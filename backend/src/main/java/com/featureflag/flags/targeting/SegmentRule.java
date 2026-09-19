package com.featureflag.flags.targeting;

import java.util.ArrayList;
import java.util.List;

/** A segment-membership rule: a context is a member if every clause matches. Segment rules are OR'd together. */
public class SegmentRule {

	private String id;

	private String description;

	private List<Clause> clauses = new ArrayList<>();

	public SegmentRule() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public List<Clause> getClauses() {
		return clauses;
	}

	public void setClauses(List<Clause> clauses) {
		this.clauses = clauses;
	}
}
