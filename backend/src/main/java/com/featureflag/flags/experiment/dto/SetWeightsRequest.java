package com.featureflag.flags.experiment.dto;

import java.util.Map;

public record SetWeightsRequest(Map<String, Integer> weights, String changedBy) {
}
