package com.featureflag.flags;

public class Variation {

	private String key;

	private String name;

	private String value;

	/** Declared default traffic share for this variation, 0-100. Null until an experiment split is configured. */
	private Integer weight;

	/** Null when this variation carries no payload beyond its bare value. */
	private PayloadType payloadType;

	private String payloadValue;

	public Variation() {
	}

	public Variation(String key, String name, String value) {
		this(key, name, value, null, null, null);
	}

	public Variation(String key, String name, String value, Integer weight, PayloadType payloadType, String payloadValue) {
		this.key = key;
		this.name = name;
		this.value = value;
		this.weight = weight;
		this.payloadType = payloadType;
		this.payloadValue = payloadValue;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public Integer getWeight() {
		return weight;
	}

	public void setWeight(Integer weight) {
		this.weight = weight;
	}

	public PayloadType getPayloadType() {
		return payloadType;
	}

	public void setPayloadType(PayloadType payloadType) {
		this.payloadType = payloadType;
	}

	public String getPayloadValue() {
		return payloadValue;
	}

	public void setPayloadValue(String payloadValue) {
		this.payloadValue = payloadValue;
	}
}
