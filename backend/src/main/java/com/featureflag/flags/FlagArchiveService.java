package com.featureflag.flags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.dto.BulkArchiveResult;
import com.featureflag.flags.dto.BulkRestoreResult;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;
import com.featureflag.shared.Validation;

/**
 * Archiving and restoring flags, one at a time or in bulk. Called through {@link FlagService}. Every
 * operation that already achieved its goal (archiving an archived flag, restoring an active one) is a
 * no-op success, not a conflict. An archived flag is retired: it can no longer be evaluated or edited
 * until it is restored.
 */
@Service
public class FlagArchiveService {

	/** Most keys one bulk request may carry; larger batches are rejected rather than half-applied. */
	static final int MAX_BULK_KEYS = 100;

	private final FlagRepository flagRepository;

	private final FlagCacheService flagCacheService;

	private final FlagStreamService flagStreamService;

	private final AuditService auditService;

	private final FlagAccess flagAccess;

	public FlagArchiveService(FlagRepository flagRepository, FlagCacheService flagCacheService,
			FlagStreamService flagStreamService, AuditService auditService, FlagAccess flagAccess) {
		this.flagRepository = flagRepository;
		this.flagCacheService = flagCacheService;
		this.flagStreamService = flagStreamService;
		this.auditService = auditService;
		this.flagAccess = flagAccess;
	}

	/** Idempotent: archiving an already-archived flag is a no-op success, not a conflict. */
	public Flag archive(String key, String changedBy) {
		Flag flag = flagAccess.getFlag(key);
		requireChangedBy(changedBy);

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
		FlagChangeBroadcast.toAllEnvironments(flagStreamService, saved.getKey(), AuditAction.ARCHIVE.name());

		return saved;
	}

	/** Idempotent: restoring an already-active flag is a no-op success and never touches its environments. */
	public Flag restore(String key, String changedBy) {
		Flag flag = flagAccess.getFlag(key);
		requireChangedBy(changedBy);

		if (flag.getLifecycle() == FlagLifecycle.ACTIVE) {
			return flag;
		}

		flag.setLifecycle(FlagLifecycle.ACTIVE);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.RESTORE, changedBy, "Restored flag from archive.");
		FlagChangeBroadcast.toAllEnvironments(flagStreamService, saved.getKey(), AuditAction.RESTORE.name());

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
		requireValidBulkRequest(keys, changedBy);

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
			FlagChangeBroadcast.toAllEnvironments(flagStreamService, key, AuditAction.ARCHIVE.name());
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
		requireValidBulkRequest(keys, changedBy);

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
			FlagChangeBroadcast.toAllEnvironments(flagStreamService, key, AuditAction.RESTORE.name());
			restored.add(key);
		}

		return new BulkRestoreResult(restored, alreadyActive, notFound);
	}

	/** Dry run of the dependency check bulkArchive performs, without archiving anything. */
	public Map<String, List<String>> validateBulkArchive(List<String> keys) {
		FieldErrors errors = new FieldErrors();
		validateKeys(keys, errors);
		errors.throwIfAny();

		Set<String> batch = Set.copyOf(keys);
		Map<String, List<String>> blocked = new LinkedHashMap<>();

		for (String key : keys) {
			Flag flag = flagAccess.getFlag(key);

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

	private void requireChangedBy(String changedBy) {
		FieldErrors errors = new FieldErrors();
		Validation.requireText(changedBy, "changedBy", errors);
		errors.throwIfAny();
	}

	private void requireValidBulkRequest(List<String> keys, String changedBy) {
		FieldErrors errors = new FieldErrors();
		validateKeys(keys, errors);
		Validation.requireText(changedBy, "changedBy", errors);
		errors.throwIfAny();
	}

	/** Present, at most {@link #MAX_BULK_KEYS}, and no null or blank entry; whether a key exists is answered per key later. */
	private void validateKeys(List<String> keys, FieldErrors errors) {
		if (keys == null || keys.isEmpty()) {
			errors.add("keys", "At least one flag key is required.");

			return;
		}

		if (keys.size() > MAX_BULK_KEYS) {
			errors.add("keys", "At most " + MAX_BULK_KEYS + " flag keys can be sent in one request.");

			return;
		}

		for (int index = 0; index < keys.size(); index++) {
			if (keys.get(index) == null || keys.get(index).isBlank()) {
				errors.add("keys[" + index + "]", "A flag key is required.");
			}
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
}
