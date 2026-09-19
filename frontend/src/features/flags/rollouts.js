import { formatEnumLabel } from "../../shared/format.js";

export function linearStages(steps, durationMinutes) {
    if (!Number.isInteger(steps) || !Number.isInteger(durationMinutes) || steps < 2 || durationMinutes < 1) return [];
    return Array.from({ length: steps }, (_, index) => ({
        percentage: Math.round((100 * (index + 1)) / steps),
        offsetMinutes: Math.floor((durationMinutes * index) / (steps - 1)),
    }));
}

export function rolloutTargetLabel(rollout, rules, strategies) {
    if (rollout.ruleId) {
        const rule = rules.find((entry) => entry.id === rollout.ruleId);
        return `Rule: ${rule ? rule.description || "Untitled rule" : "deleted rule"}`;
    }
    const strategy = strategies.find((entry) => entry.id === rollout.strategyId);
    return `Strategy: ${strategy ? formatEnumLabel(strategy.type) : "removed strategy"}`;
}
