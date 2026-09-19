package com.featureflag.flags.targeting.dto;

import java.util.List;

/** A segment rule; keep id when editing an existing rule so it stays stable, omit it for a new one. */
public record SegmentRuleRequest(String id, String description, List<ClauseRequest> clauses) {
}
