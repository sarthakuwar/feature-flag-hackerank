package com.featureflag.flags;

import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.flag;
import static com.featureflag.flags.rollout.RolloutFixtures.flagWithTargetingRule;
import static com.featureflag.flags.rollout.RolloutFixtures.percentageRule;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static com.featureflag.flags.rollout.RolloutFixtures.ruleRollout;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Bucketing: identical to the documented MD5 hash whenever a user id is present; a caller with only
 * other contexts is bucketed by a deterministic context key instead of all sharing one bucket; and a
 * percentage that only grows never removes a user who was already included.
 */
class FlagEvaluationBucketingTest {

	private static final EnvironmentKey PROD = EnvironmentKey.PRODUCTION;

	private static final int USERS = 3000;

	private final FlagEvaluationService service = new FlagEvaluationService();

	private static int md5Bucket(String flagKey, String bucketingId) throws NoSuchAlgorithmException {
		byte[] hash = MessageDigest.getInstance("MD5").digest((flagKey + ":" + bucketingId).getBytes(StandardCharsets.UTF_8));
		int unsigned = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16) | ((hash[2] & 0xFF) << 8) | (hash[3] & 0xFF);

		return Math.floorMod(unsigned, 100);
	}

	private static UserContext contextsOnly(Map<String, ContextEntry> contexts) {
		return new UserContext(null, Map.of(), contexts);
	}

	@Test
	void aPresentUserIdIsHashedExactlyAsBeforeWhateverOtherContextsSay() throws NoSuchAlgorithmException {
		for (int index = 0; index < 200; index++) {
			String userId = "user-" + index;
			UserContext withOrganization = new UserContext(userId, Map.of(),
					Map.of("organization", new ContextEntry("acme", Map.of())));

			assertThat(service.bucketFor("f", withOrganization)).isEqualTo(md5Bucket("f", userId));
			assertThat(service.bucketFor("f", UserContext.anonymous(userId))).isEqualTo(md5Bucket("f", userId));
		}
	}

	@Test
	void aCallerWithoutAUserIdIsBucketedByItsContextKey() throws NoSuchAlgorithmException {
		UserContext organization = contextsOnly(Map.of("organization", new ContextEntry("acme", Map.of())));

		assertThat(service.bucketFor("f", organization)).isEqualTo(md5Bucket("f", "acme"));
	}

	@Test
	void differentOrganizationsSpreadAcrossBucketsInsteadOfAllOrNothing() {
		Set<Integer> buckets = new HashSet<>();

		for (int index = 0; index < 200; index++) {
			buckets.add(service.bucketFor("f", contextsOnly(Map.of("organization", new ContextEntry("org-" + index, Map.of())))));
		}

		assertThat(buckets.size()).isGreaterThan(50);
	}

	@Test
	void theFirstKindInAlphabeticalOrderWinsAndBlankKeysAreSkipped() throws NoSuchAlgorithmException {
		Map<String, ContextEntry> contexts = new LinkedHashMap<>();
		contexts.put("organization", new ContextEntry("acme", Map.of()));
		contexts.put("ld_device", new ContextEntry("  ", Map.of()));
		contexts.put("ld_application", new ContextEntry("mobile-app", Map.of()));

		assertThat(service.bucketFor("f", contextsOnly(contexts))).isEqualTo(md5Bucket("f", "mobile-app"));
	}

	@Test
	void aBlankUserIdCountsAsMissing() throws NoSuchAlgorithmException {
		UserContext blank = new UserContext(" ", Map.of(), Map.of("organization", new ContextEntry("acme", Map.of())));

		assertThat(service.bucketFor("f", blank)).isEqualTo(md5Bucket("f", "acme"));
	}

	@Test
	void aCallerWithNoKeyAtAllSharesTheAnonymousBucket() throws NoSuchAlgorithmException {
		assertThat(service.bucketFor("f", null)).isEqualTo(md5Bucket("f", "anonymous"));
		assertThat(service.bucketFor("f", UserContext.anonymous(null))).isEqualTo(md5Bucket("f", "anonymous"));
		assertThat(service.bucketFor("f", contextsOnly(Map.of("organization", new ContextEntry(null, Map.of())))))
				.isEqualTo(md5Bucket("f", "anonymous"));
	}

	@Test
	void bucketsAreStableAcrossCallsServiceInstancesAndClockTimes() {
		for (int index = 0; index < 200; index++) {
			UserContext user = UserContext.anonymous("user-" + index);
			int expected = service.bucketFor("f", user);

			assertThat(service.bucketFor("f", user)).isEqualTo(expected);
			assertThat(new FlagEvaluationService(Clock.fixed(T0, ZoneOffset.UTC)).bucketFor("f", user)).isEqualTo(expected);
			assertThat(new FlagEvaluationService(Clock.fixed(T0.plus(Duration.ofDays(400)), ZoneOffset.UTC)).bucketFor("f", user))
					.isEqualTo(expected);
		}
	}

	@Test
	void aRolloutPercentageThatOnlyGrowsNeverRemovesAnIncludedUser() {
		Set<String> previouslyIncluded = new HashSet<>();

		for (int percentage = 0; percentage <= 100; percentage += 5) {
			Flag flag = flag("f");
			production(flag).getStrategies().add(percentageRule("s1", 0, percentage, "on"));
			Set<String> included = new HashSet<>();

			for (int index = 0; index < USERS; index++) {
				String userId = "user-" + index;

				if (service.evaluate(flag, PROD, UserContext.anonymous(userId)).reason() == EvaluationReason.ROLLOUT_ENABLED) {
					included.add(userId);
				}
			}

			assertThat(included).as("users included at " + percentage + "%").containsAll(previouslyIncluded);
			assertThat(included.size()).isBetween(percentage * USERS / 100 - 200, percentage * USERS / 100 + 200);
			previouslyIncluded = included;
		}

		assertThat(previouslyIncluded).hasSize(USERS);
	}

	@Test
	void aProgressiveRolloutNeverMovesAUserBackAsTimePasses() {
		Flag flag = flagWithTargetingRule("f");
		production(flag).getProgressiveRollouts().add(ruleRollout("r1", "targeting-rule-1", "off", "on", T0));
		Set<String> moved = new HashSet<>();

		for (long minutes : new long[] {0, 30, 60, 90, 120, 600}) {
			FlagEvaluationService later = new FlagEvaluationService(Clock.fixed(T0.plus(Duration.ofMinutes(minutes)), ZoneOffset.UTC));
			Set<String> movedNow = new HashSet<>();

			for (int index = 0; index < USERS; index++) {
				UserContext user = new UserContext("user-" + index, Map.of("plan", "pro"));

				if (later.evaluate(flag, PROD, user).variationKey().equals("on")) {
					movedNow.add(user.userId());
				}
			}

			assertThat(movedNow).as("after " + minutes + " minutes").containsAll(moved);
			moved = movedNow;
		}

		assertThat(moved).hasSize(USERS);
	}
}
