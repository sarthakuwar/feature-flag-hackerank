package com.featureflag.flags;

public class Variation {

	private String key;

	private String name;

	private String value;

	public Variation() {
	}

	public Variation(String key, String name, String value) {
		this.key = key;
		this.name = name;
		this.value = value;
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
}
