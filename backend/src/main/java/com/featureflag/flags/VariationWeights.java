package com.featureflag.flags;

import java.util.List;

import com.featureflag.shared.FieldErrors;

/** The whole-flag weight invariant: every variation weighted and summing to 100, or none weighted at all. */
final class VariationWeights {

	private VariationWeights() {
	}

	/**
	 * "Custom percentage" is all-or-nothing per save: either every variation specifies a weight and
	 * they sum to 100, or none do and an even split is assigned automatically - two variants
	 * default to 50/50, three to 34/33/33, and so on, remainder going to the first variations.
	 */
	static void assign(List<Variation> variations, FieldErrors errors) {
		long specified = variations.stream().filter(variation -> variation.getWeight() != null).count();

		if (specified == 0) {
			evenSplit(variations);

			return;
		}

		if (specified != variations.size()) {
			errors.add("variations", "Specify a weight for every variation, or none, to use an even split.");

			return;
		}

		int sum = variations.stream().mapToInt(Variation::getWeight).sum();

		if (sum != 100) {
			errors.add("variations", "Variation weights must sum to 100 (found " + sum + ").");
		}
	}

	/** Divides 100 as evenly as possible across every variation, remainder going to the first ones. */
	static void evenSplit(List<Variation> variations) {
		int even = 100 / variations.size();
		int remainder = 100 - (even * variations.size());

		for (int index = 0; index < variations.size(); index++) {
			variations.get(index).setWeight(even + (index < remainder ? 1 : 0));
		}
	}

	/**
	 * Used by addVariation/updateVariation. A flag created through {@code FlagService.create} always
	 * has a weight on every variation ({@link #assign} guarantees it), so weights are only ever
	 * partially missing here for flags predating that invariant; in that legacy case, weight simply
	 * isn't being tracked yet and there's nothing to validate. Once a flag has at least one weighted
	 * variation, every variation must have one and they must sum to 100.
	 */
	static void validateInvariant(List<Variation> variations, FieldErrors errors) {
		boolean noneWeighted = variations.stream().allMatch(variation -> variation.getWeight() == null);

		if (noneWeighted) {
			return;
		}

		boolean anyMissing = variations.stream().anyMatch(variation -> variation.getWeight() == null);

		if (anyMissing) {
			errors.add("weight", "Every variation must specify a weight.");

			return;
		}

		int sum = variations.stream().mapToInt(Variation::getWeight).sum();

		if (sum != 100) {
			errors.add("weight", "Variation weights must sum to 100 (found " + sum + ").");
		}
	}

	/**
	 * Removing a variation frees up its weight share. Scale the remaining variations'
	 * weights up proportionally so they still sum to 100, rather than leaving a gap or
	 * forcing the caller to manually rebalance every remaining variation in the same call.
	 * Falls back to an even split if the remaining variations were all weighted at 0.
	 */
	static void redistributeAfterRemoval(List<Variation> remaining) {
		if (remaining.isEmpty()) {
			return;
		}

		boolean noneWeighted = remaining.stream().allMatch(variation -> variation.getWeight() == null);

		if (noneWeighted) {
			return;
		}

		int sum = remaining.stream().mapToInt(variation -> variation.getWeight() == null ? 0 : variation.getWeight()).sum();

		if (sum == 100) {
			return;
		}

		if (sum == 0) {
			evenSplit(remaining);

			return;
		}

		int running = 0;
		int largestIndex = 0;
		int largestOriginalWeight = -1;

		for (int index = 0; index < remaining.size(); index++) {
			int original = remaining.get(index).getWeight() == null ? 0 : remaining.get(index).getWeight();
			int scaled = (int) Math.round(original * 100.0 / sum);
			remaining.get(index).setWeight(scaled);
			running += scaled;

			if (original > largestOriginalWeight) {
				largestOriginalWeight = original;
				largestIndex = index;
			}
		}

		Variation largest = remaining.get(largestIndex);
		largest.setWeight(largest.getWeight() + (100 - running));
	}
}
