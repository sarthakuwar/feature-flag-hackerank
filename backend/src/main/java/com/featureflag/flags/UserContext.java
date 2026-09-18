package com.featureflag.flags;

import java.util.Collections;
import java.util.Map;

public record UserContext(String userId, Map<String, String> attributes) {

	public UserContext {
		attributes = attributes == null ? Collections.emptyMap() : Map.copyOf(attributes);
	}

	public static UserContext anonymous(String userId) {
		return new UserContext(userId, Collections.emptyMap());
	}
}
