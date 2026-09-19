package com.featureflag.flags.targeting.dto;

import com.featureflag.flags.targeting.MoveDirection;

public record RuleMoveRequest(MoveDirection direction, String changedBy) {
}
