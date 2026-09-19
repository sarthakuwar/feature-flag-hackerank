package com.featureflag.flags.targeting.dto;

import java.util.List;

public record IndividualTargetRequest(String contextKind, String variationKey, List<String> keys) {
}
