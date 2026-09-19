export const baseUrl = import.meta.env.VITE_API_URL || "/api/v1";

const REQUEST_TIMEOUT_MS = 15000;
const CONFLICT_MESSAGE = "This item was changed by someone else. Reload to see the latest version.";

export class ApiError extends Error {
    constructor(message, { status, code, details } = {}) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.code = code;
        this.details = details;
    }
}

function transportError(err) {
    if (err instanceof ApiError) return err;
    if (err?.name === "TimeoutError" || err?.name === "AbortError") {
        return new ApiError("The Feature Flag service took too long to respond. Try again in a moment.", { code: "TIMEOUT" });
    }
    if (err instanceof TypeError) {
        return new ApiError("Could not reach the Feature Flag service. Check your connection and try again.", { code: "NETWORK_ERROR" });
    }
    return new ApiError("Feature Flag service returned an invalid response.", { code: "INVALID_RESPONSE" });
}

// Resolves to the `data` field, or to `{ items, meta }` when `paged` is set for a paginated list endpoint.
export async function request(path, { paged = false, ...options } = {}) {
    let response;
    let payload = null;
    try {
        response = await fetch(`${baseUrl}${path}`, {
            ...options,
            signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
            headers: {
                "Content-Type": "application/json",
                ...options.headers,
            },
        });
        if (response.status === 204) return null;
        const contentType = response.headers?.get?.("content-type") || "";
        payload = contentType.includes("application/json") || !response.headers ? await response.json() : null;
    } catch (err) {
        throw transportError(err);
    }
    if (!response.ok) {
        const code = payload?.error?.code;
        const serverMessage = payload?.error?.message || `Feature Flag service returned ${response.status}.`;
        const message = code === "CONFLICT" ? CONFLICT_MESSAGE : serverMessage;
        throw new ApiError(message, { status: response.status, code, details: payload?.error?.details });
    }
    if (!payload || !("data" in payload)) throw transportError(null);
    return paged ? { items: payload.data, meta: payload.meta } : payload.data;
}
