package com.featureflag.flags.experiment;

import java.util.List;
import java.util.Map;

import com.featureflag.flags.metrics.dto.VariationMetrics;

/** Turns observed per-variation metrics into a new weight distribution (0-100, summing to 100). */
public interface BanditAllocator {

	Map<String, Integer> allocate(List<VariationMetrics> metrics, Double epsilon);
}
