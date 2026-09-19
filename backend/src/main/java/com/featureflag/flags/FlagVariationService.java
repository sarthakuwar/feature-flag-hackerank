package com.featureflag.flags;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.dto.VariationCreateRequest;
import com.featureflag.flags.dto.VariationUpdateRequest;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;
import com.featureflag.shared.Validation;

/**
 * Adding, editing, and removing the variations of an existing flag. Called through
 * {@link FlagService}; each write validates, saves, refreshes the cache, records an audit entry, and
 * tells every environment's stream subscribers, since a variation belongs to the whole flag.
 */
@Service
public class FlagVariationService {

	private final FlagRepository flagRepository;

	private final FlagCacheService flagCacheService;

	private final FlagStreamService flagStreamService;

	private final AuditService auditService;

	private final FlagAccess flagAccess;

	public FlagVariationService(FlagRepository flagRepository, FlagCacheService flagCacheService,
			FlagStreamService flagStreamService, AuditService auditService, FlagAccess flagAccess) {
		this.flagRepository = flagRepository;
		this.flagCacheService = flagCacheService;
		this.flagStreamService = flagStreamService;
		this.auditService = auditService;
		this.flagAccess = flagAccess;
	}

	/**
	 * Multivariate flags only - boolean flags are fixed at exactly two variations (off/on) for
	 * their lifetime, enforced at create() and here. If the flag already tracks weights and the
	 * caller doesn't specify one for the new variation, every variation (existing and new) is reset
	 * to an even split - the same "no weight given = even split" default {@link VariationWeights#assign}
	 * applies at create time. Supplying an explicit weight opts back into strict validation: the
	 * existing variations' weights are left untouched and the whole-flag total must already sum to
	 * 100, so the caller rebalances the others in the same request that adds a custom-weighted one.
	 */
	public Flag add(String key, VariationCreateRequest request) {
		Flag flag = flagAccess.getEditableFlag(key);

		FieldErrors errors = new FieldErrors();
		Validation.requireText(request.changedBy(), "changedBy", errors);
		Validation.requireText(request.key(), "key", errors);
		Validation.requireText(request.name(), "name", errors);

		if (request.value() == null) {
			errors.add("value", "Variation value is required.");
		}

		if (flag.getType() == FlagType.BOOLEAN) {
			errors.add("type", "Boolean flags always have exactly two variations; use MULTIVARIATE to add more.");
		} else if (request.key() != null && flag.hasVariation(request.key())) {
			errors.add("key", "Variation keys must be unique within a flag.");
		}

		VariationValidator.validateWeightRange(request.weight(), "weight", errors);
		VariationValidator.validatePayload(request.payloadType(), request.payloadValue(), "payloadValue", errors);

		Variation newVariation = new Variation(request.key(), request.name(), request.value(), request.weight(),
				request.payloadType(), request.payloadValue());
		List<Variation> candidate = new ArrayList<>(flag.getVariations());
		candidate.add(newVariation);

		boolean existingAreWeighted = flag.getVariations().stream().anyMatch(variation -> variation.getWeight() != null);

		if (request.weight() == null && existingAreWeighted) {
			VariationWeights.evenSplit(candidate);
		} else {
			VariationWeights.validateInvariant(candidate, errors);
		}

		errors.throwIfAny();

		flag.setVariations(candidate);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, request.changedBy(),
				"Added variation '" + request.key() + "'.");
		FlagChangeBroadcast.toAllEnvironments(flagStreamService, saved.getKey(), AuditAction.UPDATE.name());

		return saved;
	}

	/**
	 * The variation key is immutable once created (strategies reference it); name/value/weight/
	 * payload can change. A null weight in the request keeps the variation's current weight rather
	 * than clearing it - weight is a whole-flag invariant, not optional per edit.
	 */
	public Flag update(String key, String variationKey, VariationUpdateRequest request) {
		Flag flag = flagAccess.getEditableFlag(key);

		FieldErrors errors = new FieldErrors();
		Validation.requireText(request.changedBy(), "changedBy", errors);
		Validation.requireText(request.name(), "name", errors);

		if (request.value() == null) {
			errors.add("value", "Variation value is required.");
		}

		VariationValidator.validateWeightRange(request.weight(), "weight", errors);
		VariationValidator.validatePayload(request.payloadType(), request.payloadValue(), "payloadValue", errors);

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
		VariationWeights.validateInvariant(candidate, errors);

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
		FlagChangeBroadcast.toAllEnvironments(flagStreamService, saved.getKey(), AuditAction.UPDATE.name());

		return saved;
	}

	/**
	 * Blocked for boolean flags, if it would leave fewer than two variations, or if any
	 * environment's offVariationKey, a strategy's servedVariationKey or variationWeights key (an
	 * experiment split lists every variation, even at weight 0), or anything else still references
	 * it - removing a variation something is actively serving would make evaluation silently
	 * return null.
	 */
	public Flag remove(String key, String variationKey, String changedBy) {
		Flag flag = flagAccess.getEditableFlag(key);

		FieldErrors errors = new FieldErrors();
		Validation.requireText(changedBy, "changedBy", errors);

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
		VariationWeights.redistributeAfterRemoval(flag.getVariations());

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, changedBy, "Removed variation '" + variationKey + "'.");
		FlagChangeBroadcast.toAllEnvironments(flagStreamService, saved.getKey(), AuditAction.UPDATE.name());

		return saved;
	}

	/** Every field of an environment that can point at a variation must be checked here; see EnvironmentConfig. */
	private List<String> environmentsReferencing(Flag flag, String variationKey) {
		List<String> referencing = new ArrayList<>();

		flag.getEnvironments().forEach((environment, config) -> {
			boolean referenced = variationKey.equals(config.getOffVariationKey())
					|| config.getStrategies().stream().anyMatch(strategy -> variationKey.equals(strategy.getServedVariationKey())
							|| strategy.getVariationWeights().containsKey(variationKey))
					|| config.getIndividualTargets().stream().anyMatch(target -> variationKey.equals(target.getVariationKey()))
					|| config.getRules().stream().anyMatch(rule -> rule.referencedVariationKeys().contains(variationKey))
					|| config.getProgressiveRollouts().stream()
							.anyMatch(rollout -> rollout.active() && variationKey.equals(rollout.getToVariationKey()));

			if (referenced) {
				referencing.add(environment.name());
			}
		});

		return referencing;
	}
}
