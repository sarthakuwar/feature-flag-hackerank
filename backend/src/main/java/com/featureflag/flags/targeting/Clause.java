package com.featureflag.flags.targeting;

import java.util.ArrayList;
import java.util.List;

import com.featureflag.flags.UserContext;

/**
 * One condition inside a targeting rule or a segment rule. {@code contextKind} says which of the
 * evaluated contexts it inspects ("user" by default; "organization", "ld_application", "ld_device"
 * and so on). A clause whose context kind isn't part of the evaluation, or whose attribute is
 * missing, is simply false - {@link #isNegate()} is not applied to it, so "is not one of" never
 * matches a context that lacks the attribute entirely.
 */
public class Clause {

	private String contextKind = UserContext.USER_KIND;

	private String attribute;

	private ClauseOperator operator;

	private List<String> values = new ArrayList<>();

	private boolean negate;

	public Clause() {
	}

	public Clause(String contextKind, String attribute, ClauseOperator operator, List<String> values, boolean negate) {
		this.contextKind = contextKind == null || contextKind.isBlank() ? UserContext.USER_KIND : contextKind;
		this.attribute = attribute;
		this.operator = operator;
		this.values = values == null ? new ArrayList<>() : new ArrayList<>(values);
		this.negate = negate;
	}

	public String getContextKind() {
		return contextKind;
	}

	public void setContextKind(String contextKind) {
		this.contextKind = contextKind;
	}

	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	public ClauseOperator getOperator() {
		return operator;
	}

	public void setOperator(ClauseOperator operator) {
		this.operator = operator;
	}

	public List<String> getValues() {
		return values;
	}

	public void setValues(List<String> values) {
		this.values = values;
	}

	public boolean isNegate() {
		return negate;
	}

	public void setNegate(boolean negate) {
		this.negate = negate;
	}

	public boolean targetsMobileContext() {
		return TargetingLimits.isMobileKind(contextKind) || operator == ClauseOperator.APP_VERSION_SUPPORTED;
	}
}
