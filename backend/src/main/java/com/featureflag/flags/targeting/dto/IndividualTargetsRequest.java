package com.featureflag.flags.targeting.dto;

import java.util.List;

/** Replaces the environment's whole individual-target list. */
public record IndividualTargetsRequest(List<IndividualTargetRequest> targets, String changedBy) {
}
