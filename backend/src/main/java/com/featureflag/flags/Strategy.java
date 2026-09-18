package com.featureflag.flags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry in an environment's ordered activation strategy list. USER_ID and ATTRIBUTE
 * strategies resolve immediately when they match; a PERCENTAGE_ROLLOUT strategy resolves only
 * when the caller's stable hash bucket falls under {@link #percentage}, otherwise evaluation
 * falls through to the next strategy in the list (and ultimately to the environment's
 * offVariationKey if nothing matches). Multiple strategies - including multiple rollouts - can
 * be layered in one environment this way.
 *
 * <p>EXPERIMENT_SPLIT is a weighted multi-variation rollout: {@link #variationWeights} maps every
 * variation key to a 0-100 share that sums to 100 across the flag's variations. It always
 * resolves (every stable-hash bucket 0-99 falls in some variation's range), so it behaves as a
 * terminal strategy in priority order, the same way a 100% PERCENTAGE_ROLLOUT already does.
 * {@link #banditAlgorithm} and {@link #epsilon} are optional multi-armed-bandit configuration
 * that periodically recomputes {@link #variationWeights} from observed metrics instead of
 * leaving them fixed.
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

	/** EXPERIMENT_SPLIT only: variationKey -> weight (0-100), summing to 100. */
	private Map<String, Integer> variationWeights = new LinkedHashMap<>();

	/** EXPERIMENT_SPLIT only: NONE means a fixed split; otherwise weights are periodically recomputed. */
	private BanditAlgorithm banditAlgorithm = BanditAlgorithm.NONE;

	/** Required in (0, 1] when banditAlgorithm is EPSILON_GREEDY, null otherwise. */
	private Double epsilon;

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

	public Map<String, Integer> getVariationWeights() {
		return variationWeights;
	}

	public void setVariationWeights(Map<String, Integer> variationWeights) {
		this.variationWeights = variationWeights;
	}

	public BanditAlgorithm getBanditAlgorithm() {
		return banditAlgorithm;
	}

	public void setBanditAlgorithm(BanditAlgorithm banditAlgorithm) {
		this.banditAlgorithm = banditAlgorithm;
	}

	public Double getEpsilon() {
		return epsilon;
	}

	public void setEpsilon(Double epsilon) {
		this.epsilon = epsilon;
	}

	/** Only meaningful for USER_ID/ATTRIBUTE strategies; PERCENTAGE_ROLLOUT/EXPERIMENT_SPLIT are bucket-based. */
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
