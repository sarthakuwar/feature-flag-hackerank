package com.featureflag.flags;

import static com.featureflag.flags.rollout.RolloutFixtures.flag;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Field;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.featureflag.flags.targeting.Clause;
import com.featureflag.flags.targeting.ClauseOperator;
import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.flags.targeting.TargetingLookup;
import com.featureflag.flags.targeting.TargetingRule;

/**
 * Prerequisites ({@code dependsOn}): a flag is evaluated further only while every prerequisite is
 * active and enabled in the evaluated environment (and so are its own prerequisites); otherwise it
 * serves its off variation with PREREQUISITE_FAILED. Checked after the enabled check and before
 * individual targets.
 */
class FlagEvaluationPrerequisiteTest {

	private static final EnvironmentKey PROD = EnvironmentKey.PRODUCTION;

	private final Map<String, Flag> flags = new HashMap<>();

	private final AtomicInteger lookups = new AtomicInteger();

	private final FlagEvaluationService service = new FlagEvaluationService(TargetingLookup.EMPTY, key -> {
		lookups.incrementAndGet();

		return Optional.ofNullable(flags.get(key));
	}, Clock.systemUTC());

	private Flag register(String key, String... prerequisites) {
		Flag flag = flag(key);
		flag.setDependsOn(new ArrayList<>(List.of(prerequisites)));
		flags.put(key, flag);

		return flag;
	}

	private EvaluationResult evaluate(Flag flag) {
		return service.evaluate(flag, PROD, new UserContext("user-1", Map.of("plan", "pro")));
	}

	private void assertServedNormally(EvaluationResult result) {
		assertThat(result.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		assertThat(result.failedPrerequisiteKey()).isNull();
	}

	private void assertPrerequisiteFailed(EvaluationResult result, String failedKey) {
		assertThat(result.reason()).isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
		assertThat(result.variationKey()).isEqualTo("off");
		assertThat(result.value()).isEqualTo("false");
		assertThat(result.failedPrerequisiteKey()).isEqualTo(failedKey);
	}

	@Test
	void aFlagWithNoPrerequisitesNeverConsultsTheLookup() {
		assertServedNormally(evaluate(register("solo")));
		assertThat(lookups).hasValue(0);
	}

	@Test
	void aFlagWhosePrerequisitesAreAllMetIsEvaluatedNormally() {
		register("base-a");
		register("base-b");

		assertServedNormally(evaluate(register("dependent", "base-a", "base-b")));
	}

	@Test
	void aDisabledPrerequisiteServesTheOffVariationAndNamesIt() {
		register("base-a");
		production(register("base-b")).setEnabled(false);

		assertPrerequisiteFailed(evaluate(register("dependent", "base-a", "base-b")), "base-b");
	}

	@Test
	void anArchivedPrerequisiteFails() {
		register("base").setLifecycle(FlagLifecycle.ARCHIVED);

		assertPrerequisiteFailed(evaluate(register("dependent", "base")), "base");
	}

	@Test
	void aMissingPrerequisiteFails() {
		assertPrerequisiteFailed(evaluate(register("dependent", "never-created")), "never-created");
	}

	@Test
	void aPrerequisiteWithNoConfigurationForTheEnvironmentFails() {
		register("base").getEnvironments().remove(PROD);

		assertPrerequisiteFailed(evaluate(register("dependent", "base")), "base");
	}

	@Test
	void onlyTheEvaluatedEnvironmentsStateOfThePrerequisiteCounts() {
		Flag base = register("base");
		base.getEnvironments().get(EnvironmentKey.STAGING).setEnabled(false);
		Flag dependent = register("dependent", "base");

		assertServedNormally(evaluate(dependent));
		assertPrerequisiteFailed(service.evaluate(dependent, EnvironmentKey.STAGING, new UserContext("user-1", Map.of())), "base");
	}

	@Test
	void prerequisitesAreCheckedThroughTheirOwnPrerequisites() {
		production(register("root")).setEnabled(false);
		register("middle", "root");
		Flag leaf = register("leaf", "middle");

		assertPrerequisiteFailed(evaluate(leaf), "middle");

		production(flags.get("root")).setEnabled(true);

		assertServedNormally(evaluate(leaf));
	}

	@Test
	void aDisabledDependentIsReportedAsDisabledNotAsAFailedPrerequisite() {
		Flag dependent = register("dependent", "never-created");
		production(dependent).setEnabled(false);

		EvaluationResult result = evaluate(dependent);

		assertThat(result.reason()).isEqualTo(EvaluationReason.FLAG_DISABLED);
		assertThat(lookups).hasValue(0);
	}

	@Test
	void aFailedPrerequisiteBeatsIndividualTargetsRulesAndStrategies() {
		Flag dependent = register("dependent", "never-created");
		production(dependent).getIndividualTargets().add(new IndividualTarget("user", "on", Set.of("user-1")));
		TargetingRule rule = new TargetingRule();
		rule.setId("rule-1");
		rule.setServedVariationKey("on");
		rule.setClauses(List.of(new Clause("user", "plan", ClauseOperator.IN, List.of("pro"), false)));
		production(dependent).getRules().add(rule);

		assertPrerequisiteFailed(evaluate(dependent), "never-created");
	}

	@Test
	void whenPrerequisitesAreMetIndividualTargetsAreStillTheNextLayer() {
		register("base");
		Flag dependent = register("dependent", "base");
		production(dependent).getIndividualTargets().add(new IndividualTarget("user", "on", Set.of("user-1")));

		EvaluationResult result = evaluate(dependent);

		assertThat(result.reason()).isEqualTo(EvaluationReason.INDIVIDUAL_TARGET);
		assertThat(result.variationKey()).isEqualTo("on");
	}

	@Test
	void aCycleIsTreatedAsUnmetFromEveryEntryPointInsteadOfLoopingForever() {
		Flag first = register("cycle-a", "cycle-b");
		Flag second = register("cycle-b", "cycle-c");
		register("cycle-c", "cycle-a");

		assertPrerequisiteFailed(evaluate(first), "cycle-b");
		assertPrerequisiteFailed(evaluate(second), "cycle-c");
	}

	@Test
	void aSelfReferenceInStoredDataFails() {
		assertPrerequisiteFailed(evaluate(register("selfish", "selfish")), "selfish");
	}

	@Test
	void aChainDeeperThanTheGuardIsUnmetAndAShorterOneIsMet() {
		for (int index = 0; index < 30; index++) {
			if (index == 29) {
				register("chain-" + index);
			} else {
				register("chain-" + index, "chain-" + (index + 1));
			}
		}

		assertPrerequisiteFailed(evaluate(flags.get("chain-0")), "chain-1");
		assertServedNormally(evaluate(flags.get("chain-25")));
	}

	@Test
	void aWideDiamondGraphIsResolvedWithoutExponentialWork() {
		for (int level = 0; level < 8; level += 2) {
			register("diamond-" + level, "diamond-" + (level + 1) + "-a", "diamond-" + (level + 1) + "-b");
			register("diamond-" + (level + 1) + "-a", "diamond-" + (level + 2));
			register("diamond-" + (level + 1) + "-b", "diamond-" + (level + 2));
		}

		register("diamond-8");

		assertServedNormally(evaluate(flags.get("diamond-0")));
		assertThat(lookups.get()).as("each flag is looked up once, not once per path").isLessThanOrEqualTo(flags.size());
	}

	@Test
	void evaluationOfPrerequisitesDoesNoIoAndOnlyReadsTheInMemoryCache() {
		FlagRepository repository = mock(FlagRepository.class);
		FlagCacheService cache = new FlagCacheService(repository);
		Flag base = flag("base");
		Flag dependent = flag("dependent");
		dependent.setDependsOn(new ArrayList<>(List.of("base")));
		cache.put(base);
		cache.put(dependent);
		FlagEvaluationService cached = new FlagEvaluationService(TargetingLookup.EMPTY, cache, Clock.systemUTC());

		assertThat(cached.evaluate(dependent, PROD, new UserContext("user-1", Map.of())).reason())
				.isEqualTo(EvaluationReason.FALLTHROUGH);

		production(base).setEnabled(false);
		cache.put(base);

		assertThat(cached.evaluate(dependent, PROD, new UserContext("user-1", Map.of())).reason())
				.isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
		verifyNoInteractions(repository);

		for (Field field : FlagEvaluationService.class.getDeclaredFields()) {
			assertThat(field.getType().getName()).as("field " + field.getName()).doesNotContain("Repository", "Mongo");
		}
	}

	@Test
	void theSameFlagAndUserAlwaysGetTheSameAnswer() {
		register("base");
		Flag dependent = register("dependent", "base");

		EvaluationResult first = evaluate(dependent);

		for (int index = 0; index < 20; index++) {
			assertThat(evaluate(dependent)).isEqualTo(first);
		}
	}
}
