package com.featureflag.flags.targeting;

import java.util.LinkedHashSet;
import java.util.Set;

import com.featureflag.flags.UserContext;

/**
 * "Serve this variation to exactly these contexts": the highest-precedence targeting layer after the
 * environment on/off switch. Keys are a set, not a list, so membership stays O(1) even after a bulk
 * import of tens of thousands of keys. A key appears under at most one variation per context kind
 * (the service enforces this) so individual targeting can never be ambiguous.
 */
public class IndividualTarget {

	private String contextKind = UserContext.USER_KIND;

	private String variationKey;

	private Set<String> keys = new LinkedHashSet<>();

	public IndividualTarget() {
	}

	public IndividualTarget(String contextKind, String variationKey, Set<String> keys) {
		this.contextKind = contextKind;
		this.variationKey = variationKey;
		this.keys = keys == null ? new LinkedHashSet<>() : new LinkedHashSet<>(keys);
	}

	public String getContextKind() {
		return contextKind;
	}

	public void setContextKind(String contextKind) {
		this.contextKind = contextKind;
	}

	public String getVariationKey() {
		return variationKey;
	}

	public void setVariationKey(String variationKey) {
		this.variationKey = variationKey;
	}

	public Set<String> getKeys() {
		return keys;
	}

	public void setKeys(Set<String> keys) {
		this.keys = keys;
	}
}
