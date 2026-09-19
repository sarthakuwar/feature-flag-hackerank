package com.featureflag.flags;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.dto.BulkArchiveResult;
import com.featureflag.flags.dto.BulkRestoreResult;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.FlagUpdateRequest;
import com.featureflag.flags.dto.VariationCreateRequest;
import com.featureflag.flags.dto.VariationUpdateRequest;
import com.featureflag.shared.FieldErrors;
import com.featureflag.shared.PageParams;
import com.featureflag.shared.Validation;

/**
 * The service the flag admin controller calls. It owns flag creation, detail edits, and environment
 * configuration, and delegates variation edits to {@link FlagVariationService} and archive/restore
 * (single and bulk) to {@link FlagArchiveService}. Field validation lives in
 * {@link VariationValidator} and {@link StrategyValidator}.
 */
@Service
public class FlagService {

	private static final Sort LIST_ORDER = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("key"));

	private final FlagRepository flagRepository;

	private final FlagCacheService flagCacheService;

	private final FlagStreamService flagStreamService;

	private final AuditService auditService;

	private final FlagAccess flagAccess;

	private final FlagVariationService variationService;

	private final FlagArchiveService archiveService;

	public FlagService(FlagRepository flagRepository, FlagCacheService flagCacheService,
			FlagStreamService flagStreamService, AuditService auditService, FlagAccess flagAccess,
			FlagVariationService variationService, FlagArchiveService archiveService) {
		this.flagRepository = flagRepository;
		this.flagCacheService = flagCacheService;
		this.flagStreamService = flagStreamService;
		this.auditService = auditService;
		this.flagAccess = flagAccess;
		this.variationService = variationService;
		this.archiveService = archiveService;
	}

	/**
	 * One page of flags, oldest first with the key as tie-break so pages never overlap. {@code query} is a
	 * case-insensitive substring of the key, name or owner; it is quoted, so regex characters match literally.
	 */
	public Page<Flag> list(FlagLifecycle lifecycle, String query, PageParams params) {
		List<FlagLifecycle> lifecycles = lifecycle == null ? List.of(FlagLifecycle.values()) : List.of(lifecycle);
		String pattern = query == null || query.isBlank() ? "" : Pattern.quote(query.trim());

		return flagRepository.search(lifecycles, pattern, params.toPageable(LIST_ORDER));
	}

	public Flag getOrThrow(String key) {
		return flagAccess.getFlag(key);
	}

	/**
	 * Idempotent: calling create twice with the same key succeeds both times and returns the same
	 * flag rather than erroring on the second call. This intentionally does not diff the submitted
	 * fields against the existing flag - "a flag with this key exists" is the only thing being
	 * made true, matching how the bulk archive/restore operations treat an operation that has
	 * already achieved its goal as a harmless no-op rather than a conflict. A concurrent create that
	 * wins the unique index between the lookup and the save is the same case: the loser returns the
	 * winner's flag.
	 */
	public Flag create(FlagCreateRequest request) {
		FieldErrors errors = new FieldErrors();
		Validation.validateKey(request.key(), errors);
		Validation.requireText(request.name(), "name", errors);
		Validation.requireText(request.owner(), "owner", errors);
		Validation.requireText(request.changedBy(), "changedBy", errors);

		if (request.type() == null) {
			errors.add("type", "Flag type is required.");
		}

		List<Variation> variations = VariationValidator.validateAll(request.variations(), request.type(), errors);
		boolean keyIsWellFormed = Validation.isWellFormedKey(request.key());

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

		Flag saved;

		try {
			saved = flagRepository.save(flag);
		} catch (DuplicateKeyException exception) {
			return flagRepository.findByKey(request.key()).orElseThrow(() -> exception);
		}

		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.CREATE, request.changedBy(),
				"Created flag '" + saved.getName() + "'.");
		FlagChangeBroadcast.toAllEnvironments(flagStreamService, saved.getKey(), AuditAction.CREATE.name());

		return saved;
	}

	public Flag update(String key, FlagUpdateRequest request) {
		Flag flag = flagAccess.getEditableFlag(key);

		FieldErrors errors = new FieldErrors();
		Validation.requireText(request.name(), "name", errors);
		Validation.requireText(request.owner(), "owner", errors);
		Validation.requireText(request.changedBy(), "changedBy", errors);
		validateDependsOn(request.dependsOn(), key, errors);
		errors.throwIfAny();

		flag.setName(request.name());
		flag.setDescription(request.description());
		flag.setOwner(request.owner());
		flag.setDependsOn(request.dependsOn() == null ? List.of() : List.copyOf(request.dependsOn()));

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, request.changedBy(), "Updated flag details.");
		FlagChangeBroadcast.toAllEnvironments(flagStreamService, saved.getKey(), AuditAction.UPDATE.name());

		return saved;
	}

	public Flag updateEnvironment(String key, EnvironmentKey environment, EnvironmentConfigRequest request) {
		Flag flag = flagAccess.getEditableFlag(key);

		FieldErrors errors = new FieldErrors();
		Validation.requireText(request.changedBy(), "changedBy", errors);

		if (request.enabled() == null) {
			errors.add("enabled", "Enabled is required (true or false).");
		}

		Set<String> variationKeys = flag.getVariations().stream().map(Variation::getKey).collect(Collectors.toSet());
		Validation.validateVariationReference(request.offVariationKey(), variationKeys, "offVariationKey", errors);

		List<Strategy> strategies = StrategyValidator.validate(request.strategies(), variationKeys, errors);

		errors.throwIfAny();

		EnvironmentConfig existing = flag.getEnvironments().get(environment);
		boolean enabled = request.enabled();
		boolean wasEnabled = existing != null && existing.isEnabled();
		List<ProgressiveRollout> rollouts = existing == null ? new ArrayList<>() : existing.getProgressiveRollouts();

		StrategyValidator.requireCompatibleWithActiveRollout(existing, strategies);

		EnvironmentConfig config = new EnvironmentConfig(enabled, request.offVariationKey(), strategies);
		config.setProgressiveRollouts(rollouts);

		if (existing != null) {
			// This endpoint owns on/off, the off variation and legacy strategies; targeting rules and
			// individual targets have their own endpoints and must survive an unrelated save here.
			config.setRules(existing.getRules());
			config.setIndividualTargets(existing.getIndividualTargets());
		}

		flag.getEnvironments().put(environment, config);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);

		AuditAction action = wasEnabled != enabled ? AuditAction.TOGGLE : AuditAction.UPDATE;
		String summary = "Updated " + environment.name().toLowerCase() + " configuration ("
				+ (enabled ? "enabled" : "disabled") + ", " + strategies.size() + " strateg"
				+ (strategies.size() == 1 ? "y" : "ies") + ").";
		auditService.record(saved.getKey(), action, request.changedBy(), summary);
		flagStreamService.publishFlagChanged(environment, saved.getKey(), action.name());

		return saved;
	}

	public Flag addVariation(String key, VariationCreateRequest request) {
		return variationService.add(key, request);
	}

	public Flag updateVariation(String key, String variationKey, VariationUpdateRequest request) {
		return variationService.update(key, variationKey, request);
	}

	public Flag removeVariation(String key, String variationKey, String changedBy) {
		return variationService.remove(key, variationKey, changedBy);
	}

	public Flag archive(String key, String changedBy) {
		return archiveService.archive(key, changedBy);
	}

	public Flag restore(String key, String changedBy) {
		return archiveService.restore(key, changedBy);
	}

	public BulkArchiveResult bulkArchive(List<String> keys, String changedBy) {
		return archiveService.bulkArchive(keys, changedBy);
	}

	public BulkRestoreResult bulkRestore(List<String> keys, String changedBy) {
		return archiveService.bulkRestore(keys, changedBy);
	}

	public Map<String, List<String>> validateBulkArchive(List<String> keys) {
		return archiveService.validateBulkArchive(keys);
	}

	private Map<EnvironmentKey, EnvironmentConfig> defaultEnvironments(List<Variation> variations) {
		String offKey = variations.get(0).getKey();
		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			environments.put(environment, new EnvironmentConfig(false, offKey, new ArrayList<>()));
		}

		return environments;
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
}
