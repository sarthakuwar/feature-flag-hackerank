import { baseUrl } from "./client.js";

const EVENT_TYPES = ["flag-changed", "segment-changed", "application-changed"];

export function openEventStream(environment, { onEvent, onStatus }) {
    const source = new EventSource(`${baseUrl}/stream/${encodeURIComponent(environment)}`);

    source.onopen = () => onStatus("live");
    source.onerror = () => onStatus(source.readyState === EventSource.CLOSED ? "offline" : "connecting");

    EVENT_TYPES.forEach((type) => {
        source.addEventListener(type, (message) => {
            try {
                onEvent({ type, ...JSON.parse(message.data) });
            } catch {
                // A malformed event carries nothing to act on; the next valid one still refreshes the page.
            }
        });
    });

    return () => source.close();
}
