package com.featureflag.flags.rollout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.ProgressiveRollout;
import com.featureflag.flags.RolloutStage;
import com.featureflag.flags.RolloutStatus;
import com.featureflag.flags.Strategy;
import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.rollout.dto.StartProgressiveRolloutRequest;
import com.featureflag.flags.targeting.TargetingRule;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;

/**
 * The control plane for progressive rollouts: gradually moving a rule's audience from the variation
 * it serves today to a new one over a schedule. The rule is a targeting rule (the current model) or,
 * for existing callers, a legacy activation strategy. Like {@link
 * com.featureflag.flags.experiment.ExperimentService} it always mutates the flag fetched fresh from
 * {@link FlagRepository} before saving and refreshing {@link FlagCacheService}, and broadcasts the
 * change over the existing stream so SDK caches pick it up within seconds.
 *
 * <p>Traffic advancing from stage to stage needs no writes: evaluation computes the current
 * percentage from the stored schedule. The only time-driven write is {@link #completeDueRollouts},
 * which turns a rollout that has reached its final 100% stage into the rule's permanent state.
 *
 * <p>A rollout can start on a rule only if it serves one fixed variation and is enabled, and only if,
 * in that environment, no other progressive rollout is active and no experiment exists. This app has no guarded rollouts and no migration flag type, so
 * the other two conditions in the product spec are unrepresentable here rather than checked.
 */
@Service
public class ProgressiveRolloutService {

	private static final Logger LOG = LoggerFactory.getLogger(ProgressiveRolloutService.class);

	static final String SYSTEM_COMPLETED_BY = "system:rollout";

	private static final Duration START_CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(1);

	private final FlagRepository flagRepository;

	private final FlagCacheService flagCacheService;

	private final FlagStreamService flagStreamService;

	private final AuditService auditService;

	private final Clock clock;

	public ProgressiveRolloutService(FlagRepository flagRepository, FlagCacheService flagCacheService,
			FlagStreamService flagStreamService, AuditService auditService, Clock clock) {
		this.flagRepository = flagRepository;
		this.flagCacheService = flagCacheService;
		this.flagStreamService = flagStreamService;
		this.auditService = auditService;
		this.clock = clock;
	}

	/** Newest first, optionally narrowed to one status. Readable for archived flags too. */
	public List<ProgressiveRollout> list(String key, EnvironmentKey environment, RolloutStatus status) {
		Flag flag = getFlag(key);
		EnvironmentConfig config = requireEnvironment(flag, environment);

		return config.getProgressiveRollouts().stream()
				.filter(rollout -> status == null || rollout.getStatus() == status)
				.sorted(Comparator.comparing(ProgressiveRollout::getCreatedAt).reversed())
				.toList();
	}

	public ProgressiveRollout get(String key, EnvironmentKey environment, String rolloutId) {
		Flag flag = getFlag(key);

		return findRollout(requireEnvironment(flag, environment), rolloutId, key);
	}

	public ProgressiveRollout start(String key, EnvironmentKey environment, StartProgressiveRolloutRequest request) {
		Flag flag = getEditableFlag(key);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);

		FieldErrors errors = new FieldErrors();
		validateRequiredText(request.changedBy(), "changedBy", errors);
		boolean hasRule = request.ruleId() != null && !request.ruleId().isBlank();
		boolean hasStrategy = request.strategyId() != null && !request.strategyId().isBlank();

		if (hasRule && hasStrategy) {
			errors.add("ruleId", "Provide either ruleId or strategyId, not both.");
		} else if (!hasRule && !hasStrategy) {
			errors.add("ruleId", "A ruleId (or, for a legacy strategy, a strategyId) is required.");
		}

		if (request.toVariationKey() == null || request.toVariationKey().isBlank()) {
			errors.add("toVariationKey", "A variation key is required.");
		} else if (!flag.hasVariation(request.toVariationKey())) {
			errors.add("toVariationKey", "'" + request.toVariationKey() + "' does not match a variation on this flag.");
		}

		Instant startAt = request.startAt() == null ? now : request.startAt().truncatedTo(ChronoUnit.MILLIS);

		if (startAt.isBefore(now.minus(START_CLOCK_SKEW_TOLERANCE))) {
			errors.add("startAt", "The start time cannot be in the past.");
		}

		List<RolloutStage> stages = RolloutSchedule.resolve(request.stages(), request.steps(), request.durationMinutes(), errors);

		errors.throwIfAny();

		String targetId;
		String fromVariationKey;

		if (hasRule) {
			TargetingRule rule = config.getRules().stream().filter(candidate -> request.ruleId().equals(candidate.getId())).findFirst()
					.orElseThrow(() -> new ApiException(404, "RULE_NOT_FOUND",
							"Flag '" + key + "' has no targeting rule '" + request.ruleId() + "' in '" + environment + "'."));

			requireRampable(rule);
			targetId = rule.getId();
			fromVariationKey = rule.getServedVariationKey();
		} else {
			Strategy strategy = config.getStrategies().stream().filter(candidate -> request.strategyId().equals(candidate.getId()))
					.findFirst()
					.orElseThrow(() -> new ApiException(404, "STRATEGY_NOT_FOUND",
							"Flag '" + key + "' has no strategy '" + request.strategyId() + "' in '" + environment + "'."));

			targetId = strategy.getId();
			fromVariationKey = strategy.getServedVariationKey();
		}

		requireEligible(config, key, environment);

		if (request.toVariationKey().equals(fromVariationKey)) {
			errors.add("toVariationKey", "The rule already serves '" + request.toVariationKey() + "'; choose a different variation to roll out.");
			errors.throwIfAny();
		}

		ProgressiveRollout rollout = new ProgressiveRollout();
		rollout.setId(UUID.randomUUID().toString());

		if (hasRule) {
			rollout.setRuleId(targetId);
		} else {
			rollout.setStrategyId(targetId);
		}

		rollout.setFromVariationKey(fromVariationKey);
		rollout.setToVariationKey(request.toVariationKey());
		rollout.setStages(new ArrayList<>(stages));
		rollout.setStatus(RolloutStatus.ACTIVE);
		rollout.setStartAt(startAt);
		rollout.setCreatedAt(now);
		rollout.setStartedBy(request.changedBy());
		config.getProgressiveRollouts().add(rollout);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.ROLLOUT_START, request.changedBy(),
				"Started progressive rollout in " + environment.name().toLowerCase() + ": '" + rollout.getFromVariationKey()
						+ "' to '" + rollout.getToVariationKey() + "' over " + stages.size() + " stages.");
		flagStreamService.publishFlagChanged(environment, saved.getKey(), AuditAction.ROLLOUT_START.name());

		return rollout;
	}

	/**
	 * Ends an active rollout immediately and makes the targeting rule serve {@code variationKey}
	 * from now on - the way to pause a release (keep the old variation), respond to an issue, or
	 * finish early (choose the new one). Any variation on the flag is a valid choice.
	 */
	public ProgressiveRollout stop(String key, EnvironmentKey environment, String rolloutId, String variationKey, String changedBy) {
		Flag flag = getEditableFlag(key);
		EnvironmentConfig config = requireEnvironment(flag, environment);
		ProgressiveRollout rollout = findRollout(config, rolloutId, key);

		FieldErrors errors = new FieldErrors();
		validateRequiredText(changedBy, "changedBy", errors);

		if (variationKey == null || variationKey.isBlank()) {
			errors.add("variationKey", "A variation key is required.");
		} else if (!flag.hasVariation(variationKey)) {
			errors.add("variationKey", "'" + variationKey + "' does not match a variation on this flag.");
		}

		errors.throwIfAny();

		if (!rollout.active()) {
			throw new ApiException(409, "ROLLOUT_NOT_ACTIVE",
					"Progressive rollout '" + rolloutId + "' is already " + rollout.getStatus().name().toLowerCase() + ".");
		}

		Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
		int percentage = rollout.percentageAt(now);

		end(config, rollout, RolloutStatus.STOPPED, variationKey, percentage, changedBy, now);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.ROLLOUT_STOP, changedBy,
				"Stopped progressive rollout in " + environment.name().toLowerCase() + " at " + percentage + "%; the rule now serves '"
						+ variationKey + "'.");
		flagStreamService.publishFlagChanged(environment, saved.getKey(), AuditAction.ROLLOUT_STOP.name());

		return rollout;
	}

	/**
	 * Called on a timer. Finds active rollouts whose final stage has begun and makes the rule
	 * permanently serve the rollout variation. Candidates are found from the in-memory cache (no
	 * database scan every tick); each is then re-read from the repository and re-checked before
	 * writing, so a rollout stopped in the meantime is left alone. One flag failing never stops the
	 * rest. Returns how many rollouts were completed.
	 */
	public int completeDueRollouts() {
		Instant now = clock.instant();
		List<DueRollout> due = new ArrayList<>();

		for (Flag flag : flagCacheService.all()) {
			if (flag.getLifecycle() != FlagLifecycle.ACTIVE) {
				continue;
			}

			flag.getEnvironments().forEach((environment, config) -> config.activeProgressiveRollout()
					.filter(rollout -> !rollout.completesAt().isAfter(now))
					.ifPresent(rollout -> due.add(new DueRollout(flag.getKey(), environment))));
		}

		int completed = 0;

		for (DueRollout target : due) {
			try {
				if (complete(target.flagKey(), target.environment(), now)) {
					completed++;
				}
			} catch (RuntimeException exception) {
				LOG.error("Failed to complete progressive rollout for flag '{}' in {}", target.flagKey(), target.environment(),
						exception);
			}
		}

		return completed;
	}

	private record DueRollout(String flagKey, EnvironmentKey environment) {
	}

	private boolean complete(String key, EnvironmentKey environment, Instant now) {
		Flag flag = flagRepository.findByKey(key).orElse(null);

		if (flag == null || flag.getLifecycle() != FlagLifecycle.ACTIVE) {
			return false;
		}

		EnvironmentConfig config = flag.getEnvironments().get(environment);
		ProgressiveRollout rollout = config == null ? null : config.activeProgressiveRollout().orElse(null);

		if (rollout == null || rollout.completesAt().isAfter(now)) {
			return false;
		}

		end(config, rollout, RolloutStatus.COMPLETED, rollout.getToVariationKey(), 100, SYSTEM_COMPLETED_BY,
				now.truncatedTo(ChronoUnit.MILLIS));

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.ROLLOUT_COMPLETE, SYSTEM_COMPLETED_BY,
				"Progressive rollout in " + environment.name().toLowerCase() + " reached 100%; the rule now serves '"
						+ rollout.getToVariationKey() + "'.");
		flagStreamService.publishFlagChanged(environment, saved.getKey(), AuditAction.ROLLOUT_COMPLETE.name());

		return true;
	}

	/** Rewrites the rule to its permanent variation and records the rollout as finished. */
	private void end(EnvironmentConfig config, ProgressiveRollout rollout, RolloutStatus status, String resolvedVariationKey,
			int finalPercentage, String endedBy, Instant endedAt) {
		if (rollout.targetsRule()) {
			config.getRules().stream().filter(rule -> rollout.getRuleId().equals(rule.getId())).findFirst()
					.ifPresent(rule -> rule.setServedVariationKey(resolvedVariationKey));
		} else {
			config.getStrategies().stream().filter(strategy -> rollout.getStrategyId().equals(strategy.getId())).findFirst()
					.ifPresent(strategy -> strategy.setServedVariationKey(resolvedVariationKey));
		}

		rollout.setStatus(status);
		rollout.setEndedAt(endedAt);
		rollout.setEndedBy(endedBy);
		rollout.setResolvedVariationKey(resolvedVariationKey);
		rollout.setFinalPercentage(finalPercentage);
	}

	/**
	 * Only a rule that serves one fixed variation has a single "from" to move away from, and it has to
	 * be switched on: a disabled rule serves nobody, so the ramp would advance with no effect and
	 * later rewrite the rule without anyone ever having seen the change.
	 */
	private void requireRampable(TargetingRule rule) {
		if (rule.servesRollout()) {
			FieldErrors errors = new FieldErrors();
			errors.add("ruleId", "Rule '" + rule.getId() + "' serves a percentage rollout; only a rule that serves one variation can be ramped.");
			errors.throwIfAny();
		}

		if (!rule.isEnabled()) {
			throw new ApiException(409, "RULE_DISABLED",
					"Rule '" + rule.getId() + "' is disabled; enable it before starting a progressive rollout on it.");
		}
	}

	private void requireEligible(EnvironmentConfig config, String key, EnvironmentKey environment) {
		if (config.activeProgressiveRollout().isPresent()) {
			throw new ApiException(409, "ROLLOUT_ALREADY_ACTIVE",
					"Flag '" + key + "' already has an active progressive rollout in '" + environment + "'.");
		}

		if (config.hasExperiment()) {
			throw new ApiException(409, "EXPERIMENT_ACTIVE", "Flag '" + key + "' has an experiment in '" + environment
					+ "'; a progressive rollout cannot run alongside it.");
		}
	}

	private ProgressiveRollout findRollout(EnvironmentConfig config, String rolloutId, String key) {
		return config.getProgressiveRollouts().stream().filter(rollout -> rollout.getId().equals(rolloutId)).findFirst()
				.orElseThrow(() -> new ApiException(404, "ROLLOUT_NOT_FOUND",
						"Flag '" + key + "' has no progressive rollout '" + rolloutId + "'."));
	}

	private EnvironmentConfig requireEnvironment(Flag flag, EnvironmentKey environment) {
		EnvironmentConfig config = flag.getEnvironments().get(environment);

		if (config == null) {
			throw new ApiException(404, "ENVIRONMENT_NOT_FOUND",
					"Flag '" + flag.getKey() + "' has no configuration for environment '" + environment + "'.");
		}

		return config;
	}

	private Flag getFlag(String key) {
		return flagRepository.findByKey(key)
				.orElseThrow(() -> new ApiException(404, "FLAG_NOT_FOUND", "No flag exists with key '" + key + "'."));
	}

	private Flag getEditableFlag(String key) {
		Flag flag = getFlag(key);

		if (flag.getLifecycle() == FlagLifecycle.ARCHIVED) {
			throw new ApiException(409, "FLAG_ARCHIVED",
					"Flag '" + key + "' is archived and cannot be modified. Restore it first.");
		}

		return flag;
	}

	private void validateRequiredText(String value, String field, FieldErrors errors) {
		if (value == null || value.isBlank()) {
			errors.add(field, "This field is required.");
		}
	}
}
