package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.featureflag.flags.ContextEntry;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.UserContext;

class ClauseMatcherTest {

	private static final EnvironmentKey ENV = EnvironmentKey.PRODUCTION;

	private final Map<String, Segment> segments = new HashMap<>();

	private final Map<String, MobileApplication> applications = new HashMap<>();

	private final ClauseMatcher matcher = new ClauseMatcher(new TargetingLookup() {
		@Override
		public Optional<Segment> segment(EnvironmentKey environment, String segmentKey) {
			return environment == ENV ? Optional.ofNullable(segments.get(segmentKey)) : Optional.empty();
		}

		@Override
		public Optional<MobileApplication> application(String applicationKey) {
			return Optional.ofNullable(applications.get(applicationKey));
		}
	});

	// ---- attribute operators

	@Test
	void inMatchesAnyOfTheValuesExactly() {
		Clause clause = attribute("plan", ClauseOperator.IN, "pro", "enterprise");

		assertThat(matches(clause, user("u1", "plan", "pro"))).isTrue();
		assertThat(matches(clause, user("u1", "plan", "enterprise"))).isTrue();
		assertThat(matches(clause, user("u1", "plan", "Pro"))).as("case-sensitive").isFalse();
		assertThat(matches(clause, user("u1", "plan", "free"))).isFalse();
	}

	@Test
	void stringOperatorsAreSubstringAware() {
		UserContext context = user("u1", "email", "sam@acme.io");

		assertThat(matches(attribute("email", ClauseOperator.ENDS_WITH, "@acme.io"), context)).isTrue();
		assertThat(matches(attribute("email", ClauseOperator.STARTS_WITH, "sam@"), context)).isTrue();
		assertThat(matches(attribute("email", ClauseOperator.CONTAINS, "@acme"), context)).isTrue();
		assertThat(matches(attribute("email", ClauseOperator.ENDS_WITH, "@other.io"), context)).isFalse();
	}

	@Test
	void regexUsesFindSemanticsAndInvalidPatternsAreFalseNotErrors() {
		UserContext context = user("u1", "email", "sam@acme.io");

		assertThat(matches(attribute("email", ClauseOperator.MATCHES, "acme\\.(io|com)$"), context)).isTrue();
		assertThat(matches(attribute("email", ClauseOperator.MATCHES, "^bob"), context)).isFalse();
		assertThat(matches(attribute("email", ClauseOperator.MATCHES, "([unclosed"), context)).as("invalid regex").isFalse();
	}

	@Test
	void aCatastrophicRegexIsAbandonedWithinTheStepBudgetAndDoesNotMatch() {
		UserContext context = user("u1", "bio", "a".repeat(40) + "!");

		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			assertThat(matches(attribute("bio", ClauseOperator.MATCHES, "(a+)+$"), context)).isFalse();
			assertThat(matches(attribute("bio", ClauseOperator.MATCHES, "(a|aa)+$"), context)).isFalse();
			assertThat(matches(attribute("bio", ClauseOperator.MATCHES, "(x+x+)+y"), user("u1", "bio", "x".repeat(60)))).isFalse();
		});
	}

	@Test
	void anAbortedMatchIsIdenticalOnEveryRun() {
		Clause clause = attribute("bio", ClauseOperator.MATCHES, "(a|aa)+$");
		UserContext context = user("u1", "bio", "a".repeat(40) + "!");

		assertThat(matches(clause, context)).isEqualTo(matches(clause, context)).isFalse();
	}

	@Test
	void ordinaryRegexesStillMatchWithinTheBudget() {
		UserContext context = user("u1", "email", "sam.lee@acme.io");

		assertThat(matches(attribute("email", ClauseOperator.MATCHES, "^[a-z.]+@acme\\.(io|com)$"), context)).isTrue();
		assertThat(matches(attribute("bio", ClauseOperator.MATCHES, "^(a|b)*c$"), user("u1", "bio", "ab".repeat(200) + "c"))).isTrue();
	}

	@Test
	void aRegexThatOverflowsTheStackOnLongInputIsANonMatchNotAnError() {
		UserContext context = user("u1", "bio", "ab".repeat(2_000) + "c");

		assertThat(matches(attribute("bio", ClauseOperator.MATCHES, "^(a|b)*c$"), context)).isFalse();
	}

	@Test
	void theBudgetedSequenceThrowsOnceItsStepsAreSpent() {
		BudgetedCharSequence sequence = new BudgetedCharSequence("abc", 2);

		assertThat(sequence.charAt(0)).isEqualTo('a');
		assertThat(sequence.charAt(1)).isEqualTo('b');
		assertThat(sequence.length()).isEqualTo(3);
		assertThatThrownBy(() -> sequence.charAt(2)).isInstanceOf(BudgetedCharSequence.BudgetExceededException.class);
	}

	@Test
	void regexSkipsOversizedInputInsteadOfRiskingBacktracking() {
		String huge = "a".repeat(ClauseMatcher.MAX_REGEX_INPUT_LENGTH + 1);

		assertThat(matches(attribute("bio", ClauseOperator.MATCHES, "a+"), user("u1", "bio", huge))).isFalse();
	}

	@Test
	void numericOperatorsCompareAsNumbers() {
		UserContext context = user("u1", "age", "18");

		assertThat(matches(attribute("age", ClauseOperator.GREATER_THAN_OR_EQUAL, "18"), context)).isTrue();
		assertThat(matches(attribute("age", ClauseOperator.GREATER_THAN, "18"), context)).isFalse();
		assertThat(matches(attribute("age", ClauseOperator.LESS_THAN, "100"), context)).as("18 < 100, not '18' < '100' lexically").isTrue();
		assertThat(matches(attribute("age", ClauseOperator.LESS_THAN_OR_EQUAL, "9"), context)).as("18 <= 9 is false numerically").isFalse();
	}

	@Test
	void numericOperatorsAreFalseForNonNumbers() {
		assertThat(matches(attribute("age", ClauseOperator.GREATER_THAN, "5"), user("u1", "age", "old"))).isFalse();
		assertThat(matches(attribute("age", ClauseOperator.GREATER_THAN, "5"), user("u1", "age", "NaN"))).isFalse();
		assertThat(matches(attribute("age", ClauseOperator.GREATER_THAN, "5"), user("u1", "age", "Infinity"))).isFalse();
	}

	@Test
	void semverOperatorsCompareVersionsNotStrings() {
		UserContext context = user("u1", "appVersion", "2.10.0");

		assertThat(matches(attribute("appVersion", ClauseOperator.SEMVER_GREATER_THAN, "2.9.0"), context)).isTrue();
		assertThat(matches(attribute("appVersion", ClauseOperator.SEMVER_LESS_THAN, "3.0.0"), context)).isTrue();
		assertThat(matches(attribute("appVersion", ClauseOperator.SEMVER_EQUAL, "2.10"), context)).isTrue();
		assertThat(matches(attribute("appVersion", ClauseOperator.SEMVER_EQUAL, "2.9"), context)).isFalse();
		assertThat(matches(attribute("appVersion", ClauseOperator.SEMVER_GREATER_THAN, "not-a-version"), context)).isFalse();
	}

	@Test
	void theReservedKeyAttributeReadsTheContextKey() {
		Clause clause = attribute("key", ClauseOperator.STARTS_WITH, "internal-");

		assertThat(matches(clause, UserContext.anonymous("internal-42"))).isTrue();
		assertThat(matches(clause, UserContext.anonymous("customer-42"))).isFalse();
	}

	// ---- negate and missing data

	@Test
	void negateInvertsAMatchingAttribute() {
		Clause clause = new Clause("user", "plan", ClauseOperator.IN, List.of("free"), true);

		assertThat(matches(clause, user("u1", "plan", "pro"))).isTrue();
		assertThat(matches(clause, user("u1", "plan", "free"))).isFalse();
	}

	@Test
	void aMissingAttributeIsFalseEvenWhenNegated() {
		Clause plain = attribute("plan", ClauseOperator.IN, "free");
		Clause negated = new Clause("user", "plan", ClauseOperator.IN, List.of("free"), true);
		UserContext noPlan = UserContext.anonymous("u1");

		assertThat(matches(plain, noPlan)).isFalse();
		assertThat(matches(negated, noPlan)).as("'is not one of' must not match a user with no plan at all").isFalse();
	}

	@Test
	void aMissingContextKindIsFalseEvenWhenNegated() {
		Clause negated = new Clause("organization", "tier", ClauseOperator.IN, List.of("free"), true);

		assertThat(matches(negated, UserContext.anonymous("u1"))).isFalse();
	}

	@Test
	void anUnsetOperatorNeverMatches() {
		assertThat(matches(new Clause("user", "plan", null, List.of("pro"), false), user("u1", "plan", "pro"))).isFalse();
	}

	// ---- context kinds

	@Test
	void clausesInspectTheContextKindTheyNameNotTheUser() {
		UserContext context = new UserContext("u1", Map.of("tier", "free"),
				Map.of("organization", new ContextEntry("org-9", Map.of("tier", "enterprise"))));

		assertThat(matches(new Clause("organization", "tier", ClauseOperator.IN, List.of("enterprise"), false), context)).isTrue();
		assertThat(matches(new Clause("user", "tier", ClauseOperator.IN, List.of("enterprise"), false), context)).isFalse();
		assertThat(matches(new Clause("organization", "key", ClauseOperator.IN, List.of("org-9"), false), context)).isTrue();
	}

	@Test
	void allMatchRequiresEveryClauseAndNeverMatchesAnEmptyList() {
		UserContext context = user("u1", "plan", "pro");
		Clause pro = attribute("plan", ClauseOperator.IN, "pro");
		Clause us = attribute("country", ClauseOperator.IN, "US");

		assertThat(matcher.allMatch(List.of(pro), context, ENV)).isTrue();
		assertThat(matcher.allMatch(List.of(pro, us), context, ENV)).isFalse();
		assertThat(matcher.allMatch(List.of(), context, ENV)).isFalse();
		assertThat(matcher.allMatch(null, context, ENV)).isFalse();
	}

	// ---- segments

	@Test
	void segmentMembershipPrecedenceIsIncludedThenExcludedThenRules() {
		Segment beta = segment("beta", Set.of("vip"), Set.of("vip", "blocked", "rule-hit-but-excluded"),
				attribute("plan", ClauseOperator.IN, "pro"));

		assertThat(matcher.isMember(beta, user("vip", "plan", "free"), ENV)).as("included wins even if also excluded").isTrue();
		assertThat(matcher.isMember(beta, user("blocked", "plan", "pro"), ENV)).as("excluded beats a matching rule").isFalse();
		assertThat(matcher.isMember(beta, user("rule-hit-but-excluded", "plan", "pro"), ENV)).isFalse();
		assertThat(matcher.isMember(beta, user("someone", "plan", "pro"), ENV)).as("rule match").isTrue();
		assertThat(matcher.isMember(beta, user("someone", "plan", "free"), ENV)).isFalse();
	}

	@Test
	void anyOneSegmentRuleMatchingIsEnoughButAllItsClausesMustMatch() {
		Segment segment = new Segment();
		segment.setEnvironment(ENV);
		segment.setKey("mixed");
		segment.setContextKind("user");
		segment.getRules().add(rule(attribute("plan", ClauseOperator.IN, "pro"), attribute("country", ClauseOperator.IN, "US")));
		segment.getRules().add(rule(attribute("email", ClauseOperator.ENDS_WITH, "@acme.io")));

		assertThat(matcher.isMember(segment, new UserContext("a", Map.of("plan", "pro", "country", "US")), ENV)).isTrue();
		assertThat(matcher.isMember(segment, new UserContext("b", Map.of("plan", "pro", "country", "CA")), ENV)).as("first rule needs both clauses").isFalse();
		assertThat(matcher.isMember(segment, new UserContext("c", Map.of("email", "c@acme.io")), ENV)).as("second rule alone").isTrue();
	}

	@Test
	void anAnonymousContextCanOnlyBeAMemberThroughRules() {
		Segment segment = segment("anon", Set.of("someone"), Set.of(), attribute("plan", ClauseOperator.IN, "pro"));

		assertThat(matcher.isMember(segment, UserContext.anonymous(null), ENV)).isFalse();
		assertThat(matcher.isMember(segment, new UserContext(null, Map.of("plan", "pro")), ENV)).isTrue();
	}

	@Test
	void inSegmentClauseMatchesMembershipOfAnyListedSegmentAndNegateGivesNotIn() {
		segments.put("beta", segment("beta", Set.of("u1"), Set.of()));
		segments.put("staff", segment("staff", Set.of("u2"), Set.of()));
		Clause isIn = new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("beta", "staff"), false);
		Clause notIn = new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("beta", "staff"), true);

		assertThat(matches(isIn, UserContext.anonymous("u1"))).isTrue();
		assertThat(matches(isIn, UserContext.anonymous("u2"))).isTrue();
		assertThat(matches(isIn, UserContext.anonymous("u3"))).isFalse();
		assertThat(matches(notIn, UserContext.anonymous("u3"))).isTrue();
		assertThat(matches(notIn, UserContext.anonymous("u1"))).isFalse();
	}

	@Test
	void anUnknownSegmentJustDoesntMatch() {
		Clause isIn = new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("ghost"), false);

		assertThat(matches(isIn, UserContext.anonymous("u1"))).isFalse();
	}

	@Test
	void aSegmentOfADifferentContextKindIsNotConsultedForThisClause() {
		Segment orgSegment = segment("orgs", Set.of("u1"), Set.of());
		orgSegment.setContextKind("organization");
		segments.put("orgs", orgSegment);

		assertThat(matches(new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("orgs"), false), UserContext.anonymous("u1"))).isFalse();
	}

	@Test
	void organizationSegmentsMatchAgainstTheOrganizationContext() {
		Segment orgSegment = segment("orgs", Set.of("org-1"), Set.of());
		orgSegment.setContextKind("organization");
		segments.put("orgs", orgSegment);
		UserContext context = new UserContext("u1", Map.of(), Map.of("organization", new ContextEntry("org-1", Map.of())));

		assertThat(matches(new Clause("organization", null, ClauseOperator.IN_SEGMENT, List.of("orgs"), false), context)).isTrue();
	}

	@Test
	void segmentClausesInsideASegmentRuleAreIgnoredSoNestingCannotRecurse() {
		segments.put("loop", segment("loop", Set.of(), Set.of(),
				new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("loop"), false)));

		assertThat(matches(new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("loop"), false), UserContext.anonymous("u1")))
				.isFalse();
	}

	// ---- mobile: app version support

	@Test
	void appVersionSupportedComparesAgainstTheRegisteredMinimum() {
		applications.put("com.acme.app", application("com.acme.app", "2.4.0"));
		Clause supported = new Clause("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, List.of(), false);
		Clause unsupported = new Clause("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, List.of(), true);

		assertThat(matches(supported, appContext("com.acme.app", "versionName", "2.4.0"))).isTrue();
		assertThat(matches(supported, appContext("com.acme.app", "versionName", "2.10.1"))).isTrue();
		assertThat(matches(supported, appContext("com.acme.app", "versionName", "2.3.9"))).isFalse();
		assertThat(matches(unsupported, appContext("com.acme.app", "versionName", "2.3.9"))).isTrue();
		assertThat(matches(unsupported, appContext("com.acme.app", "versionName", "2.4.0"))).isFalse();
	}

	@Test
	void appVersionFallsBackToTheVersionAttributeWhenVersionNameIsAbsent() {
		applications.put("com.acme.app", application("com.acme.app", "2.4.0"));
		Clause supported = new Clause("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, List.of(), false);

		assertThat(matches(supported, appContext("com.acme.app", "version", "3.0.0"))).isTrue();
	}

	@Test
	void appVersionSupportIsUndecidableForUnknownAppsOrUnparseableVersionsAndNegateDoesNotFlipThat() {
		applications.put("com.acme.app", application("com.acme.app", "2.4.0"));
		Clause supported = new Clause("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, List.of(), false);
		Clause unsupported = new Clause("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, List.of(), true);
		UserContext unknownApp = appContext("com.other.app", "versionName", "1.0.0");
		UserContext garbageVersion = appContext("com.acme.app", "versionName", "banana");
		UserContext noVersion = new UserContext("u1", Map.of(), Map.of("ld_application", new ContextEntry("com.acme.app", Map.of())));

		for (UserContext context : List.of(unknownApp, garbageVersion, noVersion)) {
			assertThat(matches(supported, context)).isFalse();
			assertThat(matches(unsupported, context)).as("unknown must not read as 'unsupported'").isFalse();
		}
	}

	@Test
	void mobileClausesCanCombineApplicationAndDeviceAttributes() {
		UserContext context = new UserContext("u1", Map.of(), Map.of(
				"ld_application", new ContextEntry("com.acme.app", Map.of("versionName", "3.1.0")),
				"ld_device", new ContextEntry("dev-1", Map.of("os.name", "Android", "manufacturer", "Google"))));

		assertThat(matcher.allMatch(List.of(
				new Clause("ld_application", "versionName", ClauseOperator.SEMVER_GREATER_THAN, List.of("3.0.0"), false),
				new Clause("ld_device", "os.name", ClauseOperator.IN, List.of("Android"), false)), context, ENV)).isTrue();
		assertThat(matcher.allMatch(List.of(
				new Clause("ld_application", "versionName", ClauseOperator.SEMVER_GREATER_THAN, List.of("3.0.0"), false),
				new Clause("ld_device", "os.name", ClauseOperator.IN, List.of("iOS"), false)), context, ENV)).isFalse();
	}

	@Test
	void segmentsAreEnvironmentScoped() {
		segments.put("beta", segment("beta", Set.of("u1"), Set.of()));
		Clause isIn = new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("beta"), false);

		assertThat(matcher.matches(isIn, UserContext.anonymous("u1"), ENV)).isTrue();
		assertThat(matcher.matches(isIn, UserContext.anonymous("u1"), EnvironmentKey.STAGING)).isFalse();
	}

	// ---- helpers

	private boolean matches(Clause clause, UserContext context) {
		return matcher.matches(clause, context, ENV);
	}

	private static UserContext user(String userId, String attribute, String value) {
		return new UserContext(userId, Map.of(attribute, value));
	}

	private static UserContext appContext(String appKey, String attribute, String value) {
		return new UserContext("u1", Map.of(), Map.of("ld_application", new ContextEntry(appKey, Map.of(attribute, value))));
	}

	private static Clause attribute(String attribute, ClauseOperator operator, String... values) {
		return new Clause("user", attribute, operator, List.of(values), false);
	}

	private static SegmentRule rule(Clause... clauses) {
		SegmentRule rule = new SegmentRule();
		rule.setId("r-" + System.nanoTime());
		rule.setClauses(List.of(clauses));

		return rule;
	}

	private static Segment segment(String key, Set<String> included, Set<String> excluded, Clause... ruleClauses) {
		Segment segment = new Segment();
		segment.setEnvironment(ENV);
		segment.setKey(key);
		segment.setName(key);
		segment.setContextKind("user");
		segment.setIncluded(new LinkedHashSet<>(included));
		segment.setExcluded(new LinkedHashSet<>(excluded));

		if (ruleClauses.length > 0) {
			segment.getRules().add(rule(ruleClauses));
		}

		return segment;
	}

	private static MobileApplication application(String key, String minSupportedVersion) {
		MobileApplication application = new MobileApplication();
		application.setKey(key);
		application.setName(key);
		application.setMinSupportedVersion(minSupportedVersion);

		return application;
	}
}
