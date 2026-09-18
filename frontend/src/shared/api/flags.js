import { request } from "./client.js";

export function listFlags(lifecycle) {
    const query = lifecycle ? `?lifecycle=${encodeURIComponent(lifecycle)}` : "";
    return request(`/flags${query}`);
}

export function getFlag(key) {
    return request(`/flags/${encodeURIComponent(key)}`);
}

export function createFlag(body) {
    return request("/flags", { method: "POST", body: JSON.stringify(body) });
}

export function updateEnvironment(key, environment, body) {
    return request(`/flags/${encodeURIComponent(key)}/environments/${environment}`, {
        method: "PATCH",
        body: JSON.stringify(body),
    });
}

export function addVariation(key, body) {
    return request(`/flags/${encodeURIComponent(key)}/variations`, { method: "POST", body: JSON.stringify(body) });
}

export function removeVariation(key, variationKey, changedBy) {
    return request(`/flags/${encodeURIComponent(key)}/variations/${encodeURIComponent(variationKey)}`, {
        method: "DELETE",
        body: JSON.stringify({ changedBy }),
    });
}

export function archiveFlag(key, changedBy) {
    return request(`/flags/${encodeURIComponent(key)}/archive`, { method: "POST", body: JSON.stringify({ changedBy }) });
}

export function restoreFlag(key, changedBy) {
    return request(`/flags/${encodeURIComponent(key)}/restore`, { method: "POST", body: JSON.stringify({ changedBy }) });
}

export function getAudit(key) {
    return request(`/flags/${encodeURIComponent(key)}/audit`);
}

export function bulkArchive(keys, changedBy) {
    return request("/flags/bulk-archive", { method: "POST", body: JSON.stringify({ keys, changedBy }) });
}

export function bulkRestore(keys, changedBy) {
    return request("/flags/bulk-restore", { method: "POST", body: JSON.stringify({ keys, changedBy }) });
}

export function evaluateFlag(key, environment, body) {
    return request(`/eval/${environment}/${encodeURIComponent(key)}`, { method: "POST", body: JSON.stringify(body) });
}

export function setExperimentWeights(key, environment, weights, changedBy) {
    return request(`/flags/${encodeURIComponent(key)}/environments/${environment}/experiment/weights`, {
        method: "PUT",
        body: JSON.stringify({ weights, changedBy }),
    });
}

export function configureBandit(key, environment, algorithm, epsilon, changedBy) {
    return request(`/flags/${encodeURIComponent(key)}/environments/${environment}/experiment/bandit`, {
        method: "PUT",
        body: JSON.stringify({ algorithm, epsilon, changedBy }),
    });
}

export function reallocateExperiment(key, environment, changedBy) {
    return request(`/flags/${encodeURIComponent(key)}/environments/${environment}/experiment/reallocate`, {
        method: "POST",
        body: JSON.stringify({ changedBy }),
    });
}

export function promoteExperiment(key, environment, winningVariationKey, changedBy) {
    return request(`/flags/${encodeURIComponent(key)}/environments/${environment}/experiment/promote`, {
        method: "POST",
        body: JSON.stringify({ winningVariationKey, changedBy }),
    });
}

export function getExperimentMetrics(key, environment, eventType) {
    const query = eventType ? `?eventType=${encodeURIComponent(eventType)}` : "";
    return request(`/metrics/${encodeURIComponent(key)}/${environment}${query}`);
}

export function recordConversion(body) {
    return request("/metrics/conversions", { method: "POST", body: JSON.stringify(body) });
}
