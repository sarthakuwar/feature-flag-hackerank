package com.featureflag.flags.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.featureflag.flags.metrics.dto.VariationMetrics;

class ThompsonSamplingAllocatorTest {

	@Test
	void weightsSumToOneHundred() {
		ThompsonSamplingAllocator allocator = new ThompsonSamplingAllocator(new Random(42));
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 100, 10, 0.10, null),
				new VariationMetrics("b", 100, 30, 0.30, null));

		Map<String, Integer> weights = allocator.allocate(metrics, null);

		assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
	}

	@Test
	void aClearlyBetterVariationGetsMaterillyMoreWeight() {
		ThompsonSamplingAllocator allocator = new ThompsonSamplingAllocator(new Random(7));
		List<VariationMetrics> metrics = List.of(new VariationMetrics("weak", 500, 20, 0.04, null),
				new VariationMetrics("strong", 500, 200, 0.40, null));

		Map<String, Integer> weights = allocator.allocate(metrics, null);

		assertThat(weights.get("strong")).isGreaterThan(weights.get("weak"));
		assertThat(weights.get("strong")).isGreaterThan(80);
	}

	@Test
	void identicalSeedProducesIdenticalOutput() {
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 300, 45, 0.15, null),
				new VariationMetrics("b", 300, 60, 0.20, null));

		Map<String, Integer> first = new ThompsonSamplingAllocator(new Random(123)).allocate(metrics, null);
		Map<String, Integer> second = new ThompsonSamplingAllocator(new Random(123)).allocate(metrics, null);

		assertThat(first).isEqualTo(second);
	}

	@Test
	void aZeroImpressionVariationStillGetsNonzeroWeightFromThePrior() {
		ThompsonSamplingAllocator allocator = new ThompsonSamplingAllocator(new Random(99));
		List<VariationMetrics> metrics = List.of(new VariationMetrics("established", 1000, 300, 0.30, null),
				new VariationMetrics("brand-new", 0, 0, 0.0, null));

		Map<String, Integer> weights = allocator.allocate(metrics, null);

		assertThat(weights.get("brand-new")).isGreaterThan(0);
	}

	@Test
	void weightsAreNeverNegativeAndEveryVariationIsPresent() {
		ThompsonSamplingAllocator allocator = new ThompsonSamplingAllocator(new Random(5));
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 50, 1, 0.02, null),
				new VariationMetrics("b", 50, 2, 0.04, null), new VariationMetrics("c", 50, 3, 0.06, null));

		Map<String, Integer> weights = allocator.allocate(metrics, null);

		assertThat(weights).containsOnlyKeys("a", "b", "c");
		assertThat(weights.values()).allMatch(weight -> weight >= 0);
	}

	@Test
	void moreConversionsThanImpressionsStillTerminatesAndSumsToOneHundred() {
		ThompsonSamplingAllocator allocator = new ThompsonSamplingAllocator(new Random(11));
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 3, 50, 16.7, null),
				new VariationMetrics("b", 100, 10, 0.10, null));

		Map<String, Integer> weights = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> allocator.allocate(metrics, null));

		assertThat(weights).containsOnlyKeys("a", "b");
		assertThat(weights.values()).allMatch(weight -> weight >= 0);
		assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
	}

	@Test
	void conversionsWithNoImpressionsAtAllStillTerminatesAndSumsToOneHundred() {
		ThompsonSamplingAllocator allocator = new ThompsonSamplingAllocator(new Random(12));
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 0, 5, 0.0, null),
				new VariationMetrics("b", 0, 0, 0.0, null), new VariationMetrics("c", 0, 1000, 0.0, null));

		Map<String, Integer> weights = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> allocator.allocate(metrics, null));

		assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
	}

	@Test
	void zeroImpressionsAndZeroConversionsSplitTheTrafficAndSumToOneHundred() {
		ThompsonSamplingAllocator allocator = new ThompsonSamplingAllocator(new Random(13));
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 0, 0, 0.0, null),
				new VariationMetrics("b", 0, 0, 0.0, null));

		Map<String, Integer> weights = allocator.allocate(metrics, null);

		assertThat(weights.get("a")).isBetween(35, 65);
		assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
	}

	@Test
	void hugeCountsTerminateAndTheBetterVariationWins() {
		ThompsonSamplingAllocator allocator = new ThompsonSamplingAllocator(new Random(14));
		long huge = 4_000_000_000_000_000_000L;
		List<VariationMetrics> metrics = List.of(new VariationMetrics("weak", huge, huge / 100, 0.01, null),
				new VariationMetrics("strong", huge, huge / 2, 0.5, null));

		Map<String, Integer> weights = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> allocator.allocate(metrics, null));

		assertThat(weights.get("strong")).isGreaterThan(weights.get("weak"));
		assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
	}

	@Test
	void aBrokenRandomSourceCannotSpinTheSamplerForever() {
		Random neverAcceptable = new Random(1) {

			@Override
			public double nextGaussian() {
				return -1e9;
			}
		};
		Random notANumber = new Random(1) {

			@Override
			public double nextGaussian() {
				return Double.NaN;
			}
		};
		List<VariationMetrics> metrics = List.of(new VariationMetrics("a", 10, 2, 0.2, null),
				new VariationMetrics("b", 10, 5, 0.5, null));

		for (Random broken : List.of(neverAcceptable, notANumber)) {
			Map<String, Integer> weights = assertTimeoutPreemptively(Duration.ofSeconds(10),
					() -> new ThompsonSamplingAllocator(broken).allocate(metrics, null));

			assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
		}
	}
}
