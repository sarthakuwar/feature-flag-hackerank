export const USER_KIND = "user";
export const APPLICATION_KIND = "ld_application";
export const DEVICE_KIND = "ld_device";

export const CONTEXT_KINDS = [USER_KIND, "organization", APPLICATION_KIND, DEVICE_KIND];

export const OPERATORS = [
    { value: "IN", label: "is one of", group: "Text" },
    { value: "STARTS_WITH", label: "starts with", group: "Text" },
    { value: "ENDS_WITH", label: "ends with", group: "Text" },
    { value: "CONTAINS", label: "contains", group: "Text" },
    { value: "MATCHES", label: "matches regex", group: "Text" },
    { value: "LESS_THAN", label: "is less than", group: "Number" },
    { value: "LESS_THAN_OR_EQUAL", label: "is at most", group: "Number" },
    { value: "GREATER_THAN", label: "is greater than", group: "Number" },
    { value: "GREATER_THAN_OR_EQUAL", label: "is at least", group: "Number" },
    { value: "SEMVER_EQUAL", label: "is version", group: "Version" },
    { value: "SEMVER_LESS_THAN", label: "is older than version", group: "Version" },
    { value: "SEMVER_GREATER_THAN", label: "is newer than version", group: "Version" },
    { value: "IN_SEGMENT", label: "is in segment", group: "Segment" },
    { value: "APP_VERSION_SUPPORTED", label: "runs a supported app version", group: "Mobile" },
];

const NEGATED_LABELS = {
    IN: "is not one of",
    STARTS_WITH: "does not start with",
    ENDS_WITH: "does not end with",
    CONTAINS: "does not contain",
    MATCHES: "does not match regex",
    IN_SEGMENT: "is not in segment",
    APP_VERSION_SUPPORTED: "runs an unsupported app version",
};

export function operatorLabel(operator, negate) {
    if (negate) {
        const negated = NEGATED_LABELS[operator];
        if (negated) return negated;
    }
    const base = OPERATORS.find((entry) => entry.value === operator)?.label ?? operator;
    return negate ? `not (${base})` : base;
}

export function usesAttribute(operator) {
    return operator !== "IN_SEGMENT" && operator !== "APP_VERSION_SUPPORTED";
}

export function emptyClause() {
    return { contextKind: USER_KIND, attribute: "", operator: "IN", values: [], negate: false };
}

export function toClauseRequest(clause) {
    const attributeBased = usesAttribute(clause.operator);
    return {
        contextKind: clause.contextKind,
        attribute: attributeBased ? clause.attribute.trim() : null,
        operator: clause.operator,
        values: clause.operator === "APP_VERSION_SUPPORTED" ? [] : clause.values,
        negate: clause.negate,
    };
}

export function describeClause(clause) {
    const subject = usesAttribute(clause.operator) ? `${clause.contextKind} ${clause.attribute}` : clause.contextKind;
    const verb = operatorLabel(clause.operator, clause.negate);
    if (clause.operator === "APP_VERSION_SUPPORTED") return { subject, verb, values: [] };
    return { subject, verb, values: clause.values };
}

export function ruleCategory(rule) {
    const clauses = rule.clauses || [];
    if (clauses.length > 0 && clauses.every((clause) => clause.operator === "IN_SEGMENT")) return "SEGMENT";
    const mobile = clauses.some(
        (clause) =>
            clause.contextKind === APPLICATION_KIND || clause.contextKind === DEVICE_KIND || clause.operator === "APP_VERSION_SUPPORTED",
    );
    return mobile ? "MOBILE" : "CUSTOM";
}

export function rolloutSummary(weights, variations) {
    return variations
        .filter((variation) => (weights?.[variation.key] ?? 0) > 0)
        .map((variation) => ({ key: variation.key, name: variation.name, weight: weights[variation.key] }));
}

export function evenWeights(variations) {
    const base = Math.floor(100 / variations.length);
    const remainder = 100 - base * variations.length;
    return Object.fromEntries(variations.map((variation, index) => [variation.key, base + (index < remainder ? 1 : 0)]));
}
