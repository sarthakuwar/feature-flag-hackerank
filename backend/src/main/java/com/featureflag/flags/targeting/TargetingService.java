package com.featureflag.flags.targeting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.UserContext;
import com.featureflag.flags.Variation;
import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.targeting.dto.BulkChangeSummary;
import com.featureflag.flags.targeting.dto.BulkTargetRequest;
import com.featureflag.flags.targeting.dto.BulkTargetResponse;
import com.featureflag.flags.targeting.dto.IndividualTargetRequest;
import com.featureflag.flags.targeting.dto.IndividualTargetsRequest;
import com.featureflag.flags.targeting.dto.RuleEnabledRequest;
import com.featureflag.flags.targeting.dto.RuleMoveRequest;
import com.featureflag.flags.targeting.dto.RuleOrderRequest;
import com.featureflag.flags.targeting.dto.TargetingResponse;
import com.featureflag.flags.targeting.dto.TargetingRuleRequest;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;

/**
 * The write side of a flag environment's targeting: ordered rules (create, edit, enable/disable,
 * reorder, delete), individual targets, and bulk key changes. Every write follows the same path the
 * rest of the flag admin surface does - mutate the flag freshly loaded from MongoDB, save it, refresh
 * the in-memory {@link FlagCacheService} evaluation reads from, audit it, and push a change event to
 * SDK streams - so evaluation itself never does I/O.
 */
@Service
public class TargetingService {

	private static final Pattern CONTEXT_KIND_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{0,49}$");

	private final FlagRepository flagRepository;

	private final SegmentRepository segmentRepository;

	private final FlagCacheService flagCacheService;

	private final FlagStreamService flagStreamService;

	private final AuditService auditService;

	public TargetingService(FlagRepository flagRepository, SegmentRepository segmentRepository,
			FlagCacheService flagCacheService, FlagStreamService flagStreamService, AuditService auditService) {
		this.flagRepository = flagRepository;
		this.segmentRepository = segmentRepository;
		this.flagCacheService = flagCacheService;
		this.flagStreamService = flagStreamService;
		this.auditService = auditService;
	}

	/** Readable for archived flags too - archiving retires a flag from evaluation, it doesn't hide its history. */
	public TargetingResponse get(String flagKey, EnvironmentKey environment) {
		Flag flag = loadFlag(flagKey);

		return TargetingResponse.from(requireEnvironment(flag, environment));
	}

	public TargetingRule createRule(String flagKey, EnvironmentKey environment, TargetingRuleRequest request) {
		Flag flag = loadEditableFlag(flagKey);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		FieldErrors errors = new FieldErrors();
		requireText(request.changedBy(), "changedBy", errors);

		TargetingRule rule = buildRule(UUID.randomUUID().toString(), request, request.enabled() == null || request.enabled(),
				false, flag, environment, errors);

		int size = config.getRules().size();
		int position = request.position() == null ? size : request.position();

		if (position < 0 || position > size) {
			errors.add("position", "Position must be between 0 and " + size + ".");
		}

		if (size >= TargetingLimits.MAX_RULES_PER_ENVIRONMENT) {
			errors.addFormError("A flag environment can have at most " + TargetingLimits.MAX_RULES_PER_ENVIRONMENT
					+ " targeting rules.");
		}

		checkValueLimit(config.getRules(), rule, errors);
		errors.throwIfAny();

		config.getRules().add(position, rule);
		save(flag, environment, request.changedBy(), "Added targeting rule " + describe(rule) + " in "
				+ environment.name().toLowerCase() + ".");

		return rule;
	}

	/** Replaces a rule's conditions and served variation in place; a null enabled keeps its current state. */
	public TargetingRule updateRule(String flagKey, EnvironmentKey environment, String ruleId, TargetingRuleRequest request) {
		Flag flag = loadEditableFlag(flagKey);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		int index = indexOfRule(config, ruleId);
		TargetingRule existing = config.getRules().get(index);
		requireUpdateCompatibleWithActiveRollout(config, existing, request);

		FieldErrors errors = new FieldErrors();
		requireText(request.changedBy(), "changedBy", errors);

		TargetingRule updated = buildRule(ruleId, request, request.enabled() == null ? existing.isEnabled() : request.enabled(),
				existing.isIncludeInExperiment(), flag, environment, errors);

		List<TargetingRule> others = new ArrayList<>(config.getRules());
		others.remove(index);
		checkValueLimit(others, updated, errors);
		errors.throwIfAny();

		config.getRules().set(index, updated);
		save(flag, environment, request.changedBy(), "Updated targeting rule " + describe(updated) + " in "
				+ environment.name().toLowerCase() + ".");

		return updated;
	}

	/**
	 * While a progressive rollout is ramping a rule, its audience (clauses), description, order and
	 * enabled state can all change, but not what it serves: that is the rollout's "from", and turning it
	 * into a percentage rollout would leave the ramp with nothing to move away from. Stopping the
	 * rollout first is how a caller decides what the rule serves instead.
	 */
	private void requireUpdateCompatibleWithActiveRollout(EnvironmentConfig config, TargetingRule existing,
			TargetingRuleRequest request) {
		if (config.activeRolloutForRule(existing.getId()).isEmpty()) {
			return;
		}

		boolean nowServesRollout = request.rolloutWeights() != null && !request.rolloutWeights().isEmpty();

		if (nowServesRollout || !Objects.equals(existing.getServedVariationKey(), request.servedVariationKey())) {
			throw new ApiException(409, "ROLLOUT_IN_PROGRESS", "Rule '" + existing.getId()
					+ "' has an active progressive rollout, so the variation it serves cannot change; stop the rollout first.");
		}
	}

	/** Disabling keeps the rule (and its position) but evaluation skips it; enabling puts it straight back to work. */
	public TargetingRule setRuleEnabled(String flagKey, EnvironmentKey environment, String ruleId, RuleEnabledRequest request) {
		Flag flag = loadEditableFlag(flagKey);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		FieldErrors errors = new FieldErrors();
		requireText(request.changedBy(), "changedBy", errors);
		errors.throwIfAny();

		TargetingRule rule = config.getRules().get(indexOfRule(config, ruleId));

		if (rule.isEnabled() == request.enabled()) {
			return rule;
		}

		rule.setEnabled(request.enabled());
		save(flag, environment, request.changedBy(), (request.enabled() ? "Enabled" : "Disabled") + " targeting rule "
				+ describe(rule) + " in " + environment.name().toLowerCase() + ".");

		return rule;
	}

	public TargetingResponse deleteRule(String flagKey, EnvironmentKey environment, String ruleId, String changedBy) {
		Flag flag = loadEditableFlag(flagKey);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		FieldErrors errors = new FieldErrors();
		requireText(changedBy, "changedBy", errors);
		errors.throwIfAny();

		int index = indexOfRule(config, ruleId);

		if (config.activeRolloutForRule(ruleId).isPresent()) {
			throw new ApiException(409, "ROLLOUT_IN_PROGRESS",
					"Rule '" + ruleId + "' has an active progressive rollout and cannot be deleted; stop the rollout first.");
		}

		TargetingRule removed = config.getRules().remove(index);
		Flag saved = save(flag, environment, changedBy, "Deleted targeting rule " + describe(removed) + " in "
				+ environment.name().toLowerCase() + ".");

		return TargetingResponse.from(saved.getEnvironments().get(environment));
	}

	/** The request must be a permutation of the current rule ids, so a stale client can't silently drop or duplicate a rule. */
	public TargetingResponse reorderRules(String flagKey, EnvironmentKey environment, RuleOrderRequest request) {
		Flag flag = loadEditableFlag(flagKey);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		FieldErrors errors = new FieldErrors();
		requireText(request.changedBy(), "changedBy", errors);

		List<String> requested = request.ruleIds() == null ? List.of() : request.ruleIds();
		Map<String, TargetingRule> existingById = new LinkedHashMap<>();
		config.getRules().forEach(rule -> existingById.put(rule.getId(), rule));

		if (requested.size() != existingById.size() || !new HashSet<>(requested).equals(existingById.keySet())) {
			errors.add("ruleIds", "ruleIds must list every existing rule id exactly once.");
		}

		errors.throwIfAny();

		if (requested.equals(new ArrayList<>(existingById.keySet()))) {
			return TargetingResponse.from(config);
		}

		List<TargetingRule> reordered = requested.stream().map(existingById::get).collect(Collectors.toCollection(ArrayList::new));
		config.setRules(reordered);
		Flag saved = save(flag, environment, request.changedBy(), "Reordered targeting rules in "
				+ environment.name().toLowerCase() + ".");

		return TargetingResponse.from(saved.getEnvironments().get(environment));
	}

	/** Moving the first rule up or the last rule down is a no-op success, not an error: nothing is saved or broadcast. */
	public TargetingResponse moveRule(String flagKey, EnvironmentKey environment, String ruleId, RuleMoveRequest request) {
		Flag flag = loadEditableFlag(flagKey);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		FieldErrors errors = new FieldErrors();
		requireText(request.changedBy(), "changedBy", errors);

		if (request.direction() == null) {
			errors.add("direction", "Direction is required (UP or DOWN).");
		}

		errors.throwIfAny();

		int index = indexOfRule(config, ruleId);
		int target = request.direction() == MoveDirection.UP ? index - 1 : index + 1;

		if (target < 0 || target >= config.getRules().size()) {
			return TargetingResponse.from(config);
		}

		TargetingRule moving = config.getRules().remove(index);
		config.getRules().add(target, moving);
		Flag saved = save(flag, environment, request.changedBy(), "Moved targeting rule " + describe(moving) + " "
				+ request.direction().name().toLowerCase() + " in " + environment.name().toLowerCase() + ".");

		return TargetingResponse.from(saved.getEnvironments().get(environment));
	}

	/** Replaces every individual target in the environment. Entries with no keys are dropped. */
	public TargetingResponse replaceIndividualTargets(String flagKey, EnvironmentKey environment,
			IndividualTargetsRequest request) {
		Flag flag = loadEditableFlag(flagKey);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		FieldErrors errors = new FieldErrors();
		requireText(request.changedBy(), "changedBy", errors);

		Set<String> variationKeys = variationKeysOf(flag);
		List<IndividualTargetRequest> requested = request.targets() == null ? List.of() : request.targets();
		Map<String, Map<String, IndividualTarget>> byKindAndVariation = new LinkedHashMap<>();
		Map<String, Map<String, String>> variationByKindAndKey = new LinkedHashMap<>();

		for (int index = 0; index < requested.size(); index++) {
			IndividualTargetRequest target = requested.get(index);
			String field = "targets[" + index + "]";

			if (target == null) {
				errors.add(field, "Target is required.");
				continue;
			}

			String kind = normalizeKind(target.contextKind());
			validateKind(kind, field + ".contextKind", errors);
			validateVariationReference(target.variationKey(), variationKeys, field + ".variationKey", errors);
			Set<String> keys = cleanKeys(target.keys(), field + ".keys", errors);

			for (String key : keys) {
				String previous = variationByKindAndKey.computeIfAbsent(kind, unused -> new HashMap<>()).put(key,
						String.valueOf(target.variationKey()));

				if (previous != null && !previous.equals(String.valueOf(target.variationKey()))) {
					errors.add(field + ".keys", "'" + key + "' is targeted to more than one variation.");
				}
			}

			if (!keys.isEmpty()) {
				byKindAndVariation.computeIfAbsent(kind, unused -> new LinkedHashMap<>())
						.computeIfAbsent(String.valueOf(target.variationKey()),
								unused -> new IndividualTarget(kind, target.variationKey(), null))
						.getKeys().addAll(keys);
			}
		}

		List<IndividualTarget> replacement = byKindAndVariation.values().stream()
				.flatMap(byVariation -> byVariation.values().stream()).collect(Collectors.toCollection(ArrayList::new));
		checkIndividualKeyLimit(replacement, errors);
		errors.throwIfAny();

		config.setIndividualTargets(replacement);
		Flag saved = save(flag, environment, request.changedBy(), "Replaced individual targets in "
				+ environment.name().toLowerCase() + " (" + countKeys(replacement) + " key(s)).");

		return TargetingResponse.from(saved.getEnvironments().get(environment));
	}

	/**
	 * Adds or removes many keys for one variation in a single call - the API behind pasting a list of
	 * user ids into the targeting UI. Blank keys are ignored, duplicates collapse, and the summary says
	 * how many keys actually changed state versus already were there.
	 */
	public BulkTargetResponse bulkTarget(String flagKey, EnvironmentKey environment, BulkTargetRequest request) {
		Flag flag = loadEditableFlag(flagKey);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		FieldErrors errors = new FieldErrors();
		requireText(request.changedBy(), "changedBy", errors);

		String kind = normalizeKind(request.contextKind());
		validateKind(kind, "contextKind", errors);
		validateVariationReference(request.variationKey(), variationKeysOf(flag), "variationKey", errors);

		if (request.operation() == null) {
			errors.add("operation", "Operation is required (ADD or REMOVE).");
		}

		Set<String> keys = cleanKeys(request.keys(), "keys", errors);

		if (keys.isEmpty() && !errors.has("keys")) {
			errors.add("keys", "At least one key is required.");
		}

		errors.throwIfAny();

		int changed = request.operation() == BulkOperation.ADD ? addKeys(config, kind, request.variationKey(), keys)
				: removeKeys(config, kind, request.variationKey(), keys);
		config.getIndividualTargets().removeIf(target -> target.getKeys().isEmpty());

		FieldErrors limitErrors = new FieldErrors();
		checkIndividualKeyLimit(config.getIndividualTargets(), limitErrors);
		limitErrors.throwIfAny();

		BulkChangeSummary summary = new BulkChangeSummary(keys.size(), changed, keys.size() - changed);

		if (changed == 0) {
			return new BulkTargetResponse(summary, TargetingResponse.from(config));
		}

		Flag saved = save(flag, environment, request.changedBy(),
				(request.operation() == BulkOperation.ADD ? "Targeted " : "Untargeted ") + changed + " " + kind
						+ " key(s) " + (request.operation() == BulkOperation.ADD ? "to" : "from") + " variation '"
						+ request.variationKey() + "' in " + environment.name().toLowerCase() + ".");

		return new BulkTargetResponse(summary, TargetingResponse.from(saved.getEnvironments().get(environment)));
	}

	private int addKeys(EnvironmentConfig config, String kind, String variationKey, Set<String> keys) {
		IndividualTarget destination = config.getIndividualTargets().stream()
				.filter(target -> kind.equals(target.getContextKind()) && variationKey.equals(target.getVariationKey()))
				.findFirst().orElse(null);
		int changed = 0;

		for (String key : keys) {
			if (destination != null && destination.getKeys().contains(key)) {
				continue;
			}

			config.getIndividualTargets().stream()
					.filter(target -> kind.equals(target.getContextKind()) && !variationKey.equals(target.getVariationKey()))
					.forEach(target -> target.getKeys().remove(key));

			if (destination == null) {
				destination = new IndividualTarget(kind, variationKey, null);
				config.getIndividualTargets().add(destination);
			}

			destination.getKeys().add(key);
			changed++;
		}

		return changed;
	}

	private int removeKeys(EnvironmentConfig config, String kind, String variationKey, Set<String> keys) {
		int changed = 0;

		for (IndividualTarget target : config.getIndividualTargets()) {
			if (!kind.equals(target.getContextKind()) || !variationKey.equals(target.getVariationKey())) {
				continue;
			}

			for (String key : keys) {
				if (target.getKeys().remove(key)) {
					changed++;
				}
			}
		}

		return changed;
	}

	private TargetingRule buildRule(String id, TargetingRuleRequest request, boolean enabled,
			boolean currentIncludeInExperiment, Flag flag, EnvironmentKey environment, FieldErrors errors) {
		TargetingRule rule = new TargetingRule();
		rule.setId(id);
		rule.setDescription(request.description() == null || request.description().isBlank() ? null
				: request.description().trim());
		rule.setEnabled(enabled);
		rule.setClauses(ClauseValidator.validate(request.clauses(), "clauses",
				segmentKey -> segmentRepository.findByEnvironmentAndKey(environment, segmentKey), errors));

		Set<String> variationKeys = variationKeysOf(flag);
		boolean hasRollout = request.rolloutWeights() != null && !request.rolloutWeights().isEmpty();
		boolean hasFixed = request.servedVariationKey() != null && !request.servedVariationKey().isBlank();

		if (hasRollout && hasFixed) {
			errors.add("servedVariationKey", "Provide either servedVariationKey or rolloutWeights, not both.");
		} else if (hasRollout) {
			validateRollout(request.rolloutWeights(), variationKeys, errors);
			rule.setRolloutWeights(new LinkedHashMap<>(request.rolloutWeights()));
		} else {
			validateVariationReference(request.servedVariationKey(), variationKeys, "servedVariationKey", errors);
			rule.setServedVariationKey(request.servedVariationKey());
		}

		if (Boolean.TRUE.equals(request.includeInExperiment()) && !hasRollout) {
			errors.add("includeInExperiment",
					"Only a rule that serves a percentage rollout can be included in an experiment.");
		}

		// Unspecified keeps what the rule had, but never carries over onto a rule that no longer serves a rollout.
		boolean include = request.includeInExperiment() == null ? currentIncludeInExperiment : request.includeInExperiment();
		rule.setIncludeInExperiment(include && hasRollout);

		if (rule.getDescription() != null && rule.getDescription().length() > 200) {
			errors.add("description", "Description must be at most 200 characters.");
		}

		return rule;
	}

	/** Same contract as an experiment split: keys are real variations and the shares total exactly 100. */
	private void validateRollout(Map<String, Integer> weights, Set<String> variationKeys, FieldErrors errors) {
		int sum = 0;

		for (Map.Entry<String, Integer> entry : weights.entrySet()) {
			Integer weight = entry.getValue();

			if (!variationKeys.contains(entry.getKey())) {
				errors.add("rolloutWeights", "'" + entry.getKey() + "' does not match a variation on this flag.");
			}

			if (weight == null || weight < 0 || weight > 100) {
				errors.add("rolloutWeights", "Weight for '" + entry.getKey() + "' must be between 0 and 100.");
			} else {
				sum += weight;
			}
		}

		if (sum != 100) {
			errors.add("rolloutWeights", "Rollout weights must sum to 100 (found " + sum + ").");
		}
	}

	private void checkValueLimit(List<TargetingRule> otherRules, TargetingRule candidate, FieldErrors errors) {
		int total = candidate.valueCount() + otherRules.stream().mapToInt(TargetingRule::valueCount).sum();

		if (total > TargetingLimits.MAX_VALUES_PER_ENVIRONMENT) {
			errors.addFormError("A flag environment can have at most " + TargetingLimits.MAX_VALUES_PER_ENVIRONMENT
					+ " values across all targeting rules (this change would make it " + total + ").");
		}
	}

	private void checkIndividualKeyLimit(List<IndividualTarget> targets, FieldErrors errors) {
		int total = countKeys(targets);

		if (total > TargetingLimits.MAX_INDIVIDUAL_KEYS_PER_ENVIRONMENT) {
			errors.addFormError("A flag environment can have at most " + TargetingLimits.MAX_INDIVIDUAL_KEYS_PER_ENVIRONMENT
					+ " individually targeted keys (this change would make it " + total + ").");
		}
	}

	private int countKeys(List<IndividualTarget> targets) {
		return targets.stream().mapToInt(target -> target.getKeys().size()).sum();
	}

	private Set<String> cleanKeys(List<String> raw, String field, FieldErrors errors) {
		Set<String> keys = new LinkedHashSet<>();

		if (raw == null) {
			return keys;
		}

		if (raw.size() > TargetingLimits.MAX_INDIVIDUAL_KEYS_PER_ENVIRONMENT) {
			errors.add(field, "At most " + TargetingLimits.MAX_INDIVIDUAL_KEYS_PER_ENVIRONMENT + " keys per request.");

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

	private String normalizeKind(String kind) {
		return kind == null || kind.isBlank() ? UserContext.USER_KIND : kind;
	}

	private void validateKind(String kind, String field, FieldErrors errors) {
		if (!CONTEXT_KIND_PATTERN.matcher(kind).matches()) {
			errors.add(field, "Context kind must be lowercase letters, numbers, underscores and hyphens, starting with a letter.");
		}
	}

	private void validateVariationReference(String variationKey, Set<String> variationKeys, String field, FieldErrors errors) {
		if (variationKey == null || variationKey.isBlank()) {
			errors.add(field, "A variation key is required.");
		} else if (!variationKeys.contains(variationKey)) {
			errors.add(field, "'" + variationKey + "' does not match a variation on this flag.");
		}
	}

	private Set<String> variationKeysOf(Flag flag) {
		return flag.getVariations().stream().map(Variation::getKey).collect(Collectors.toSet());
	}

	private int indexOfRule(EnvironmentConfig config, String ruleId) {
		for (int index = 0; index < config.getRules().size(); index++) {
			if (config.getRules().get(index).getId().equals(ruleId)) {
				return index;
			}
		}

		throw new ApiException(404, "RULE_NOT_FOUND", "No targeting rule exists with id '" + ruleId + "'.");
	}

	private Flag save(Flag flag, EnvironmentKey environment, String changedBy, String summary) {
		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, changedBy, summary);
		flagStreamService.publishFlagChanged(environment, saved.getKey(), AuditAction.UPDATE.name());

		return saved;
	}

	private String describe(TargetingRule rule) {
		return rule.getDescription() == null ? "'" + rule.getId() + "'" : "'" + rule.getDescription() + "'";
	}

	private Flag loadFlag(String flagKey) {
		return flagRepository.findByKey(flagKey)
				.orElseThrow(() -> new ApiException(404, "FLAG_NOT_FOUND", "No flag exists with key '" + flagKey + "'."));
	}

	private Flag loadEditableFlag(String flagKey) {
		Flag flag = loadFlag(flagKey);

		if (flag.getLifecycle() == FlagLifecycle.ARCHIVED) {
			throw new ApiException(409, "FLAG_ARCHIVED",
					"Flag '" + flag.getKey() + "' is archived and cannot be modified. Restore it first.");
		}

		return flag;
	}

	private EnvironmentConfig requireEnvironment(Flag flag, EnvironmentKey environment) {
		EnvironmentConfig config = flag.getEnvironments().get(environment);

		if (config == null) {
			throw new ApiException(404, "ENVIRONMENT_NOT_FOUND",
					"Flag '" + flag.getKey() + "' has no configuration for environment '" + environment + "'.");
		}

		return config;
	}

	private void requireText(String value, String field, FieldErrors errors) {
		if (value == null || value.isBlank()) {
			errors.add(field, "This field is required.");
		}
	}
}
