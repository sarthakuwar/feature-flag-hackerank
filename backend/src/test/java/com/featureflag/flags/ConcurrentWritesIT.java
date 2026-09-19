package com.featureflag.flags;

import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.TARGETING_RULE_ID;
import static com.featureflag.flags.rollout.RolloutFixtures.flagWithTargetingRule;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static com.featureflag.flags.rollout.RolloutFixtures.ruleRollout;
import static com.featureflag.flags.rollout.RolloutFixtures.targetingRuleOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditEvent;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.FlagUpdateRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.rollout.ProgressiveRolloutService;
import com.featureflag.flags.targeting.MobileApplication;
import com.featureflag.flags.targeting.MobileApplicationRepository;
import com.featureflag.flags.targeting.MobileApplicationService;
import com.featureflag.flags.targeting.Segment;
import com.featureflag.flags.targeting.SegmentRepository;
import com.featureflag.flags.targeting.SegmentService;
import com.featureflag.flags.targeting.dto.MobileApplicationRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationUpdateRequest;
import com.featureflag.flags.targeting.dto.SegmentCreateRequest;
import com.featureflag.flags.targeting.dto.SegmentUpdateRequest;
import com.featureflag.shared.MutableClock;

/** Optimistic locking and concurrent writers, against real MongoDB. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrentWritesIT {

	private static final String FLAG = "race-flag";

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(T0);
		}
	}

	@Autowired
	private MutableClock clock;

	@Autowired
	private MongoTemplate mongoTemplate;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private FlagService flagService;

	@Autowired
	private FlagCacheService flagCacheService;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private SegmentRepository segmentRepository;

	@Autowired
	private SegmentService segmentService;

	@Autowired
	private MobileApplicationRepository applicationRepository;

	@Autowired
	private MobileApplicationService applicationService;

	@Autowired
	private ProgressiveRolloutService rolloutService;

	@BeforeEach
	@AfterEach
	void reset() {
		clock.set(T0);
		flagRepository.deleteAll();
		segmentRepository.deleteAll();
		applicationRepository.deleteAll();
		auditRepository.deleteAll();
		flagCacheService.load();
	}

	// ---- versions ----

	@Test
	void aNewDocumentStartsAtVersionZeroAndEachSaveCountsUp() {
		Flag flag = flagService.create(createRequest(FLAG));

		assertThat(flag.getVersion()).isZero();
		assertThat(flagService.update(FLAG, new FlagUpdateRequest("Renamed", "d", "owner", List.of(), "tester")).getVersion()).isEqualTo(1L);
		assertThat(flagRepository.findByKey(FLAG).orElseThrow().getVersion()).isEqualTo(1L);
		assertThat(mongoTemplate.getCollection("flags").find().first()).doesNotContainKey("new");
	}

	@Test
	void theSecondOfTwoWritersWorkingFromTheSameReadIsRefusedInsteadOfOverwritingTheFirst() {
		flagService.create(createRequest(FLAG));
		Flag first = flagRepository.findByKey(FLAG).orElseThrow();
		Flag second = flagRepository.findByKey(FLAG).orElseThrow();
		first.setName("First writer");
		second.setName("Second writer");

		flagRepository.save(first);

		assertThatThrownBy(() -> flagRepository.save(second)).isInstanceOf(OptimisticLockingFailureException.class);
		assertThat(flagRepository.findByKey(FLAG).orElseThrow().getName()).isEqualTo("First writer");
	}

	@Test
	void aFlagStoredBeforeVersioningExistedCanStillBeUpdatedThroughTheService() {
		flagService.create(createRequest(FLAG));
		removeVersionField("flags", "key", FLAG);
		assertThat(mongoTemplate.getCollection("flags").find().first()).doesNotContainKey("version");
		flagCacheService.load();

		Flag updated = flagService.update(FLAG, new FlagUpdateRequest("Legacy renamed", "d", "owner", List.of(), "tester"));

		assertThat(updated.getName()).isEqualTo("Legacy renamed");
		assertThat(flagRepository.count()).isEqualTo(1);
		assertThat(flagRepository.findByKey(FLAG).orElseThrow().getVersion()).isZero();

		flagService.update(FLAG, new FlagUpdateRequest("Legacy renamed twice", "d", "owner", List.of(), "tester"));

		assertThat(flagRepository.findByKey(FLAG).orElseThrow().getVersion()).isEqualTo(1L);
	}

	@Test
	void aSegmentAndAnApplicationStoredBeforeVersioningExistedCanStillBeUpdatedThroughTheirServices() {
		segmentService.create(EnvironmentKey.PRODUCTION, new SegmentCreateRequest("beta-users", "Beta users", null, null, List.of("u1"),
				List.of(), List.of(), "tester"));
		applicationService.create(new MobileApplicationRequest("shop-app", "Shop", "1.0.0", "tester"));
		removeVersionField("segments", "key", "beta-users");
		removeVersionField("mobile_applications", "key", "shop-app");

		Segment segment = segmentService.update(EnvironmentKey.PRODUCTION, "beta-users",
				new SegmentUpdateRequest("Beta users renamed", null, List.of(), "tester"));
		MobileApplication application = applicationService.update("shop-app",
				new MobileApplicationUpdateRequest("Shop renamed", "2.0.0", "tester"));

		assertThat(segment.getName()).isEqualTo("Beta users renamed");
		assertThat(application.getName()).isEqualTo("Shop renamed");
		assertThat(segmentRepository.count()).isEqualTo(1);
		assertThat(applicationRepository.count()).isEqualTo(1);
	}

	@Test
	void staleSegmentAndApplicationWritesAreRefusedToo() {
		segmentService.create(EnvironmentKey.PRODUCTION, new SegmentCreateRequest("beta-users", "Beta users", null, null, List.of(),
				List.of(), List.of(), "tester"));
		applicationService.create(new MobileApplicationRequest("shop-app", "Shop", "1.0.0", "tester"));
		Segment staleSegment = segmentRepository.findByEnvironmentAndKey(EnvironmentKey.PRODUCTION, "beta-users").orElseThrow();
		MobileApplication staleApplication = applicationRepository.findByKey("shop-app").orElseThrow();

		segmentService.update(EnvironmentKey.PRODUCTION, "beta-users", new SegmentUpdateRequest("Changed", null, List.of(), "tester"));
		applicationService.update("shop-app", new MobileApplicationUpdateRequest("Changed", "1.0.0", "tester"));

		assertThatThrownBy(() -> segmentRepository.save(staleSegment)).isInstanceOf(OptimisticLockingFailureException.class);
		assertThatThrownBy(() -> applicationRepository.save(staleApplication)).isInstanceOf(OptimisticLockingFailureException.class);
	}

	// ---- two writers racing ----

	@Test
	void twoConcurrentCreatesOfTheSameKeyBothSucceedAndTheFlagIsCreatedAndAuditedOnce() throws Exception {
		List<Flag> results = runTogether(() -> flagService.create(createRequest(FLAG)), () -> flagService.create(createRequest(FLAG)));

		assertThat(results).hasSize(2).allSatisfy(flag -> assertThat(flag.getKey()).isEqualTo(FLAG));
		assertThat(flagRepository.count()).isEqualTo(1);
		assertThat(auditHistory(FLAG)).extracting(AuditEvent::getAction)
				.containsExactly(AuditAction.CREATE);
	}

	@Test
	void twoCompetingCompletionsOfTheSameRolloutCompleteItExactlyOnce() throws Exception {
		Flag flag = flagWithTargetingRule(FLAG);
		production(flag).getProgressiveRollouts().add(ruleRollout("r1", TARGETING_RULE_ID, "off", "on", T0));
		flagRepository.save(flag);
		flagCacheService.load();
		clock.set(T0.plus(Duration.ofMinutes(120)));

		List<Integer> completed = runTogether(rolloutService::completeDueRollouts, rolloutService::completeDueRollouts);

		assertThat(completed.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);

		Flag stored = flagRepository.findByKey(FLAG).orElseThrow();
		assertThat(production(stored).getProgressiveRollouts()).singleElement()
				.satisfies(rollout -> assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.COMPLETED));
		assertThat(targetingRuleOf(stored, TARGETING_RULE_ID).getServedVariationKey()).isEqualTo("on");
		assertThat(auditHistory(FLAG)).extracting(AuditEvent::getAction)
				.containsExactly(AuditAction.ROLLOUT_COMPLETE);
	}

	@Test
	void aCompletionRacingAHumanEditKeepsTheEdit() throws Exception {
		Flag flag = flagWithTargetingRule(FLAG);
		production(flag).getProgressiveRollouts().add(ruleRollout("r1", TARGETING_RULE_ID, "off", "on", T0));
		flagRepository.save(flag);
		flagCacheService.load();
		clock.set(T0.plus(Duration.ofMinutes(120)));

		List<Object> outcomes = runTogether(() -> rolloutService.completeDueRollouts(), () -> {
			try {
				return flagService.update(FLAG, new FlagUpdateRequest("Edited during completion", "d", "owner", List.of(), "tester"));
			} catch (OptimisticLockingFailureException exception) {
				return exception;
			}
		});

		Flag stored = flagRepository.findByKey(FLAG).orElseThrow();
		boolean editAccepted = outcomes.get(1) instanceof Flag;

		assertThat(production(stored).getProgressiveRollouts()).singleElement()
				.satisfies(rollout -> assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.COMPLETED));
		assertThat(stored.getName().equals("Edited during completion")).as("an accepted edit is never overwritten, a refused one never applied")
				.isEqualTo(editAccepted);
		assertThat(auditHistory(FLAG)).extracting(AuditEvent::getAction)
				.contains(AuditAction.ROLLOUT_COMPLETE);
	}

	private <T> List<T> runTogether(Callable<? extends T> first, Callable<? extends T> second) throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<T> one = executor.submit(() -> {
				barrier.await();

				return first.call();
			});
			Future<T> two = executor.submit(() -> {
				barrier.await();

				return second.call();
			});

			return List.of(one.get(), two.get());
		} finally {
			executor.shutdownNow();
		}
	}

	private void removeVersionField(String collection, String keyField, String key) {
		mongoTemplate.getCollection(collection).updateOne(new Document(keyField, key),
				new Document("$unset", new Document("version", 1)));
	}

	private FlagCreateRequest createRequest(String key) {
		return new FlagCreateRequest(key, "Race Flag", "d", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(), "tester");
	}

	private List<AuditEvent> auditHistory(String flagKey) {
		return auditRepository.findByFlagKey(flagKey, PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "changedAt"))).getContent();
	}
}
