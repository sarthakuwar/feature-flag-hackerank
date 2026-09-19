package com.featureflag.flags.targeting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Write-time heuristics that reject regular expressions which are almost certainly catastrophic
 * (exponential backtracking): a group that ends in an unbounded repetition and is itself repeated
 * without bound, such as {@code (a+)+} or {@code (.*)*}, and a repeated alternation whose
 * branches overlap, such as {@code (a|aa)+}. The evaluation step budget in {@link ClauseMatcher}
 * remains the real safety net; this only gives the author an early, clear error.
 */
final class RegexSafety {

	static final String NESTED_QUANTIFIER = "repeats a group that already ends in a repetition";

	static final String OVERLAPPING_ALTERNATION = "repeats an alternation whose branches overlap";

	private RegexSafety() {
	}

	/** A reason the pattern is rejected, or empty when no known catastrophic shape is found. Assumes the pattern compiles. */
	static Optional<String> catastrophicReason(String pattern) {
		return new Scan(pattern).run();
	}

	private static final class Frame {

		private final boolean atomic;

		private final List<int[]> branches = new ArrayList<>();

		private int branchStart;

		private boolean lastUnbounded;

		private boolean anyBranchEndsUnbounded;

		Frame(boolean atomic, int branchStart) {
			this.atomic = atomic;
			this.branchStart = branchStart;
		}
	}

	private record Quantifier(int end, boolean present, boolean unbounded) {
	}

	private static final class Scan {

		private final String pattern;

		private final List<Frame> stack = new ArrayList<>();

		private int index;

		Scan(String pattern) {
			this.pattern = pattern;
			stack.add(new Frame(false, 0));
		}

		Optional<String> run() {
			while (index < pattern.length()) {
				switch (pattern.charAt(index)) {
					case '\\' -> {
						index = Math.min(index + 2, pattern.length());
						atomFollowedByQuantifier();
					}
					case '[' -> {
						index = endOfCharacterClass(index);
						atomFollowedByQuantifier();
					}
					case '(' -> openGroup();
					case '|' -> {
						closeBranch(index);
						index++;
						top().branchStart = index;
					}
					case ')' -> {
						Optional<String> reason = closeGroup();

						if (reason.isPresent()) {
							return reason;
						}
					}
					default -> {
						index++;
						atomFollowedByQuantifier();
					}
				}
			}

			return Optional.empty();
		}

		private Frame top() {
			return stack.get(stack.size() - 1);
		}

		private void closeBranch(int branchEnd) {
			Frame frame = top();

			frame.branches.add(new int[] { frame.branchStart, branchEnd });
			frame.anyBranchEndsUnbounded |= frame.lastUnbounded;
			frame.lastUnbounded = false;
		}

		private void atomFollowedByQuantifier() {
			Quantifier quantifier = quantifierAt(index);

			index = quantifier.end();
			top().lastUnbounded = quantifier.unbounded();
		}

		private void openGroup() {
			index++;
			boolean atomic = false;

			if (index < pattern.length() && pattern.charAt(index) == '?') {
				atomic = index + 1 < pattern.length() && pattern.charAt(index + 1) == '>';
				index = endOfGroupPrefix(index + 1);
			}

			stack.add(new Frame(atomic, index));
		}

		private Optional<String> closeGroup() {
			if (stack.size() == 1) {
				index++;

				return Optional.empty();
			}

			closeBranch(index);
			Frame group = stack.remove(stack.size() - 1);
			index++;

			Quantifier quantifier = quantifierAt(index);
			index = quantifier.end();
			boolean endsUnbounded = !group.atomic && group.anyBranchEndsUnbounded;

			if (quantifier.unbounded() && endsUnbounded) {
				return Optional.of(NESTED_QUANTIFIER);
			}

			if (quantifier.unbounded() && !group.atomic && branchesOverlap(group.branches)) {
				return Optional.of(OVERLAPPING_ALTERNATION);
			}

			top().lastUnbounded = quantifier.unbounded() || (!quantifier.present() && endsUnbounded);

			return Optional.empty();
		}

		private boolean branchesOverlap(List<int[]> branches) {
			for (int first = 0; first < branches.size(); first++) {
				for (int second = first + 1; second < branches.size(); second++) {
					String left = pattern.substring(branches.get(first)[0], branches.get(first)[1]);
					String right = pattern.substring(branches.get(second)[0], branches.get(second)[1]);

					if (left.startsWith(right) || right.startsWith(left)) {
						return true;
					}
				}
			}

			return false;
		}

		/** Skips a group prefix after its "?" (non-capturing, lookaround, atomic, named or inline flags); returns the first body index. */
		private int endOfGroupPrefix(int afterQuestionMark) {
			if (afterQuestionMark >= pattern.length()) {
				return afterQuestionMark;
			}

			char kind = pattern.charAt(afterQuestionMark);

			if (kind == ':' || kind == '=' || kind == '!' || kind == '>') {
				return afterQuestionMark + 1;
			}

			if (kind == '<') {
				boolean lookbehind = afterQuestionMark + 1 < pattern.length() && "=!".indexOf(pattern.charAt(afterQuestionMark + 1)) >= 0;

				if (lookbehind) {
					return afterQuestionMark + 2;
				}

				int nameEnd = pattern.indexOf('>', afterQuestionMark);

				return nameEnd < 0 ? pattern.length() : nameEnd + 1;
			}

			int cursor = afterQuestionMark;

			while (cursor < pattern.length() && pattern.charAt(cursor) != ':' && pattern.charAt(cursor) != ')') {
				cursor++;
			}

			return cursor < pattern.length() && pattern.charAt(cursor) == ':' ? cursor + 1 : cursor;
		}

		private int endOfCharacterClass(int open) {
			int depth = 0;
			int cursor = open;

			while (cursor < pattern.length()) {
				char current = pattern.charAt(cursor);

				if (current == '\\') {
					cursor += 2;
					continue;
				}

				if (current == '[') {
					depth++;
				} else if (current == ']' && --depth == 0) {
					return cursor + 1;
				}

				cursor++;
			}

			return pattern.length();
		}

		private Quantifier quantifierAt(int start) {
			if (start >= pattern.length()) {
				return new Quantifier(start, false, false);
			}

			char current = pattern.charAt(start);
			int end;
			boolean unbounded;

			if (current == '*' || current == '+') {
				end = start + 1;
				unbounded = true;
			} else if (current == '?') {
				end = start + 1;
				unbounded = false;
			} else if (current == '{' && pattern.indexOf('}', start) > 0) {
				int close = pattern.indexOf('}', start);

				end = close + 1;
				unbounded = pattern.charAt(close - 1) == ',';
			} else {
				return new Quantifier(start, false, false);
			}

			if (end < pattern.length() && pattern.charAt(end) == '?') {
				end++;
			} else if (end < pattern.length() && pattern.charAt(end) == '+') {
				end++;
				unbounded = false;
			}

			return new Quantifier(end, true, unbounded);
		}
	}
}
