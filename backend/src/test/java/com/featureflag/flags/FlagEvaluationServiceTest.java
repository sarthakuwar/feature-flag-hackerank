package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FlagEvaluationServiceTest {

	private final FlagEvaluationService evaluationService = new FlagEvaluationService();

	@Test
	void disabledEnvironmentReturnsOffVariation() {
		Flag flag = booleanFlag(config(false, "off", List.of(rollout("on", 100))));

		EvaluationResult result = evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION, new UserContext("u1", Map.of()));

		assertThat(result.variationKey()).isEqualTo("off");
		assertThat(result.reason()).isEqualTo(EvaluationReason.FLAG_DISABLED);
	}

	@Test
	void missingEnvironmentConfigFallsBackToFirstVariationInstedOfThrowing() {
		Flag flag = booleanFlag(config(false, "off", List.of(rollout("on", 100))));
		flag.getEnvironments().remove(EnvironmentKey.STAGING);

		EvaluationResult result = evaluationService.evaluate(flag, EnvironmentKey.STAGING, new UserContext("u1", Map.of()));

		assertThat(result.variationKey()).isEqualTo("off");
		assertThat(result.reason()).isEqualTo(EvaluationReason.FLAG_DISABLED);
	}

	@Test
	void userIdStrategyMatchWinsOverRollout() {
		Strategy strategy = userIdStrategy(0, List.of("vip-user"), "on");
		Flag flag = booleanFlag(config(true, "off", List.of(strategy, rollout("on", 0))));

		EvaluationResult result = evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION,
				new UserContext("vip-user", Map.of()));

		assertThat(result.variationKey()).isEqualTo("on");
		assertThat(result.reason()).isEqualTo(EvaluationReason.STRATEGY_MATCH);
		assertThat(result.matchedStrategyId()).isEqualTo(strategy.getId());
	}

	@Test
	void attributeStrategyMatchesOnEquality() {
		Strategy strategy = attributeStrategy(0, "country", "US", "on");
		Flag flag = booleanFlag(config(true, "off", List.of(strategy)));

		EvaluationResult usResult = evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION,
				new UserContext("u1", Map.of("country", "US")));
		EvaluationResult caResult = evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION,
				new UserContext("u2", Map.of("country", "CA")));

		assertThat(usResult.variationKey()).isEqualTo("on");
		assertThat(usResult.reason()).isEqualTo(EvaluationReason.STRATEGY_MATCH);
		assertThat(caResult.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		assertThat(caResult.variationKey()).isEqualTo("off");
	}

	@Test
	void firstMatchingStrategyWinsWhenMultipleStrategiesMatch() {
		Strategy first = userIdStrategy(0, List.of("shared-user"), "on");
		Strategy second = attributeStrategy(1, "plan", "pro", "off");
		Flag flag = booleanFlag(config(true, "off", List.of(second, first)));

		EvaluationResult result = evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION,
				new UserContext("shared-user", Map.of("plan", "pro")));

		assertThat(result.variationKey()).isEqualTo("on");
		assertThat(result.matchedStrategyId()).isEqualTo(first.getId());
	}

	@Test
	void zeroPercentRolloutFallsThroughToOffVariation() {
		Flag flag = booleanFlag(config(true, "off", List.of(rollout("on", 0))));

		for (int i = 0; i < 50; i++) {
			EvaluationResult result = evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION,
					new UserContext("user-" + i, Map.of()));
			assertThat(result.variationKey()).isEqualTo("off");
			assertThat(result.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		}
	}

	@Test
	void hundredPercentRolloutAlwaysServesEnabledVariation() {
		Flag flag = booleanFlag(config(true, "off", List.of(rollout("on", 100))));

		for (int i = 0; i < 50; i++) {
			EvaluationResult result = evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION,
					new UserContext("user-" + i, Map.of()));
			assertThat(result.variationKey()).isEqualTo("on");
			assertThat(result.reason()).isEqualTo(EvaluationReason.ROLLOUT_ENABLED);
		}
	}

	@Test
	void rolloutStrategyFallsThroughToNextStrategyWhenBucketMisses() {
		// 0% rollout never matches, so this should fall through to the second strategy.
		Strategy missedRollout = rollout("on", 0);
		Strategy catchAll = userIdStrategy(1, List.of("vip-user"), "on");
		Flag flag = booleanFlag(config(true, "off", List.of(missedRollout, catchAll)));

		EvaluationResult result = evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION,
				new UserContext("vip-user", Map.of()));

		assertThat(result.variationKey()).isEqualTo("on");
		assertThat(result.reason()).isEqualTo(EvaluationReason.STRATEGY_MATCH);
	}

	@Test
	void sameUserAlwaysLandsInSameBucketAcrossRepeatedEvaluations() {
		int firstBucket = evaluationService.bucketFor("rollout-flag", new UserContext("stable-user", Map.of()));
		int secondBucket = evaluationService.bucketFor("rollout-flag", new UserContext("stable-user", Map.of()));

		assertThat(firstBucket).isEqualTo(secondBucket);
	}

	@Test
	void bucketMatchesIndependentlyComputedMd5HashModulo100() throws NoSuchAlgorithmException {
		String flagKey = "rollout-flag";
		String userId = "user-42";

		int expectedBucket = independentBucket(flagKey, userId);
		int actualBucket = evaluationService.bucketFor(flagKey, new UserContext(userId, Map.of()));

		assertThat(actualBucket).isEqualTo(expectedBucket);
		assertThat(actualBucket).isBetween(0, 99);
	}

	@Test
	void missingUserContextFallsThroughToAnonymousBucketingWithoutThrowing() {
		Flag flag = booleanFlag(config(true, "off", List.of(rollout("on", 100))));

		EvaluationResult result = evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION, null);

		assertThat(result.variationKey()).isEqualTo("on");
	}

	private int independentBucket(String flagKey, String userId) throws NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("MD5");
		byte[] hash = digest.digest((flagKey + ":" + userId).getBytes(StandardCharsets.UTF_8));
		int unsignedValue = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16) | ((hash[2] & 0xFF) << 8) | (hash[3] & 0xFF);

		return Math.floorMod(unsignedValue, 100);
	}

	private Flag booleanFlag(EnvironmentConfig environmentConfig) {
		Flag flag = new Flag();
		flag.setKey("rollout-flag");
		flag.setName("Rollout Flag");
		flag.setType(FlagType.BOOLEAN);
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setVariations(new ArrayList<>(List.of(new Variation("off", "Off", "false"), new Variation("on", "On", "true"))));

		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);
		environments.put(EnvironmentKey.PRODUCTION, environmentConfig);
		flag.setEnvironments(environments);

		return flag;
	}

	private EnvironmentConfig config(boolean enabled, String offVariationKey, List<Strategy> strategies) {
		return new EnvironmentConfig(enabled, offVariationKey, new ArrayList<>(strategies));
	}

	private Strategy rollout(String servedVariationKey, int percentage) {
		Strategy strategy = new Strategy();
		strategy.setId("strategy-rollout-" + percentage);
		strategy.setPriority(0);
		strategy.setType(StrategyType.PERCENTAGE_ROLLOUT);
		strategy.setPercentage(percentage);
		strategy.setServedVariationKey(servedVariationKey);

		return strategy;
	}

	private Strategy userIdStrategy(int priority, List<String> userIds, String servedVariationKey) {
		Strategy strategy = new Strategy();
		strategy.setId("strategy-" + priority + "-user-id");
		strategy.setPriority(priority);
		strategy.setType(StrategyType.USER_ID);
		strategy.setUserIds(userIds);
		strategy.setServedVariationKey(servedVariationKey);

		return strategy;
	}

	private Strategy attributeStrategy(int priority, String attribute, String attributeValue, String servedVariationKey) {
		Strategy strategy = new Strategy();
		strategy.setId("strategy-" + priority + "-attribute");
		strategy.setPriority(priority);
		strategy.setType(StrategyType.ATTRIBUTE);
		strategy.setAttribute(attribute);
		strategy.setAttributeValue(attributeValue);
		strategy.setServedVariationKey(servedVariationKey);

		return strategy;
	}
}
