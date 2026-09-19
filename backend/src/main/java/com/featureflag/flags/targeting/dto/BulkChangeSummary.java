package com.featureflag.flags.targeting.dto;

/** What a bulk key change did: keys submitted (after trimming/dedup), how many changed state, how many already were. */
public record BulkChangeSummary(int requested, int changed, int unchanged) {
}
