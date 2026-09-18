const baseUrl = import.meta.env.VITE_API_URL || "/api/v1";

export async function request(path, options = {}) {
    const response = await fetch(`${baseUrl}${path}`, {
        ...options,
        headers: {
            "Content-Type": "application/json",
            ...options.headers,
        },
    });
    if (response.status === 204) return null;
    const contentType = response.headers?.get?.("content-type") || "";
    const payload = contentType.includes("application/json") || !response.headers ? await response.json() : null;
    if (!response.ok) {
        const message = payload?.error?.message || `Feature Flag service returned ${response.status}.`;
        throw new Error(message);
    }
    if (!payload || !("data" in payload)) throw new Error("Feature Flag service returned an invalid response.");
    return payload.data;
}
