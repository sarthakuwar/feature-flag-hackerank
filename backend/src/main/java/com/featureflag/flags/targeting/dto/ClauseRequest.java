package com.featureflag.flags.targeting.dto;

import java.util.List;

import com.featureflag.flags.targeting.ClauseOperator;

/** contextKind defaults to "user". attribute is ignored (and should be omitted) for IN_SEGMENT and APP_VERSION_SUPPORTED. */
public record ClauseRequest(String contextKind, String attribute, ClauseOperator operator, List<String> values,
		boolean negate) {
}
