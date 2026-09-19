package com.featureflag.flags.targeting.dto;

import java.time.Instant;

import com.featureflag.flags.targeting.MobileApplication;

public record MobileApplicationResponse(String key, String name, String minSupportedVersion, String updatedBy,
		Instant createdAt, Instant updatedAt) {

	public static MobileApplicationResponse from(MobileApplication application) {
		return new MobileApplicationResponse(application.getKey(), application.getName(),
				application.getMinSupportedVersion(), application.getUpdatedBy(), application.getCreatedAt(),
				application.getUpdatedAt());
	}
}
