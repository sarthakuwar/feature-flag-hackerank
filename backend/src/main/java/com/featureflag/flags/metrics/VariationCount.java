package com.featureflag.flags.metrics;

/** One row of a per-variation event count: {@code id} is the variation key the events were grouped by. */
public record VariationCount(String id, long count) {
}
