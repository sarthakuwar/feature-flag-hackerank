package com.featureflag.flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.dto.BulkArchiveResult;
import com.featureflag.flags.dto.BulkRestoreResult;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.FlagUpdateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationCreateRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.dto.VariationUpdateRequest;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;

@Service
public class FlagService {

	private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

	private final FlagRepository flagRepository;

	private final FlagCacheService flagCacheService;

	private final FlagStreamService flagStreamService;

	private final AuditService auditService;

	public FlagService(FlagRepository flagRepository, FlagCacheService flagCacheService,
			FlagStreamService flagStreamService, AuditService auditService) {
		this.flagRepository = flagRepository;
		this.flagCacheService = flagCacheService;
		this.flagStreamService = flagStreamService;
		this.auditService = auditService;
	}

	public List<Flag> list(FlagLifecycle lifecycle) {
		return lifecycle == null ? flagRepository.findAll() : flagRepository.findByLifecycle(lifecycle);
	}

	public Flag getOrThrow(String key) {
		return flagRepository.findByKey(key)
				.orElseThrow(() -> new ApiException(404, "FLAG_NOT_FOUND", "No flag exists with key '" + key + "'."));
	}

	/**
	 * Idempotent: calling create twice with the same key succeeds both times and returns the same
	 * flag rather than erroring on the second call. This intentionally does not diff the submitted
	 * fields against the existing flag - "a flag with this key exists" is the only thing being
	 * made true, matching how the bulk archive/restore operations below treat an operation that has
	 * already achieved its goal as a harmless no-op rather than a conflict.
	 */
	public Flag create(FlagCreateRequest request) {
		FieldErrors errors = new FieldErrors();
		validateKey(request.key(), errors);
		validateRequiredText(request.name(), "name", errors);
		validateRequiredText(request.owner(), "owner", errors);
		validateRequiredText(request.changedBy(), "changedBy", errors);

		if (request.type() == null) {
			errors.add("type", "Flag type is required.");
		}

		List<Variation> variations = validateVariations(request.variations(), request.type(), errors);
		boolean keyIsWellFormed = request.key() != null && KEY_PATTERN.matcher(request.key()).matches();

		if (keyIsWellFormed) {
			validateDependsOn(request.dependsOn(), request.key(), errors);
		}

		if (keyIsWellFormed && errors.isEmpty()) {
			Optional<Flag> existing = flagRepository.findByKey(request.key());

			if (existing.isPresent()) {
				return existing.get();
			}
		}

		errors.throwIfAny();

		Flag flag = new Flag();
		flag.setKey(request.key());
		flag.setName(request.name());
		flag.setDescription(request.description());
		flag.setType(request.type());
		flag.setOwner(request.owner());
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setVariations(variations);
		flag.setDependsOn(request.dependsOn() == null ? List.of() : List.copyOf(request.dependsOn()));
		flag.setEnvironments(defaultEnvironments(variations));

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.CREATE, request.changedBy(),
				"Created flag '" + saved.getName() + "'.");

		return saved;
	}

	public Flag update(String key, FlagUpdateRequest request) {
		Flag flag = getOrThrow(key);
		requireEditable(flag);

		FieldErrors errors = new FieldErrors();
		validateRequiredText(request.name(), "name", errors);
		validateRequiredText(request.owner(), "owner", errors);
		validateRequiredText(request.changedBy(), "changedBy", errors);
		validateDependsOn(request.dependsOn(), key, errors);
		errors.throwIfAny();

		flag.setName(request.name());
		flag.setDescription(request.description());
		flag.setOwner(request.owner());
		flag.setDependsOn(request.dependsOn() == null ? List.of() : List.copyOf(request.dependsOn()));

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, request.changedBy(), "Updated flag details.");

		return saved;
	}

	public Flag updateEnvironment(String key, EnvironmentKey environment, EnvironmentConfigRequest request) {
		Flag flag = getOrThrow(key);
		requireEditable(flag);

		FieldErrors errors = new FieldErrors();
		validateRequiredText(request.changedBy(), "changedBy", errors);

		Set<String> variationKeys = flag.getVariations().stream().map(Variation::getKey).collect(Collectors.toSet());
		validateVariationReference(request.offVariationKey(), variationKeys, "offVariationKey", errors);

		List<Strategy> strategies = validateStrategies(request.strategies(), variationKeys, errors);

		errors.throwIfAny();

		boolean wasEnabled = flag.getEnvironments().containsKey(environment) && flag.getEnvironments().get(environment).isEnabled();
		EnvironmentConfig config = new EnvironmentConfig(request.enabled(), request.offVariationKey(), strategies);
		flag.getEnvironments().put(environment, config);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);

		AuditAction action = wasEnabled != request.enabled() ? AuditAction.TOGGLE : AuditAction.UPDATE;
		String summary = "Updated " + environment.name().toLowerCase() + " configuration ("
				+ (request.enabled() ? "enabled" : "disabled") + ", " + strategies.size() + " strateg"
				+ (strategies.size() == 1 ? "y" : "ies") + ").";
		auditService.record(saved.getKey(), action, request.changedBy(), summary);
		flagStreamService.publishFlagChanged(environment, saved.getKey(), action.name());

		return saved;
	}

	/**
	 * Multivariate flags only - boolean flags are fixed at exactly two variations (off/on) for
	 * their lifetime, enforced at create() and here. If the flag already tracks weights and the
	 * caller doesn't specify one for the new variation, every variation (existing and new) is reset
	 * to an even split - the same "no weight given = even split" default {@link #assignWeights}
	 * applies at create time. Supplying an explicit weight opts back into strict validation: the
	 * existing variations' weights are left untouched and the whole-flag total must already sum to
	 * 100, so the caller rebalances the others in the same request that adds a custom-weighted one.
	 */
	public Flag addVariation(String key, VariationCreateRequest request) {
		Flag flag = getOrThrow(key);
		requireEditable(flag);

		FieldErrors errors = new FieldErrors();
		validateRequiredText(request.changedBy(), "changedBy", errors);
		validateRequiredText(request.key(), "key", errors);
		validateRequiredText(request.name(), "name", errors);

		if (request.value() == null) {
			errors.add("value", "Variation value is required.");
		}

		if (flag.getType() == FlagType.BOOLEAN) {
			errors.add("type", "Boolean flags always have exactly two variations; use MULTIVARIATE to add more.");
		} else if (request.key() != null && flag.hasVariation(request.key())) {
			errors.add("key", "Variation keys must be unique within a flag.");
		}

		if (request.weight() != null && (request.weight() < 0 || request.weight() > 100)) {
			errors.add("weight", "Weight must be between 0 and 100.");
		}

		validatePayload(request.payloadType(), request.payloadValue(), "payloadValue", errors);

		Variation newVariation = new Variation(request.key(), request.name(), request.value(), request.weight(),
				request.payloadType(), request.payloadValue());
		List<Variation> candidate = new ArrayList<>(flag.getVariations());
		candidate.add(newVariation);

		boolean existingAreWeighted = flag.getVariations().stream().anyMatch(variation -> variation.getWeight() != null);

		if (request.weight() == null && existingAreWeighted) {
			evenSplit(candidate);
		} else {
			validateWeightInvariant(candidate, errors);
		}

		errors.throwIfAny();

		flag.setVariations(candidate);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, request.changedBy(),
				"Added variation '" + request.key() + "'.");

		return saved;
	}

	/**
	 * The variation key is immutable once created (strategies reference it); name/value/weight/
	 * payload can change. A null weight in the request keeps the variation's current weight rather
	 * than clearing it - weight is a whole-flag invariant, not optional per edit.
	 */
	public Flag updateVariation(String key, String variationKey, VariationUpdateRequest request) {
		Flag flag = getOrThrow(key);
		requireEditable(flag);

		FieldErrors errors = new FieldErrors();
		validateRequiredText(request.changedBy(), "changedBy", errors);
		validateRequiredText(request.name(), "name", errors);

		if (request.value() == null) {
			errors.add("value", "Variation value is required.");
		}

		if (request.weight() != null && (request.weight() < 0 || request.weight() > 100)) {
			errors.add("weight", "Weight must be between 0 and 100.");
		}

		validatePayload(request.payloadType(), request.payloadValue(), "payloadValue", errors);

		Variation variation = flag.getVariations().stream().filter(candidate -> candidate.getKey().equals(variationKey))
				.findFirst()
				.orElseThrow(() -> new ApiException(404, "VARIATION_NOT_FOUND",
						"Flag '" + key + "' has no variation '" + variationKey + "'."));

		Integer newWeight = request.weight() != null ? request.weight() : variation.getWeight();
		List<Variation> candidate = flag.getVariations().stream()
				.map(existing -> existing.getKey().equals(variationKey)
						? new Variation(existing.getKey(), existing.getName(), existing.getValue(), newWeight,
								existing.getPayloadType(), existing.getPayloadValue())
						: existing)
				.toList();
		validateWeightInvariant(candidate, errors);

		errors.throwIfAny();

		variation.setName(request.name());
		variation.setValue(request.value());
		variation.setWeight(newWeight);
		variation.setPayloadType(request.payloadType());
		variation.setPayloadValue(request.payloadValue());

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, request.changedBy(),
				"Updated variation '" + variationKey + "'.");

		return saved;
	}

	/**
	 * Blocked for boolean flags, if it would leave fewer than two variations, or if any
	 * environment's offVariationKey or a strategy's servedVariationKey still references it -
	 * removing a variation something is actively serving would make evaluation silently return
	 * null.
	 */
	public Flag removeVariation(String key, String variationKey, String changedBy) {
		Flag flag = getOrThrow(key);
		requireEditable(flag);

		FieldErrors errors = new FieldErrors();
		validateRequiredText(changedBy, "changedBy", errors);

		if (!flag.hasVariation(variationKey)) {
			errors.throwIfAny();
			throw new ApiException(404, "VARIATION_NOT_FOUND", "Flag '" + key + "' has no variation '" + variationKey + "'.");
		}

		if (flag.getType() == FlagType.BOOLEAN) {
			errors.add("key", "Boolean flags always have exactly two variations and cannot have one removed.");
		} else if (flag.getVariations().size() <= 2) {
			errors.add("key", "At least two variations are required; remove the flag instead if it's no longer needed.");
		}

		List<String> referencingEnvironments = environmentsReferencing(flag, variationKey);

		if (!referencingEnvironments.isEmpty()) {
			errors.add("key", "Variation '" + variationKey + "' is still in use by environment(s) " + referencingEnvironments
					+ "; update their configuration first.");
		}

		errors.throwIfAny();

		flag.getVariations().removeIf(variation -> variation.getKey().equals(variationKey));
		redistributeWeightsAfterRemoval(flag.getVariations());

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, changedBy, "Removed variation '" + variationKey + "'.");

		return saved;
	}

	/**
	 * Removing a variation frees up its weight share. Scale the remaining variations'
	 * weights up proportionally so they still sum to 100, rather than leaving a gap or
	 * forcing the caller to manually rebalance every remaining variation in the same call.
	 * Falls back to an even split if the remaining variations were all weighted at 0.
	 */
	private void redistributeWeightsAfterRemoval(List<Variation> remaining) {
		if (remaining.isEmpty()) {
			return;
		}

		boolean noneWeighted = remaining.stream().allMatch(variation -> variation.getWeight() == null);

		if (noneWeighted) {
			return;
		}

		int sum = remaining.stream().mapToInt(variation -> variation.getWeight() == null ? 0 : variation.getWeight()).sum();

		if (sum == 100) {
			return;
		}

		if (sum == 0) {
			evenSplit(remaining);

			return;
		}

		int running = 0;
		int largestIndex = 0;
		int largestOriginalWeight = -1;

		for (int index = 0; index < remaining.size(); index++) {
			int original = remaining.get(index).getWeight() == null ? 0 : remaining.get(index).getWeight();
			int scaled = (int) Math.round(original * 100.0 / sum);
			remaining.get(index).setWeight(scaled);
			running += scaled;

			if (original > largestOriginalWeight) {
				largestOriginalWeight = original;
				largestIndex = index;
			}
		}

		Variation largest = remaining.get(largestIndex);
		largest.setWeight(largest.getWeight() + (100 - running));
	}

	private List<String> environmentsReferencing(Flag flag, String variationKey) {
		List<String> referencing = new ArrayList<>();

		flag.getEnvironments().forEach((environment, config) -> {
			boolean referenced = variationKey.equals(config.getOffVariationKey())
					|| config.getStrategies().stream().anyMatch(strategy -> variationKey.equals(strategy.getServedVariationKey()));

			if (referenced) {
				referencing.add(environment.name());
			}
		});

		return referencing;
	}

	/** Idempotent: archiving an already-archived flag is a no-op success, not a conflict. */
	public Flag archive(String key, String changedBy) {
		Flag flag = getOrThrow(key);

		if (flag.getLifecycle() == FlagLifecycle.ARCHIVED) {
			return flag;
		}

		List<String> blockingDependents = activeDependentsOf(key, Set.of(key));

		if (!blockingDependents.isEmpty()) {
			throw new ApiException(409, "FLAG_HAS_DEPENDENTS",
					"Flag '" + key + "' cannot be archived: still required by active flag(s) " + blockingDependents + ".");
		}

		flag.setLifecycle(FlagLifecycle.ARCHIVED);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.ARCHIVE, changedBy, "Archived flag.");
		broadcastToAllEnvironments(saved.getKey(), AuditAction.ARCHIVE.name());

		return saved;
	}

	/** Idempotent: restoring an already-active flag is a no-op success and never touches its environments. */
	public Flag restore(String key, String changedBy) {
		Flag flag = getOrThrow(key);

		if (flag.getLifecycle() == FlagLifecycle.ACTIVE) {
			return flag;
		}

		flag.setLifecycle(FlagLifecycle.ACTIVE);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.RESTORE, changedBy, "Restored flag from archive.");
		broadcastToAllEnvironments(saved.getKey(), AuditAction.RESTORE.name());

		return saved;
	}

	/**
	 * Archives every key in the batch that can safely be archived: already-archived keys are
	 * reported as idempotent no-ops, unknown keys are reported as not found, and any key that a
	 * still-active flag (outside this batch) depends on is blocked rather than archived. Blocking
	 * is evaluated against the batch as a whole, so archiving a dependency together with its only
	 * dependent in the same call is allowed.
	 */
	public BulkArchiveResult bulkArchive(List<String> keys, String changedBy) {
		requireNonEmptyKeys(keys);

		Set<String> batch = Set.copyOf(keys);
		List<String> archived = new ArrayList<>();
		List<String> alreadyArchived = new ArrayList<>();
		Map<String, List<String>> blocked = new LinkedHashMap<>();
		List<String> notFound = new ArrayList<>();

		for (String key : keys) {
			Optional<Flag> maybeFlag = flagRepository.findByKey(key);

			if (maybeFlag.isEmpty()) {
				notFound.add(key);
				continue;
			}

			Flag flag = maybeFlag.get();

			if (flag.getLifecycle() == FlagLifecycle.ARCHIVED) {
				alreadyArchived.add(key);
				continue;
			}

			List<String> blockingDependents = activeDependentsOf(key, batch);

			if (!blockingDependents.isEmpty()) {
				blocked.put(key, blockingDependents);
				continue;
			}

			flag.setLifecycle(FlagLifecycle.ARCHIVED);
			Flag saved = flagRepository.save(flag);
			flagCacheService.put(saved);
			auditService.record(key, AuditAction.ARCHIVE, changedBy, "Archived flag (bulk).");
			broadcastToAllEnvironments(key, AuditAction.ARCHIVE.name());
			archived.add(key);
		}

		return new BulkArchiveResult(archived, alreadyArchived, blocked, notFound);
	}

	/**
	 * Restores every archived key in the batch. A key that is already ACTIVE is reported as an
	 * idempotent no-op and its environments are left completely untouched - bulk-reviving a mixed
	 * batch must never reset or disable a flag that was never archived in the first place.
	 */
	public BulkRestoreResult bulkRestore(List<String> keys, String changedBy) {
		requireNonEmptyKeys(keys);

		List<String> restored = new ArrayList<>();
		List<String> alreadyActive = new ArrayList<>();
		List<String> notFound = new ArrayList<>();

		for (String key : keys) {
			Optional<Flag> maybeFlag = flagRepository.findByKey(key);

			if (maybeFlag.isEmpty()) {
				notFound.add(key);
				continue;
			}

			Flag flag = maybeFlag.get();

			if (flag.getLifecycle() == FlagLifecycle.ACTIVE) {
				alreadyActive.add(key);
				continue;
			}

			flag.setLifecycle(FlagLifecycle.ACTIVE);
			Flag saved = flagRepository.save(flag);
			flagCacheService.put(saved);
			auditService.record(key, AuditAction.RESTORE, changedBy, "Restored flag from archive (bulk).");
			broadcastToAllEnvironments(key, AuditAction.RESTORE.name());
			restored.add(key);
		}

		return new BulkRestoreResult(restored, alreadyActive, notFound);
	}

	/** Dry run of the dependency check bulkArchive performs, without archiving anything. */
	public Map<String, List<String>> validateBulkArchive(List<String> keys) {
		requireNonEmptyKeys(keys);

		Set<String> batch = Set.copyOf(keys);
		Map<String, List<String>> blocked = new LinkedHashMap<>();

		for (String key : keys) {
			Flag flag = getOrThrow(key);

			if (flag.getLifecycle() == FlagLifecycle.ARCHIVED) {
				continue;
			}

			List<String> blockingDependents = activeDependentsOf(key, batch);

			if (!blockingDependents.isEmpty()) {
				blocked.put(key, blockingDependents);
			}
		}

		return blocked;
	}

	private void requireNonEmptyKeys(List<String> keys) {
		if (keys == null || keys.isEmpty()) {
			FieldErrors errors = new FieldErrors();
			errors.add("keys", "At least one flag key is required.");
			errors.throwIfAny();
		}
	}

	/** Active flags (outside excludingKeys) that list `key` in their dependsOn. */
	private List<String> activeDependentsOf(String key, Set<String> excludingKeys) {
		return flagRepository.findByLifecycle(FlagLifecycle.ACTIVE).stream()
				.filter(candidate -> !excludingKeys.contains(candidate.getKey()))
				.filter(candidate -> candidate.getDependsOn().contains(key))
				.map(Flag::getKey)
				.toList();
	}

	/** An archived flag is retired: it can no longer be evaluated or edited. Restore it first. */
	private void requireEditable(Flag flag) {
		if (flag.getLifecycle() == FlagLifecycle.ARCHIVED) {
			throw new ApiException(409, "FLAG_ARCHIVED",
					"Flag '" + flag.getKey() + "' is archived and cannot be modified. Restore it first.");
		}
	}

	private void broadcastToAllEnvironments(String flagKey, String action) {
		for (EnvironmentKey environment : EnvironmentKey.values()) {
			flagStreamService.publishFlagChanged(environment, flagKey, action);
		}
	}

	private Map<EnvironmentKey, EnvironmentConfig> defaultEnvironments(List<Variation> variations) {
		String offKey = variations.get(0).getKey();
		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			environments.put(environment, new EnvironmentConfig(false, offKey, new ArrayList<>()));
		}

		return environments;
	}

	private List<Variation> validateVariations(List<VariationRequest> requested, FlagType type, FieldErrors errors) {
		List<Variation> variations = new ArrayList<>();

		if (requested == null || requested.size() < 2) {
			errors.add("variations", "At least two variations are required.");

			return variations;
		}

		if (type == FlagType.BOOLEAN && requested.size() != 2) {
			errors.add("variations", "Boolean flags must have exactly two variations.");
		}

		Set<String> seenKeys = new HashSet<>();

		for (int index = 0; index < requested.size(); index++) {
			VariationRequest variationRequest = requested.get(index);
			String field = "variations[" + index + "]";
			validateRequiredText(variationRequest.key(), field + ".key", errors);
			validateRequiredText(variationRequest.name(), field + ".name", errors);

			if (variationRequest.value() == null) {
				errors.add(field + ".value", "Variation value is required.");
			}

			if (variationRequest.key() != null && !seenKeys.add(variationRequest.key())) {
				errors.add(field + ".key", "Variation keys must be unique within a flag.");
			}

			if (variationRequest.weight() != null && (variationRequest.weight() < 0 || variationRequest.weight() > 100)) {
				errors.add(field + ".weight", "Weight must be between 0 and 100.");
			}

			validatePayload(variationRequest.payloadType(), variationRequest.payloadValue(), field + ".payloadValue", errors);

			variations.add(new Variation(variationRequest.key(), variationRequest.name(), variationRequest.value(),
					variationRequest.weight(), variationRequest.payloadType(), variationRequest.payloadValue()));
		}

		assignWeights(variations, errors);

		return variations;
	}

	/**
	 * "Custom percentage" is all-or-nothing per save: either every variation specifies a weight and
	 * they sum to 100, or none do and an even split is assigned automatically - two variants
	 * default to 50/50, three to 34/33/33, and so on, remainder going to the first variations.
	 */
	private void assignWeights(List<Variation> variations, FieldErrors errors) {
		long specified = variations.stream().filter(variation -> variation.getWeight() != null).count();

		if (specified == 0) {
			evenSplit(variations);

			return;
		}

		if (specified != variations.size()) {
			errors.add("variations", "Specify a weight for every variation, or none, to use an even split.");

			return;
		}

		int sum = variations.stream().mapToInt(Variation::getWeight).sum();

		if (sum != 100) {
			errors.add("variations", "Variation weights must sum to 100 (found " + sum + ").");
		}
	}

	/** Divides 100 as evenly as possible across every variation, remainder going to the first ones. */
	private void evenSplit(List<Variation> variations) {
		int even = 100 / variations.size();
		int remainder = 100 - (even * variations.size());

		for (int index = 0; index < variations.size(); index++) {
			variations.get(index).setWeight(even + (index < remainder ? 1 : 0));
		}
	}

	/**
	 * Used by addVariation/updateVariation. A flag created through {@link #create} always has a
	 * weight on every variation (assignWeights guarantees it), so weights are only ever partially
	 * missing here for flags predating that invariant; in that legacy case, weight simply isn't
	 * being tracked yet and there's nothing to validate. Once a flag has at least one weighted
	 * variation, every variation must have one and they must sum to 100.
	 */
	private void validateWeightInvariant(List<Variation> variations, FieldErrors errors) {
		boolean noneWeighted = variations.stream().allMatch(variation -> variation.getWeight() == null);

		if (noneWeighted) {
			return;
		}

		boolean anyMissing = variations.stream().anyMatch(variation -> variation.getWeight() == null);

		if (anyMissing) {
			errors.add("weight", "Every variation must specify a weight.");

			return;
		}

		int sum = variations.stream().mapToInt(Variation::getWeight).sum();

		if (sum != 100) {
			errors.add("weight", "Variation weights must sum to 100 (found " + sum + ").");
		}
	}

	private void validatePayload(PayloadType payloadType, String payloadValue, String field, FieldErrors errors) {
		if (payloadType == null) {
			return;
		}

		if (payloadValue == null || payloadValue.isBlank()) {
			errors.add(field, "Payload value is required when a payload type is set.");

			return;
		}

		switch (payloadType) {
			case NUMBER -> {
				try {
					Double.parseDouble(payloadValue);
				} catch (NumberFormatException exception) {
					errors.add(field, "Payload value must be a valid number.");
				}
			}
			case JSON -> {
				try {
					new ObjectMapper().readTree(payloadValue);
				} catch (JsonProcessingException exception) {
					errors.add(field, "Payload value must be valid JSON.");
				}
			}
			case CSV -> {
				boolean hasNonBlankToken = Arrays.stream(payloadValue.split(",")).anyMatch(token -> !token.isBlank());

				if (!hasNonBlankToken) {
					errors.add(field, "Payload value must contain at least one comma-separated value.");
				}
			}
			case STRING -> {
				// Any non-blank value is valid; already checked above.
			}
		}
	}

	private List<Strategy> validateStrategies(List<StrategyRequest> requested, Set<String> variationKeys, FieldErrors errors) {
		List<Strategy> strategies = new ArrayList<>();
		List<StrategyRequest> requests = requested == null ? List.of() : requested;

		for (int index = 0; index < requests.size(); index++) {
			StrategyRequest strategyRequest = requests.get(index);
			String field = "strategies[" + index + "]";

			if (strategyRequest.type() == null) {
				errors.add(field + ".type", "Strategy type is required.");
			} else if (strategyRequest.type() == StrategyType.USER_ID
					&& (strategyRequest.userIds() == null || strategyRequest.userIds().isEmpty())) {
				errors.add(field + ".userIds", "At least one user id is required for a USER_ID strategy.");
			} else if (strategyRequest.type() == StrategyType.ATTRIBUTE) {
				validateRequiredText(strategyRequest.attribute(), field + ".attribute", errors);
				validateRequiredText(strategyRequest.attributeValue(), field + ".attributeValue", errors);
			} else if (strategyRequest.type() == StrategyType.PERCENTAGE_ROLLOUT
					&& (strategyRequest.percentage() < 0 || strategyRequest.percentage() > 100)) {
				errors.add(field + ".percentage", "Percentage must be between 0 and 100.");
			} else if (strategyRequest.type() == StrategyType.EXPERIMENT_SPLIT) {
				validateExperimentSplit(strategyRequest, variationKeys, field, errors);
			}

			if (strategyRequest.type() != StrategyType.EXPERIMENT_SPLIT) {
				validateVariationReference(strategyRequest.servedVariationKey(), variationKeys, field + ".servedVariationKey",
						errors);
			}

			Strategy strategy = new Strategy();
			strategy.setId(strategyRequest.id() == null || strategyRequest.id().isBlank() ? UUID.randomUUID().toString()
					: strategyRequest.id());
			strategy.setPriority(index);
			strategy.setType(strategyRequest.type());
			strategy.setUserIds(strategyRequest.userIds() == null ? List.of() : strategyRequest.userIds());
			strategy.setAttribute(strategyRequest.attribute());
			strategy.setAttributeValue(strategyRequest.attributeValue());
			strategy.setPercentage(strategyRequest.percentage());
			strategy.setServedVariationKey(strategyRequest.servedVariationKey());
			strategy.setVariationWeights(
					strategyRequest.variationWeights() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(strategyRequest.variationWeights()));
			strategy.setBanditAlgorithm(strategyRequest.banditAlgorithm() == null ? BanditAlgorithm.NONE : strategyRequest.banditAlgorithm());
			strategy.setEpsilon(strategyRequest.epsilon());
			strategies.add(strategy);
		}

		return strategies;
	}

	/**
	 * servedVariationKey is meaningless for EXPERIMENT_SPLIT (variationWeights decides the outcome
	 * for every bucket), so it's validated separately here instead of via validateVariationReference.
	 */
	private void validateExperimentSplit(StrategyRequest strategyRequest, Set<String> variationKeys, String field,
			FieldErrors errors) {
		Map<String, Integer> weights = strategyRequest.variationWeights();

		if (weights == null || weights.isEmpty()) {
			errors.add(field + ".variationWeights", "Variation weights are required for an EXPERIMENT_SPLIT strategy.");
		} else {
			if (!weights.keySet().equals(variationKeys)) {
				errors.add(field + ".variationWeights", "Weights must cover exactly this flag's variations.");
			}

			int sum = weights.values().stream().mapToInt(weight -> weight == null ? 0 : weight).sum();

			if (sum != 100) {
				errors.add(field + ".variationWeights", "Variation weights must sum to 100 (found " + sum + ").");
			}
		}

		BanditAlgorithm algorithm = strategyRequest.banditAlgorithm() == null ? BanditAlgorithm.NONE : strategyRequest.banditAlgorithm();

		if (algorithm == BanditAlgorithm.EPSILON_GREEDY) {
			Double epsilon = strategyRequest.epsilon();

			if (epsilon == null || epsilon <= 0 || epsilon > 1) {
				errors.add(field + ".epsilon", "Epsilon is required and must be between 0 (exclusive) and 1 (inclusive) for EPSILON_GREEDY.");
			}
		} else if (strategyRequest.epsilon() != null) {
			errors.add(field + ".epsilon", "Epsilon only applies to the EPSILON_GREEDY algorithm.");
		}
	}

	private void validateVariationReference(String variationKey, Set<String> variationKeys, String field, FieldErrors errors) {
		if (variationKey == null || variationKey.isBlank()) {
			errors.add(field, "A variation key is required.");
		} else if (!variationKeys.contains(variationKey)) {
			errors.add(field, "'" + variationKey + "' does not match a variation on this flag.");
		}
	}

	private void validateDependsOn(List<String> dependsOn, String ownKey, FieldErrors errors) {
		if (dependsOn == null || dependsOn.isEmpty()) {
			return;
		}

		for (int index = 0; index < dependsOn.size(); index++) {
			String dependencyKey = dependsOn.get(index);
			String field = "dependsOn[" + index + "]";

			if (dependencyKey == null || dependencyKey.isBlank()) {
				errors.add(field, "A dependency key is required.");
			} else if (dependencyKey.equals(ownKey)) {
				errors.add(field, "A flag cannot depend on itself.");
			} else if (!flagRepository.existsByKey(dependencyKey)) {
				errors.add(field, "'" + dependencyKey + "' does not match an existing flag.");
			}
		}
	}

	private void validateKey(String key, FieldErrors errors) {
		if (key == null || key.isBlank()) {
			errors.add("key", "Key is required.");
		} else if (!KEY_PATTERN.matcher(key).matches()) {
			errors.add("key", "Key must be lowercase letters, numbers, and hyphens, starting with a letter.");
		}
	}

	private void validateRequiredText(String value, String field, FieldErrors errors) {
		if (value == null || value.isBlank()) {
			errors.add(field, "This field is required.");
		}
	}
}
