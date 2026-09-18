package com.featureflag.flags;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry in an environment's ordered activation strategy list. USER_ID and ATTRIBUTE
 * strategies resolve immediately when they match; a PERCENTAGE_ROLLOUT strategy resolves only
 * when the caller's stable hash bucket falls under {@link #percentage}, otherwise evaluation
 * falls through to the next strategy in the list (and ultimately to the environment's
 * offVariationKey if nothing matches). Multiple strategies - including multiple rollouts - can
 * be layered in one environment this way.
 */
public class Strategy {

	private String id;

	private int priority;

	private StrategyType type;

	private List<String> userIds = new ArrayList<>();

	private String attribute;

	private String attributeValue;

	private int percentage;

	private String servedVariationKey;

	public Strategy() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public int getPriority() {
		return priority;
	}

	public void setPriority(int priority) {
		this.priority = priority;
	}

	public StrategyType getType() {
		return type;
	}

	public void setType(StrategyType type) {
		this.type = type;
	}

	public List<String> getUserIds() {
		return userIds;
	}

	public void setUserIds(List<String> userIds) {
		this.userIds = userIds;
	}

	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	public String getAttributeValue() {
		return attributeValue;
	}

	public void setAttributeValue(String attributeValue) {
		this.attributeValue = attributeValue;
	}

	public int getPercentage() {
		return percentage;
	}

	public void setPercentage(int percentage) {
		this.percentage = percentage;
	}

	public String getServedVariationKey() {
		return servedVariationKey;
	}

	public void setServedVariationKey(String servedVariationKey) {
		this.servedVariationKey = servedVariationKey;
	}

	/** Only meaningful for USER_ID/ATTRIBUTE strategies; PERCENTAGE_ROLLOUT is bucket-based. */
	public boolean matches(UserContext userContext) {
		if (userContext == null) {
			return false;
		}

		if (type == StrategyType.USER_ID) {
			return userContext.userId() != null && userIds.contains(userContext.userId());
		}

		if (type == StrategyType.ATTRIBUTE) {
			String actual = userContext.attributes().get(attribute);

			return actual != null && actual.equals(attributeValue);
		}

		return false;
	}
}
