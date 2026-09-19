package com.featureflag.flags.targeting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.UserContext;
import com.featureflag.flags.targeting.dto.BulkChangeSummary;
import com.featureflag.flags.targeting.dto.SegmentCreateRequest;
import com.featureflag.flags.targeting.dto.SegmentMembersRequest;
import com.featureflag.flags.targeting.dto.SegmentMembersResponse;
import com.featureflag.flags.targeting.dto.SegmentResponse;
import com.featureflag.flags.targeting.dto.SegmentRuleRequest;
import com.featureflag.flags.targeting.dto.SegmentUpdateRequest;
import com.featureflag.flags.targeting.dto.SegmentUsageResponse;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;

/**
 * Environment-scoped segments: named, reusable groups of contexts that flag targeting rules refer to
 * by key. Every write saves to MongoDB, refreshes the in-memory {@link TargetingCacheService} that
 * evaluation reads, and pushes a {@code segment-changed} event to that environment's SDK streams.
 * A segment that any flag rule still references cannot be deleted.
 */
@Service
public class SegmentService {

	private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

	private static final Pattern CONTEXT_KIND_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{0,49}$");

	private static final String CHANGE_CREATED = "CREATE";

	private static final String CHANGE_UPDATED = "UPDATE";

	private static final String CHANGE_DELETED = "DELETE";

	private final SegmentRepository segmentRepository;

	private final FlagRepository flagRepository;

	private final TargetingCacheService targetingCacheService;

	private final FlagStreamService flagStreamService;

	public SegmentService(SegmentRepository segmentRepository, FlagRepository flagRepository,
			TargetingCacheService targetingCacheService, FlagStreamService flagStreamService) {
		this.segmentRepository = segmentRepository;
		this.flagRepository = flagRepository;
		this.targetingCacheService = targetingCacheService;
		this.flagStreamService = flagStreamService;
	}

	/** Case-insensitive match on key, name or description ("Find a segment"); sorted by name. */
	public List<Segment> list(EnvironmentKey environment, String query, String contextKind) {
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

		return segmentRepository.findByEnvironment(environment).stream()
				.filter(segment -> contextKind == null || contextKind.isBlank() || contextKind.equals(segment.getContextKind()))
				.filter(segment -> needle.isEmpty() || contains(segment.getKey(), needle) || contains(segment.getName(), needle)
						|| contains(segment.getDescription(), needle))
				.sorted(Comparator.comparing((Segment segment) -> segment.getName().toLowerCase(Locale.ROOT)))
				.toList();
	}

	public Segment getOrThrow(EnvironmentKey environment, String key) {
		return segmentRepository.findByEnvironmentAndKey(environment, key).orElseThrow(() -> new ApiException(404,
				"SEGMENT_NOT_FOUND", "No segment exists with key '" + key + "' in " + environment.name().toLowerCase() + "."));
	}

	public Segment create(EnvironmentKey environment, SegmentCreateRequest request) {
		FieldErrors errors = new FieldErrors();
		validateKey(request.key(), errors);
		requireText(request.name(), "name", errors);
		requireText(request.changedBy(), "changedBy", errors);

		String contextKind = request.contextKind() == null || request.contextKind().isBlank() ? UserContext.USER_KIND
				: request.contextKind();

		if (!CONTEXT_KIND_PATTERN.matcher(contextKind).matches()) {
			errors.add("contextKind", "Context kind must be lowercase letters, numbers, underscores and hyphens, starting with a letter.");
		}

		Set<String> included = cleanKeys(request.included(), "included", errors);
		Set<String> excluded = cleanKeys(request.excluded(), "excluded", errors);

		if (included.stream().anyMatch(excluded::contains)) {
			errors.add("excluded", "A key cannot be both included and excluded.");
		}

		if (included.size() + excluded.size() > TargetingLimits.MAX_SEGMENT_MEMBERS) {
			errors.add("included", "A segment can have at most " + TargetingLimits.MAX_SEGMENT_MEMBERS + " explicit members.");
		}

		List<SegmentRule> rules = buildRules(request.rules(), errors);

		if (request.key() != null && errors.isEmpty() && segmentRepository.existsByEnvironmentAndKey(environment, request.key())) {
			throw new ApiException(409, "SEGMENT_KEY_EXISTS",
					"A segment with key '" + request.key() + "' already exists in " + environment.name().toLowerCase() + ".");
		}

		errors.throwIfAny();

		Segment segment = new Segment();
		segment.setEnvironment(environment);
		segment.setKey(request.key());
		segment.setName(request.name().trim());
		segment.setDescription(request.description());
		segment.setContextKind(contextKind);
		segment.setIncluded(included);
		segment.setExcluded(excluded);
		segment.setRules(rules);
		segment.setUpdatedBy(request.changedBy());

		return persist(segment, CHANGE_CREATED);
	}

	/** Name, description and rules only. The key and context kind are immutable: flag rules and segment members depend on them. */
	public Segment update(EnvironmentKey environment, String key, SegmentUpdateRequest request) {
		Segment segment = getOrThrow(environment, key);
		FieldErrors errors = new FieldErrors();
		requireText(request.name(), "name", errors);
		requireText(request.changedBy(), "changedBy", errors);

		List<SegmentRule> rules = request.rules() == null ? segment.getRules() : buildRules(request.rules(), errors);
		errors.throwIfAny();

		segment.setName(request.name().trim());
		segment.setDescription(request.description());
		segment.setRules(rules);
		segment.setUpdatedBy(request.changedBy());

		return persist(segment, CHANGE_UPDATED);
	}

	/**
	 * Adds or removes many keys from the included or excluded list in one call. A key lives in at most
	 * one of the two lists, so ADD to one list removes it from the other; the summary reports how many
	 * keys actually changed state.
	 */
	public SegmentMembersResponse changeMembers(EnvironmentKey environment, String key, SegmentMembersRequest request) {
		Segment segment = getOrThrow(environment, key);
		FieldErrors errors = new FieldErrors();
		requireText(request.changedBy(), "changedBy", errors);

		if (request.list() == null) {
			errors.add("list", "List is required (INCLUDED or EXCLUDED).");
		}

		if (request.operation() == null) {
			errors.add("operation", "Operation is required (ADD or REMOVE).");
		}

		Set<String> keys = cleanKeys(request.keys(), "keys", errors);

		if (keys.isEmpty() && !errors.has("keys")) {
			errors.add("keys", "At least one key is required.");
		}

		errors.throwIfAny();

		Set<String> target = request.list() == MemberList.INCLUDED ? segment.getIncluded() : segment.getExcluded();
		Set<String> opposite = request.list() == MemberList.INCLUDED ? segment.getExcluded() : segment.getIncluded();
		int changed = 0;

		for (String memberKey : keys) {
			if (request.operation() == BulkOperation.ADD) {
				boolean movedFromOpposite = opposite.remove(memberKey);

				if (target.add(memberKey) || movedFromOpposite) {
					changed++;
				}
			} else if (target.remove(memberKey)) {
				changed++;
			}
		}

		if (segment.getIncluded().size() + segment.getExcluded().size() > TargetingLimits.MAX_SEGMENT_MEMBERS) {
			errors.add("keys", "A segment can have at most " + TargetingLimits.MAX_SEGMENT_MEMBERS + " explicit members.");
			errors.throwIfAny();
		}

		BulkChangeSummary summary = new BulkChangeSummary(keys.size(), changed, keys.size() - changed);

		if (changed == 0) {
			return new SegmentMembersResponse(summary, SegmentResponse.from(segment));
		}

		segment.setUpdatedBy(request.changedBy());

		return new SegmentMembersResponse(summary, SegmentResponse.from(persist(segment, CHANGE_UPDATED)));
	}

	public void delete(EnvironmentKey environment, String key, String changedBy) {
		Segment segment = getOrThrow(environment, key);
		FieldErrors errors = new FieldErrors();
		requireText(changedBy, "changedBy", errors);
		errors.throwIfAny();

		List<SegmentUsageResponse> usages = usage(environment, key);

		if (!usages.isEmpty()) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("usedBy", usages);

			throw new ApiException(409, "SEGMENT_IN_USE", "Segment '" + key + "' is still referenced by " + usages.size()
					+ " flag rule(s); remove it from those rules first.", details);
		}

		segmentRepository.delete(segment);
		targetingCacheService.evictSegment(environment, key);
		flagStreamService.publishSegmentChanged(environment, key, CHANGE_DELETED);
	}

	/** Every flag rule (any lifecycle - a restored flag must not be left pointing at a deleted segment) that references the segment. */
	public List<SegmentUsageResponse> usage(EnvironmentKey environment, String key) {
		getOrThrow(environment, key);
		List<SegmentUsageResponse> usages = new ArrayList<>();

		for (Flag flag : flagRepository.findAll()) {
			EnvironmentConfig config = flag.getEnvironments().get(environment);

			if (config == null) {
				continue;
			}

			for (TargetingRule rule : config.getRules()) {
				boolean references = rule.getClauses().stream()
						.anyMatch(clause -> clause.getOperator() == ClauseOperator.IN_SEGMENT && clause.getValues().contains(key));

				if (references) {
					usages.add(new SegmentUsageResponse(flag.getKey(), flag.getName(), flag.getLifecycle(), rule.getId(),
							rule.getDescription()));
				}
			}
		}

		return usages;
	}

	private Segment persist(Segment segment, String change) {
		Segment saved = segmentRepository.save(segment);
		targetingCacheService.putSegment(saved);
		flagStreamService.publishSegmentChanged(saved.getEnvironment(), saved.getKey(), change);

		return saved;
	}

	private List<SegmentRule> buildRules(List<SegmentRuleRequest> requested, FieldErrors errors) {
		List<SegmentRule> rules = new ArrayList<>();

		if (requested == null || requested.isEmpty()) {
			return rules;
		}

		if (requested.size() > TargetingLimits.MAX_SEGMENT_RULES) {
			errors.add("rules", "A segment can have at most " + TargetingLimits.MAX_SEGMENT_RULES + " rules.");

			return rules;
		}

		Set<String> seenIds = new LinkedHashSet<>();

		for (int index = 0; index < requested.size(); index++) {
			SegmentRuleRequest request = requested.get(index);
			String field = "rules[" + index + "]";

			if (request == null) {
				errors.add(field, "Rule is required.");
				continue;
			}

			SegmentRule rule = new SegmentRule();
			rule.setId(request.id() == null || request.id().isBlank() ? UUID.randomUUID().toString() : request.id());
			rule.setDescription(request.description());
			rule.setClauses(ClauseValidator.validate(request.clauses(), field + ".clauses", null, errors));

			if (!seenIds.add(rule.getId())) {
				errors.add(field + ".id", "Rule ids must be unique within a segment.");
			}

			rules.add(rule);
		}

		return rules;
	}

	private Set<String> cleanKeys(List<String> raw, String field, FieldErrors errors) {
		Set<String> keys = new LinkedHashSet<>();

		if (raw == null) {
			return keys;
		}

		if (raw.size() > TargetingLimits.MAX_SEGMENT_MEMBERS) {
			errors.add(field, "At most " + TargetingLimits.MAX_SEGMENT_MEMBERS + " keys per request.");

			return keys;
		}

		for (String key : raw) {
			String trimmed = key == null ? "" : key.trim();

			if (trimmed.isEmpty()) {
				continue;
			}

			if (trimmed.length() > TargetingLimits.MAX_VALUE_LENGTH) {
				errors.add(field, "Each key must be at most " + TargetingLimits.MAX_VALUE_LENGTH + " characters.");
			} else {
				keys.add(trimmed);
			}
		}

		return keys;
	}

	private boolean contains(String haystack, String lowerCaseNeedle) {
		return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
	}

	private void validateKey(String key, FieldErrors errors) {
		if (key == null || key.isBlank()) {
			errors.add("key", "Key is required.");
		} else if (!KEY_PATTERN.matcher(key).matches()) {
			errors.add("key", "Key must be lowercase letters, numbers, and hyphens, starting with a letter.");
		}
	}

	private void requireText(String value, String field, FieldErrors errors) {
		if (value == null || value.isBlank()) {
			errors.add(field, "This field is required.");
		}
	}
}
