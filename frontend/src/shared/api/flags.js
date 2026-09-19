import { request } from "./client.js";

const enc = encodeURIComponent;

// Flags

export function listFlags(lifecycle) {
    const query = lifecycle ? `?lifecycle=${enc(lifecycle)}` : "";
    return request(`/flags${query}`);
}

export function getFlag(key) {
    return request(`/flags/${enc(key)}`);
}

export function createFlag(body) {
    return request("/flags", { method: "POST", body: JSON.stringify(body) });
}

export function updateFlag(key, body) {
    return request(`/flags/${enc(key)}`, { method: "PATCH", body: JSON.stringify(body) });
}

export function updateEnvironment(key, environment, body) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}`, { method: "PATCH", body: JSON.stringify(body) });
}

export function addVariation(key, body) {
    return request(`/flags/${enc(key)}/variations`, { method: "POST", body: JSON.stringify(body) });
}

export function updateVariation(key, variationKey, body) {
    return request(`/flags/${enc(key)}/variations/${enc(variationKey)}`, { method: "PATCH", body: JSON.stringify(body) });
}

export function removeVariation(key, variationKey, changedBy) {
    return request(`/flags/${enc(key)}/variations/${enc(variationKey)}`, { method: "DELETE", body: JSON.stringify({ changedBy }) });
}

export function archiveFlag(key, changedBy) {
    return request(`/flags/${enc(key)}/archive`, { method: "POST", body: JSON.stringify({ changedBy }) });
}

export function restoreFlag(key, changedBy) {
    return request(`/flags/${enc(key)}/restore`, { method: "POST", body: JSON.stringify({ changedBy }) });
}

export function getAudit(key) {
    return request(`/flags/${enc(key)}/audit`);
}

export function validateBulkArchive(keys) {
    return request("/flags/bulk-archive/validate", { method: "POST", body: JSON.stringify({ keys }) });
}

export function bulkArchive(keys, changedBy) {
    return request("/flags/bulk-archive", { method: "POST", body: JSON.stringify({ keys, changedBy }) });
}

export function bulkRestore(keys, changedBy) {
    return request("/flags/bulk-restore", { method: "POST", body: JSON.stringify({ keys, changedBy }) });
}

// Evaluation and SDK feeds

export function evaluateFlag(key, environment, body) {
    return request(`/eval/${enc(environment)}/${enc(key)}`, { method: "POST", body: JSON.stringify(body) });
}

export function evaluateAllFlags(environment, body) {
    return request(`/eval/${enc(environment)}`, { method: "POST", body: JSON.stringify(body) });
}

export function getBootstrap(environment) {
    return request(`/eval/${enc(environment)}/bootstrap`);
}

export function getSegmentFeed(environment) {
    return request(`/eval/${enc(environment)}/segments`);
}

export function getApplicationFeed() {
    return request("/eval/applications");
}

// Targeting rules and individual targets

export function getTargeting(key, environment) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/targeting`);
}

export function createRule(key, environment, body) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/rules`, { method: "POST", body: JSON.stringify(body) });
}

export function updateRule(key, environment, ruleId, body) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/rules/${enc(ruleId)}`, {
        method: "PUT",
        body: JSON.stringify(body),
    });
}

export function setRuleEnabled(key, environment, ruleId, enabled, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/rules/${enc(ruleId)}`, {
        method: "PATCH",
        body: JSON.stringify({ enabled, changedBy }),
    });
}

export function moveRule(key, environment, ruleId, direction, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/rules/${enc(ruleId)}/move`, {
        method: "POST",
        body: JSON.stringify({ direction, changedBy }),
    });
}

export function reorderRules(key, environment, ruleIds, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/rules/order`, {
        method: "PUT",
        body: JSON.stringify({ ruleIds, changedBy }),
    });
}

export function deleteRule(key, environment, ruleId, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/rules/${enc(ruleId)}`, {
        method: "DELETE",
        body: JSON.stringify({ changedBy }),
    });
}

export function replaceIndividualTargets(key, environment, targets, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/individual-targets`, {
        method: "PUT",
        body: JSON.stringify({ targets, changedBy }),
    });
}

export function bulkTarget(key, environment, body) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/individual-targets/bulk`, {
        method: "POST",
        body: JSON.stringify(body),
    });
}

// Progressive rollouts

export function listRollouts(key, environment, status) {
    const query = status ? `?status=${enc(status)}` : "";
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/progressive-rollouts${query}`);
}

export function startRollout(key, environment, body) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/progressive-rollouts`, {
        method: "POST",
        body: JSON.stringify(body),
    });
}

export function stopRollout(key, environment, rolloutId, variationKey, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/progressive-rollouts/${enc(rolloutId)}/stop`, {
        method: "POST",
        body: JSON.stringify({ variationKey, changedBy }),
    });
}

// Segments

export function listSegments(environment, { query, contextKind } = {}) {
    const params = new URLSearchParams();
    if (query) params.set("q", query);
    if (contextKind) params.set("contextKind", contextKind);
    const suffix = params.size > 0 ? `?${params}` : "";
    return request(`/segments/${enc(environment)}${suffix}`);
}

export function getSegment(environment, key) {
    return request(`/segments/${enc(environment)}/${enc(key)}`);
}

export function createSegment(environment, body) {
    return request(`/segments/${enc(environment)}`, { method: "POST", body: JSON.stringify(body) });
}

export function updateSegment(environment, key, body) {
    return request(`/segments/${enc(environment)}/${enc(key)}`, { method: "PATCH", body: JSON.stringify(body) });
}

export function changeSegmentMembers(environment, key, body) {
    return request(`/segments/${enc(environment)}/${enc(key)}/members`, { method: "POST", body: JSON.stringify(body) });
}

export function getSegmentUsage(environment, key) {
    return request(`/segments/${enc(environment)}/${enc(key)}/usage`);
}

export function deleteSegment(environment, key, changedBy) {
    return request(`/segments/${enc(environment)}/${enc(key)}`, { method: "DELETE", body: JSON.stringify({ changedBy }) });
}

// Mobile applications

export function listApplications() {
    return request("/mobile-applications");
}

export function createApplication(body) {
    return request("/mobile-applications", { method: "POST", body: JSON.stringify(body) });
}

export function updateApplication(key, body) {
    return request(`/mobile-applications/${enc(key)}`, { method: "PATCH", body: JSON.stringify(body) });
}

export function deleteApplication(key, changedBy) {
    return request(`/mobile-applications/${enc(key)}`, { method: "DELETE", body: JSON.stringify({ changedBy }) });
}

// Experiments and metrics

export function setExperimentWeights(key, environment, weights, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/experiment/weights`, {
        method: "PUT",
        body: JSON.stringify({ weights, changedBy }),
    });
}

export function configureBandit(key, environment, algorithm, epsilon, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/experiment/bandit`, {
        method: "PUT",
        body: JSON.stringify({ algorithm, epsilon, changedBy }),
    });
}

export function reallocateExperiment(key, environment, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/experiment/reallocate`, {
        method: "POST",
        body: JSON.stringify({ changedBy }),
    });
}

export function promoteExperiment(key, environment, winningVariationKey, changedBy) {
    return request(`/flags/${enc(key)}/environments/${enc(environment)}/experiment/promote`, {
        method: "POST",
        body: JSON.stringify({ winningVariationKey, changedBy }),
    });
}

export function getExperimentMetrics(key, environment, eventType) {
    const query = eventType ? `?eventType=${enc(eventType)}` : "";
    return request(`/metrics/${enc(key)}/${enc(environment)}${query}`);
}

export function recordConversion(body) {
    return request("/metrics/conversions", { method: "POST", body: JSON.stringify(body) });
}
