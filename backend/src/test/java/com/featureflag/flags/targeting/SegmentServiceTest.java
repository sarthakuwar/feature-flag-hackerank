package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.Variation;
import com.featureflag.flags.targeting.dto.ClauseRequest;
import com.featureflag.flags.targeting.dto.SegmentCreateRequest;
import com.featureflag.flags.targeting.dto.SegmentMembersRequest;
import com.featureflag.flags.targeting.dto.SegmentMembersResponse;
import com.featureflag.flags.targeting.dto.SegmentRuleRequest;
import com.featureflag.flags.targeting.dto.SegmentUpdateRequest;
import com.featureflag.flags.targeting.dto.SegmentUsageResponse;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.ValidationException;

@ExtendWith(MockitoExtension.class)
class SegmentServiceTest {

	private static final EnvironmentKey ENV = EnvironmentKey.PRODUCTION;

	@Mock
	private SegmentRepository segmentRepository;

	@Mock
	private FlagRepository flagRepository;

	@Mock
	private TargetingCacheService targetingCacheService;

	@Mock
	private FlagStreamService flagStreamService;

	private SegmentService segmentService;

	@BeforeEach
	void setUp() {
		segmentService = new SegmentService(segmentRepository, flagRepository, targetingCacheService, flagStreamService);
		lenient().when(segmentRepository.save(any(Segment.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	// ---- create

	@Test
	void createSavesRefreshesTheCacheAndBroadcastsToThatEnvironmentOnly() {
		Segment created = segmentService.create(ENV, new SegmentCreateRequest("beta-testers", "  Beta testers ", "Early adopters",
				null, List.of("u1", " u2 ", "u1"), List.of("u9"), null, "tester"));

		assertThat(created.getKey()).isEqualTo("beta-testers");
		assertThat(created.getName()).isEqualTo("Beta testers");
		assertThat(created.getEnvironment()).isEqualTo(ENV);
		assertThat(created.getContextKind()).as("defaults to user").isEqualTo("user");
		assertThat(created.getIncluded()).containsExactly("u1", "u2");
		assertThat(created.getExcluded()).containsExactly("u9");
		assertThat(created.getUpdatedBy()).isEqualTo("tester");

		verify(segmentRepository).save(created);
		verify(targetingCacheService).putSegment(created);
		verify(flagStreamService).publishSegmentChanged(ENV, "beta-testers", "CREATE");
	}

	@Test
	void createAssignsStableIdsToRulesAndKeepsSuppliedOnes() {
		Segment created = segmentService.create(ENV, new SegmentCreateRequest("acme-staff", "Acme staff", null, null, null, null,
				List.of(new SegmentRuleRequest(null, "acme email", List.of(emailClause())),
						new SegmentRuleRequest("keep-me", "other", List.of(emailClause()))),
				"tester"));

		assertThat(created.getRules()).hasSize(2);
		assertThat(created.getRules().get(0).getId()).isNotBlank();
		assertThat(created.getRules().get(1).getId()).isEqualTo("keep-me");
	}

	@Test
	void createRejectsBadKeysAndMissingFields() {
		assertThat(fieldErrors(() -> segmentService.create(ENV, request("Bad Key!", "Name")))).containsKey("key");
		assertThat(fieldErrors(() -> segmentService.create(ENV, request("", "Name")))).containsKey("key");
		assertThat(fieldErrors(() -> segmentService.create(ENV, request("ok-key", " ")))).containsKey("name");
		assertThat(fieldErrors(() -> segmentService.create(ENV, new SegmentCreateRequest("ok-key", "Name", null, null, null, null, null, null))))
				.containsKey("changedBy");
		assertThat(fieldErrors(() -> segmentService.create(ENV, new SegmentCreateRequest("ok-key", "Name", null, "BAD KIND", null, null, null, "t"))))
				.containsKey("contextKind");
		verify(segmentRepository, never()).save(any());
	}

	@Test
	void createRejectsAKeyInBothIncludedAndExcluded() {
		assertThat(fieldErrors(() -> segmentService.create(ENV, new SegmentCreateRequest("ok-key", "Name", null, null,
				List.of("u1"), List.of("u1"), null, "tester")))).containsKey("excluded");
	}

	@Test
	void createRejectsSegmentsReferencingOtherSegmentsInRules() {
		Map<String, List<String>> errors = fieldErrors(() -> segmentService.create(ENV, new SegmentCreateRequest("nested", "Nested", null, null,
				null, null, List.of(new SegmentRuleRequest(null, "nesting",
						List.of(new ClauseRequest("user", null, ClauseOperator.IN_SEGMENT, List.of("other"), false)))), "tester")));

		assertThat(errors).containsKey("rules[0].clauses[0].operator");
	}

	@Test
	void createRejectsRuleWithoutClausesAndBadClauseValues() {
		assertThat(fieldErrors(() -> segmentService.create(ENV, new SegmentCreateRequest("ok-key", "Name", null, null, null, null,
				List.of(new SegmentRuleRequest(null, "empty", List.of())), "tester")))).containsKey("rules[0].clauses");
		assertThat(fieldErrors(() -> segmentService.create(ENV, new SegmentCreateRequest("ok-key", "Name", null, null, null, null,
				List.of(new SegmentRuleRequest(null, "bad", List.of(new ClauseRequest("user", "age", ClauseOperator.GREATER_THAN, List.of("many"), false)))),
				"tester")))).containsKey("rules[0].clauses[0].values");
	}

	@Test
	void createRejectsMoreRulesThanTheCap() {
		List<SegmentRuleRequest> rules = new ArrayList<>();

		for (int index = 0; index <= TargetingLimits.MAX_SEGMENT_RULES; index++) {
			rules.add(new SegmentRuleRequest(null, "r" + index, List.of(emailClause())));
		}

		assertThat(fieldErrors(() -> segmentService.create(ENV, new SegmentCreateRequest("ok-key", "Name", null, null, null, null, rules, "t"))))
				.containsKey("rules");
	}

	@Test
	void createRejectsDuplicateKeysWithinTheEnvironmentAsAConflict() {
		when(segmentRepository.existsByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(true);

		assertThatThrownBy(() -> segmentService.create(ENV, request("beta-testers", "Beta testers")))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(409);
					assertThat(exception.getCode()).isEqualTo("SEGMENT_KEY_EXISTS");
				});
		verify(segmentRepository, never()).save(any());
	}

	// ---- read

	@Test
	void getOrThrowIsNotFoundForAnUnknownSegment() {
		when(segmentRepository.findByEnvironmentAndKey(ENV, "ghost")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> segmentService.getOrThrow(ENV, "ghost"))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("SEGMENT_NOT_FOUND"));
	}

	@Test
	void listSearchesKeyNameAndDescriptionCaseInsensitivelyAndSortsByName() {
		Segment beta = segment("beta-testers", "Beta testers", "Early adopters", "user");
		Segment enterprise = segment("enterprise", "Enterprise customers", "Paying accounts", "user");
		Segment orgs = segment("big-orgs", "Big orgs", null, "organization");
		when(segmentRepository.findByEnvironment(ENV)).thenReturn(List.of(orgs, enterprise, beta));

		assertThat(segmentService.list(ENV, null, null)).extracting(Segment::getKey).containsExactly("beta-testers", "big-orgs", "enterprise");
		assertThat(segmentService.list(ENV, "BETA", null)).extracting(Segment::getKey).containsExactly("beta-testers");
		assertThat(segmentService.list(ENV, "paying", null)).extracting(Segment::getKey).containsExactly("enterprise");
		assertThat(segmentService.list(ENV, "big-orgs", null)).extracting(Segment::getKey).containsExactly("big-orgs");
		assertThat(segmentService.list(ENV, "  ", "organization")).extracting(Segment::getKey).containsExactly("big-orgs");
		assertThat(segmentService.list(ENV, "zzz", null)).isEmpty();
	}

	// ---- update

	@Test
	void updateChangesNameDescriptionAndRulesButNeverKeyContextKindOrMembers() {
		Segment existing = segment("beta-testers", "Beta testers", "old", "user");
		existing.getIncluded().add("u1");
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(existing));

		Segment updated = segmentService.update(ENV, "beta-testers", new SegmentUpdateRequest("Beta program", "new",
				List.of(new SegmentRuleRequest(null, "acme", List.of(emailClause()))), "tester"));

		assertThat(updated.getName()).isEqualTo("Beta program");
		assertThat(updated.getDescription()).isEqualTo("new");
		assertThat(updated.getRules()).hasSize(1);
		assertThat(updated.getKey()).isEqualTo("beta-testers");
		assertThat(updated.getContextKind()).isEqualTo("user");
		assertThat(updated.getIncluded()).containsExactly("u1");
		verify(flagStreamService).publishSegmentChanged(ENV, "beta-testers", "UPDATE");
		verify(targetingCacheService).putSegment(updated);
	}

	@Test
	void updateWithNullRulesLeavesExistingRulesUntouched() {
		Segment existing = segment("beta-testers", "Beta testers", null, "user");
		SegmentRule rule = new SegmentRule();
		rule.setId("keep");
		existing.getRules().add(rule);
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(existing));

		Segment updated = segmentService.update(ENV, "beta-testers", new SegmentUpdateRequest("Renamed", null, null, "tester"));

		assertThat(updated.getRules()).containsExactly(rule);
	}

	@Test
	void updateWithAnEmptyRulesListClearsRules() {
		Segment existing = segment("beta-testers", "Beta testers", null, "user");
		existing.getRules().add(new SegmentRule());
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(existing));

		Segment updated = segmentService.update(ENV, "beta-testers", new SegmentUpdateRequest("Renamed", null, List.of(), "tester"));

		assertThat(updated.getRules()).isEmpty();
	}

	@Test
	void updateValidatesAndDoesNotPersistOnFailure() {
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(segment("beta-testers", "Beta", null, "user")));

		assertThat(fieldErrors(() -> segmentService.update(ENV, "beta-testers", new SegmentUpdateRequest(" ", null, null, "tester"))))
				.containsKey("name");
		verify(segmentRepository, never()).save(any());
	}

	// ---- bulk members

	@Test
	void bulkAddToIncludedReportsChangesAndClearsTheOppositeList() {
		Segment existing = segment("beta-testers", "Beta", null, "user");
		existing.getIncluded().add("u1");
		existing.getExcluded().add("u2");
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(existing));

		SegmentMembersResponse response = segmentService.changeMembers(ENV, "beta-testers",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.ADD, List.of("u1", "u2", "u3", " u3 ", ""), "tester"));

		assertThat(response.summary().requested()).isEqualTo(3);
		assertThat(response.summary().changed()).as("u1 was already included").isEqualTo(2);
		assertThat(response.summary().unchanged()).isEqualTo(1);
		assertThat(response.segment().included()).containsExactlyInAnyOrder("u1", "u2", "u3");
		assertThat(response.segment().excluded()).as("u2 moved out of excluded").isEmpty();
		verify(flagStreamService).publishSegmentChanged(ENV, "beta-testers", "UPDATE");
	}

	@Test
	void bulkAddToExcludedClearsTheIncludedList() {
		Segment existing = segment("beta-testers", "Beta", null, "user");
		existing.getIncluded().add("u1");
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(existing));

		SegmentMembersResponse response = segmentService.changeMembers(ENV, "beta-testers",
				new SegmentMembersRequest(MemberList.EXCLUDED, BulkOperation.ADD, List.of("u1"), "tester"));

		assertThat(response.segment().excluded()).containsExactly("u1");
		assertThat(response.segment().included()).isEmpty();
	}

	@Test
	void bulkRemoveOnlyTouchesTheNamedList() {
		Segment existing = segment("beta-testers", "Beta", null, "user");
		existing.getIncluded().addAll(Set.of("u1", "u2"));
		existing.getExcluded().add("u3");
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(existing));

		SegmentMembersResponse response = segmentService.changeMembers(ENV, "beta-testers",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.REMOVE, List.of("u1", "u3", "nobody"), "tester"));

		assertThat(response.summary().changed()).as("only u1 was in the included list").isEqualTo(1);
		assertThat(response.segment().included()).containsExactly("u2");
		assertThat(response.segment().excluded()).containsExactly("u3");
	}

	@Test
	void aBulkChangeThatChangesNothingSavesAndBroadcastsNothing() {
		Segment existing = segment("beta-testers", "Beta", null, "user");
		existing.getIncluded().add("u1");
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(existing));

		SegmentMembersResponse response = segmentService.changeMembers(ENV, "beta-testers",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.ADD, List.of("u1"), "tester"));

		assertThat(response.summary().changed()).isZero();
		verify(segmentRepository, never()).save(any());
		verify(flagStreamService, never()).publishSegmentChanged(any(), any(), any());
	}

	@Test
	void bulkMembersValidatesItsRequest() {
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(segment("beta-testers", "Beta", null, "user")));

		assertThat(fieldErrors(() -> segmentService.changeMembers(ENV, "beta-testers",
				new SegmentMembersRequest(null, BulkOperation.ADD, List.of("u1"), "tester")))).containsKey("list");
		assertThat(fieldErrors(() -> segmentService.changeMembers(ENV, "beta-testers",
				new SegmentMembersRequest(MemberList.INCLUDED, null, List.of("u1"), "tester")))).containsKey("operation");
		assertThat(fieldErrors(() -> segmentService.changeMembers(ENV, "beta-testers",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.ADD, List.of(), "tester")))).containsKey("keys");
		assertThat(fieldErrors(() -> segmentService.changeMembers(ENV, "beta-testers",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.ADD, List.of("u1"), null)))).containsKey("changedBy");
	}

	@Test
	void bulkMembersEnforcesTheMemberCap() {
		Segment existing = segment("beta-testers", "Beta", null, "user");

		for (int index = 0; index < TargetingLimits.MAX_SEGMENT_MEMBERS; index++) {
			existing.getIncluded().add("u" + index);
		}

		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(existing));

		assertThat(fieldErrors(() -> segmentService.changeMembers(ENV, "beta-testers",
				new SegmentMembersRequest(MemberList.EXCLUDED, BulkOperation.ADD, List.of("one-more"), "tester")))).containsKey("keys");
		verify(segmentRepository, never()).save(any());
	}

	// ---- delete and usage

	@Test
	void deleteRemovesTheSegmentEvictsItFromTheCacheAndBroadcasts() {
		Segment existing = segment("beta-testers", "Beta", null, "user");
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(existing));
		when(flagRepository.findAll()).thenReturn(List.of());

		segmentService.delete(ENV, "beta-testers", "tester");

		verify(segmentRepository).delete(existing);
		verify(targetingCacheService).evictSegment(ENV, "beta-testers");
		verify(flagStreamService).publishSegmentChanged(ENV, "beta-testers", "DELETE");
	}

	@Test
	void deleteIsBlockedWhileAnyFlagRuleReferencesTheSegmentEvenOnAnArchivedFlag() {
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(segment("beta-testers", "Beta", null, "user")));
		Flag archived = flagWithSegmentRule("legacy-flag", "beta-testers");
		archived.setLifecycle(FlagLifecycle.ARCHIVED);
		when(flagRepository.findAll()).thenReturn(List.of(archived, flagWithSegmentRule("live-flag", "other-segment")));

		assertThatThrownBy(() -> segmentService.delete(ENV, "beta-testers", "tester")).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.getStatusCode()).isEqualTo(409);
			assertThat(exception.getCode()).isEqualTo("SEGMENT_IN_USE");
			assertThat(exception.getDetails()).containsKey("usedBy");
		});
		verify(segmentRepository, never()).delete(any(Segment.class));
		verify(targetingCacheService, never()).evictSegment(any(), any());
	}

	@Test
	void usageListsEveryReferencingRuleInThisEnvironmentOnly() {
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(segment("beta-testers", "Beta", null, "user")));
		Flag usesIt = flagWithSegmentRule("uses-it", "beta-testers");
		Flag differentEnvironmentOnly = flagWithSegmentRule("staging-only", "beta-testers");
		differentEnvironmentOnly.getEnvironments().put(EnvironmentKey.STAGING, differentEnvironmentOnly.getEnvironments().remove(ENV));
		when(flagRepository.findAll()).thenReturn(List.of(usesIt, differentEnvironmentOnly, flagWithSegmentRule("no", "unrelated")));

		List<SegmentUsageResponse> usage = segmentService.usage(ENV, "beta-testers");

		assertThat(usage).hasSize(1);
		assertThat(usage.get(0).flagKey()).isEqualTo("uses-it");
		assertThat(usage.get(0).ruleId()).isEqualTo("rule-uses-it");
		assertThat(usage.get(0).lifecycle()).isEqualTo(FlagLifecycle.ACTIVE);
	}

	// ---- helpers

	private static ClauseRequest emailClause() {
		return new ClauseRequest("user", "email", ClauseOperator.ENDS_WITH, List.of("@acme.io"), false);
	}

	private static SegmentCreateRequest request(String key, String name) {
		return new SegmentCreateRequest(key, name, null, null, null, null, null, "tester");
	}

	private static Segment segment(String key, String name, String description, String contextKind) {
		Segment segment = new Segment();
		segment.setEnvironment(ENV);
		segment.setKey(key);
		segment.setName(name);
		segment.setDescription(description);
		segment.setContextKind(contextKind);
		segment.setIncluded(new LinkedHashSet<>());
		segment.setExcluded(new LinkedHashSet<>());

		return segment;
	}

	private static Flag flagWithSegmentRule(String flagKey, String segmentKey) {
		TargetingRule rule = new TargetingRule();
		rule.setId("rule-" + flagKey);
		rule.setDescription("uses " + segmentKey);
		rule.setServedVariationKey("on");
		rule.getClauses().add(new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of(segmentKey), false));

		EnvironmentConfig config = new EnvironmentConfig(true, "off", new ArrayList<>());
		config.getRules().add(rule);

		Flag flag = new Flag();
		flag.setKey(flagKey);
		flag.setName(flagKey);
		flag.setType(FlagType.BOOLEAN);
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setVariations(new ArrayList<>(List.of(new Variation("off", "Off", "false"), new Variation("on", "On", "true"))));
		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);
		environments.put(ENV, config);
		flag.setEnvironments(environments);

		return flag;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, List<String>> fieldErrors(org.junit.jupiter.api.function.Executable executable) {
		ValidationException thrown = org.junit.jupiter.api.Assertions.assertThrows(ValidationException.class, executable);

		return (Map<String, List<String>>) thrown.getDetails().get("fieldErrors");
	}
}
