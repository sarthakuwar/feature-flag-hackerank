package com.featureflag.flags.targeting;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lenient semantic version, the way mobile app versions actually show up: an optional leading "v",
 * one to three numeric parts (missing minor/patch count as 0), an optional "-prerelease" and an
 * ignored "+build". Ordering follows semver 2.0: a prerelease sorts below its release, numeric
 * prerelease identifiers compare numerically and sort below alphanumeric ones.
 */
public record SemanticVersion(long major, long minor, long patch, List<String> prerelease)
		implements Comparable<SemanticVersion> {

	private static final int MAX_LENGTH = 64;

	private static final Pattern PATTERN = Pattern
			.compile("^v?(\\d{1,18})(?:\\.(\\d{1,18}))?(?:\\.(\\d{1,18}))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

	public static Optional<SemanticVersion> parse(String raw) {
		if (raw == null || raw.isBlank() || raw.length() > MAX_LENGTH) {
			return Optional.empty();
		}

		Matcher matcher = PATTERN.matcher(raw.trim());

		if (!matcher.matches()) {
			return Optional.empty();
		}

		long major = Long.parseLong(matcher.group(1));
		long minor = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
		long patch = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
		List<String> prerelease = matcher.group(4) == null ? List.of() : Arrays.asList(matcher.group(4).split("\\."));

		return Optional.of(new SemanticVersion(major, minor, patch, prerelease));
	}

	@Override
	public int compareTo(SemanticVersion other) {
		int byMajor = Long.compare(major, other.major);

		if (byMajor != 0) {
			return byMajor;
		}

		int byMinor = Long.compare(minor, other.minor);

		if (byMinor != 0) {
			return byMinor;
		}

		int byPatch = Long.compare(patch, other.patch);

		if (byPatch != 0) {
			return byPatch;
		}

		return comparePrerelease(prerelease, other.prerelease);
	}

	private static int comparePrerelease(List<String> left, List<String> right) {
		if (left.isEmpty() || right.isEmpty()) {
			return Boolean.compare(left.isEmpty(), right.isEmpty());
		}

		int shared = Math.min(left.size(), right.size());

		for (int index = 0; index < shared; index++) {
			int result = compareIdentifier(left.get(index), right.get(index));

			if (result != 0) {
				return result;
			}
		}

		return Integer.compare(left.size(), right.size());
	}

	private static int compareIdentifier(String left, String right) {
		boolean leftNumeric = left.chars().allMatch(Character::isDigit);
		boolean rightNumeric = right.chars().allMatch(Character::isDigit);

		if (leftNumeric && rightNumeric) {
			return compareDigitStrings(left, right);
		}

		if (leftNumeric != rightNumeric) {
			return leftNumeric ? -1 : 1;
		}

		return left.compareTo(right);
	}

	/** Numeric identifiers can exceed a long, so compare by length after stripping leading zeros. */
	private static int compareDigitStrings(String left, String right) {
		String a = left.replaceFirst("^0+(?=.)", "");
		String b = right.replaceFirst("^0+(?=.)", "");

		return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
	}
}
