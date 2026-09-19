package com.featureflag.flags.targeting;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.featureflag.flags.ContextEntry;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.UserContext;

/**
 * Pure clause/segment matching against an in-memory {@link TargetingLookup}: no I/O, no side effects.
 *
 * <p>Semantics worth knowing: a clause whose context kind isn't in the evaluation, or whose attribute
 * is absent, is false and is <em>not</em> flipped by {@code negate}; an unparseable number, version or
 * regex is likewise false rather than an error, so one bad value can never break evaluation of the
 * whole flag. Regex matching skips attribute values over {@link #MAX_REGEX_INPUT_LENGTH} characters and
 * gives every match a budget of {@link #MAX_REGEX_STEPS} character reads; a match that spends it is
 * abandoned as a non-match. Counting reads instead of watching a clock keeps the result deterministic.
 */
public final class ClauseMatcher {

	static final int MAX_REGEX_INPUT_LENGTH = 4_096;

	static final long MAX_REGEX_STEPS = 200_000;

	private static final Logger LOG = LoggerFactory.getLogger(ClauseMatcher.class);

	/** Counts aborted matches so the warning can be rate limited without reading a clock. */
	private static final AtomicLong ABORTED_MATCHES = new AtomicLong();

	private static final int MAX_CACHED_PATTERNS = 1_024;

	private final TargetingLookup lookup;

	private final ConcurrentHashMap<String, Optional<Pattern>> compiledPatterns = new ConcurrentHashMap<>();

	public ClauseMatcher(TargetingLookup lookup) {
		this.lookup = lookup;
	}

	/** All clauses must match; an empty clause list never matches (a rule must say something). */
	public boolean allMatch(List<Clause> clauses, UserContext context, EnvironmentKey environment) {
		return allMatch(clauses, context, environment, true);
	}

	public boolean matches(Clause clause, UserContext context, EnvironmentKey environment) {
		return matches(clause, context, environment, true);
	}

	/**
	 * Membership precedence: explicitly included, then explicitly excluded, then the segment's rules
	 * (any rule whose clauses all match). A context with no key can only ever match by rule.
	 */
	public boolean isMember(Segment segment, UserContext context, EnvironmentKey environment) {
		ContextEntry entry = context.contextOfKind(segment.getContextKind());

		if (entry == null) {
			return false;
		}

		String key = entry.key();

		if (key != null && segment.getIncluded().contains(key)) {
			return true;
		}

		if (key != null && segment.getExcluded().contains(key)) {
			return false;
		}

		for (SegmentRule rule : segment.getRules()) {
			if (allMatch(rule.getClauses(), context, environment, false)) {
				return true;
			}
		}

		return false;
	}

	private boolean allMatch(List<Clause> clauses, UserContext context, EnvironmentKey environment,
			boolean allowSegmentClauses) {
		if (clauses == null || clauses.isEmpty()) {
			return false;
		}

		for (Clause clause : clauses) {
			if (!matches(clause, context, environment, allowSegmentClauses)) {
				return false;
			}
		}

		return true;
	}

	private boolean matches(Clause clause, UserContext context, EnvironmentKey environment, boolean allowSegmentClauses) {
		if (clause.getOperator() == null) {
			return false;
		}

		ContextEntry entry = context.contextOfKind(clause.getContextKind());

		if (entry == null) {
			return false;
		}

		Boolean result = switch (clause.getOperator()) {
			case IN_SEGMENT -> allowSegmentClauses ? inAnySegment(clause, context, environment) : Boolean.FALSE;
			case APP_VERSION_SUPPORTED -> appVersionSupported(entry);
			default -> matchesAttribute(clause, entry);
		};

		if (result == null) {
			return false;
		}

		return clause.isNegate() != result;
	}

	private Boolean inAnySegment(Clause clause, UserContext context, EnvironmentKey environment) {
		for (String segmentKey : clause.getValues()) {
			Optional<Segment> segment = lookup.segment(environment, segmentKey);

			if (segment.isPresent() && kindOf(segment.get().getContextKind()).equals(kindOf(clause.getContextKind()))
					&& isMember(segment.get(), context, environment)) {
				return Boolean.TRUE;
			}
		}

		return Boolean.FALSE;
	}

	private String kindOf(String contextKind) {
		return contextKind == null ? UserContext.USER_KIND : contextKind;
	}

	/** null (not false) when it can't be decided, so a negated clause doesn't turn "unknown" into a match. */
	private Boolean appVersionSupported(ContextEntry entry) {
		Optional<MobileApplication> application = entry.key() == null ? Optional.empty() : lookup.application(entry.key());
		String rawVersion = entry.attribute("versionName") != null ? entry.attribute("versionName") : entry.attribute("version");
		Optional<SemanticVersion> version = SemanticVersion.parse(rawVersion);
		Optional<SemanticVersion> minimum = application.flatMap(app -> SemanticVersion.parse(app.getMinSupportedVersion()));

		if (version.isEmpty() || minimum.isEmpty()) {
			return null;
		}

		return version.get().compareTo(minimum.get()) >= 0;
	}

	private Boolean matchesAttribute(Clause clause, ContextEntry entry) {
		String actual = entry.attribute(clause.getAttribute());

		if (actual == null) {
			return null;
		}

		for (String expected : clause.getValues()) {
			if (attributeMatches(clause.getOperator(), actual, expected)) {
				return Boolean.TRUE;
			}
		}

		return Boolean.FALSE;
	}

	private boolean attributeMatches(ClauseOperator operator, String actual, String expected) {
		if (expected == null) {
			return false;
		}

		return switch (operator) {
			case IN -> actual.equals(expected);
			case STARTS_WITH -> actual.startsWith(expected);
			case ENDS_WITH -> actual.endsWith(expected);
			case CONTAINS -> actual.contains(expected);
			case MATCHES -> matchesRegex(actual, expected);
			case LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL -> compareNumbers(operator, actual, expected);
			case SEMVER_EQUAL, SEMVER_LESS_THAN, SEMVER_GREATER_THAN -> compareVersions(operator, actual, expected);
			case IN_SEGMENT, APP_VERSION_SUPPORTED -> false;
		};
	}

	private boolean matchesRegex(String actual, String pattern) {
		if (actual.length() > MAX_REGEX_INPUT_LENGTH) {
			return false;
		}

		return compiled(pattern).map(compiledPattern -> findWithinBudget(compiledPattern, actual)).orElse(false);
	}

	private boolean findWithinBudget(Pattern pattern, String actual) {
		try {
			return pattern.matcher(new BudgetedCharSequence(actual, MAX_REGEX_STEPS)).find();
		} catch (BudgetedCharSequence.BudgetExceededException | StackOverflowError exception) {
			// The regex engine recurses per repetition, so deep input overflows the stack like a spent budget.
			logAbortedMatch();

			return false;
		}
	}

	/** Logs the first aborted match and every thousandth after it, never the pattern or the attribute value. */
	private void logAbortedMatch() {
		long occurrence = ABORTED_MATCHES.incrementAndGet();

		if (occurrence == 1 || occurrence % 1_000 == 0) {
			LOG.warn("A MATCHES clause exceeded its regex step budget of {} and was treated as no match (occurrence {}).",
					MAX_REGEX_STEPS, occurrence);
		}
	}

	private Optional<Pattern> compiled(String pattern) {
		if (compiledPatterns.size() >= MAX_CACHED_PATTERNS) {
			compiledPatterns.clear();
		}

		return compiledPatterns.computeIfAbsent(pattern, source -> {
			try {
				return Optional.of(Pattern.compile(source));
			} catch (PatternSyntaxException exception) {
				return Optional.empty();
			}
		});
	}

	private boolean compareNumbers(ClauseOperator operator, String actual, String expected) {
		double left;
		double right;

		try {
			left = Double.parseDouble(actual.trim());
			right = Double.parseDouble(expected.trim());
		} catch (NumberFormatException exception) {
			return false;
		}

		if (!Double.isFinite(left) || !Double.isFinite(right)) {
			return false;
		}

		return switch (operator) {
			case LESS_THAN -> left < right;
			case LESS_THAN_OR_EQUAL -> left <= right;
			case GREATER_THAN -> left > right;
			default -> left >= right;
		};
	}

	private boolean compareVersions(ClauseOperator operator, String actual, String expected) {
		Optional<SemanticVersion> left = SemanticVersion.parse(actual);
		Optional<SemanticVersion> right = SemanticVersion.parse(expected);

		if (left.isEmpty() || right.isEmpty()) {
			return false;
		}

		int comparison = left.get().compareTo(right.get());

		return switch (operator) {
			case SEMVER_EQUAL -> comparison == 0;
			case SEMVER_LESS_THAN -> comparison < 0;
			default -> comparison > 0;
		};
	}
}
