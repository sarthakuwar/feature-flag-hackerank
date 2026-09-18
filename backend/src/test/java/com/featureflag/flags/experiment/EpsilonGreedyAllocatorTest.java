package com.featureflag.flags.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.featureflag.flags.metrics.dto.VariationMetrics;

class EpsilonGreedyAllocatorTest {

	private final EpsilonGreedyAllocator allocator = new EpsilonGreedyAllocator();

	@Test
	void weightsSumToOneHundred() {
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 100, 10, 0.10, null),
				new VariationMetrics("b", 100, 30, 0.30, null), new VariationMetrics("c", 100, 20, 0.20, null));

		Map<String, Integer> weights = allocator.allocate(metrics, 0.1);

		assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
	}

	@Test
	void bestVariationGetsTheMajorityShare() {
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 100, 10, 0.10, null),
				new VariationMetrics("b", 100, 30, 0.30, null), new VariationMetrics("c", 100, 20, 0.20, null));

		Map<String, Integer> weights = allocator.allocate(metrics, 0.1);

		assertThat(weights.get("b")).isGreaterThan(weights.get("a"));
		assertThat(weights.get("b")).isGreaterThan(weights.get("c"));
		assertThat(weights.get("b")).isGreaterThan(50);
	}

	@Test
	void allZeroImpressionsGivesAUniformSplitSinceThereIsNoSignalToExploitYet() {
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 0, 0, 0.0, null),
				new VariationMetrics("b", 0, 0, 0.0, null));

		Map<String, Integer> weights = allocator.allocate(metrics, 0.1);

		assertThat(weights.get("a")).isEqualTo(50);
		assertThat(weights.get("b")).isEqualTo(50);
	}

	@Test
	void aFullTieAcrossEveryVariationGivesAUniformSplitEvenWithNonzeroData() {
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 100, 20, 0.20, null),
				new VariationMetrics("b", 100, 20, 0.20, null), new VariationMetrics("c", 100, 20, 0.20, null));

		Map<String, Integer> weights = allocator.allocate(metrics, 0.1);

		assertThat(weights.get("a")).isEqualTo(34);
		assertThat(weights.get("b")).isEqualTo(33);
		assertThat(weights.get("c")).isEqualTo(33);
	}

	@Test
	void aTieForBestAmongSomeButNotAllVariationsIsBrokenByFirstInVariationOrder() {
		List<VariationMetrics> metrics = List.of(new VariationMetrics("first", 100, 20, 0.20, null),
				new VariationMetrics("second", 100, 20, 0.20, null), new VariationMetrics("third", 100, 5, 0.05, null));

		Map<String, Integer> weights = allocator.allocate(metrics, 0.1);

		assertThat(weights.get("first")).isGreaterThan(weights.get("second"));
		assertThat(weights.get("first")).isGreaterThan(weights.get("third"));
	}

	@Test
	void isFullyDeterministicAcrossRepeatedCalls() {
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 200, 15, 0.075, null),
				new VariationMetrics("b", 180, 40, 0.222, null));

		Map<String, Integer> first = allocator.allocate(metrics, 0.2);
		Map<String, Integer> second = allocator.allocate(metrics, 0.2);

		assertThat(first).isEqualTo(second);
	}

	@Test
	void nullEpsilonFallsBackToADefault() {
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 100, 5, 0.05, null),
				new VariationMetrics("b", 100, 50, 0.50, null));

		Map<String, Integer> weights = allocator.allocate(metrics, null);

		assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
		assertThat(weights.get("b")).isGreaterThan(weights.get("a"));
	}
}
