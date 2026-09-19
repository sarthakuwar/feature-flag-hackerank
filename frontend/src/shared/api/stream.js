import { baseUrl } from "./client.js";

const EVENT_TYPES = ["flag-changed", "segment-changed", "application-changed"];
const MIN_RETRY_MS = 1000;
const MAX_RETRY_MS = 30000;

// Opens the one stream for an environment and keeps it alive. `onResync` fires after a reconnect, because events sent while
// the connection was down are gone and every open page has to reload what it shows.
export function openEventStream(environment, { onEvent, onStatus, onResync }) {
    let source = null;
    let retryTimer = null;
    let failures = 0;
    let dropped = false;

    const connect = () => {
        if (dropped) onStatus("connecting");
        const current = new EventSource(`${baseUrl}/stream/${encodeURIComponent(environment)}`);
        source = current;

        current.onopen = () => {
            failures = 0;
            onStatus("live");
            if (dropped) {
                dropped = false;
                onResync();
            }
        };

        current.onerror = () => {
            dropped = true;
            if (current.readyState !== EventSource.CLOSED) {
                onStatus("connecting");
                return;
            }
            onStatus("offline");
            current.close();
            const delay = Math.min(MAX_RETRY_MS, MIN_RETRY_MS * 2 ** failures);
            failures += 1;
            retryTimer = window.setTimeout(connect, delay);
        };

        EVENT_TYPES.forEach((type) => {
            current.addEventListener(type, (message) => {
                try {
                    onEvent({ type, ...JSON.parse(message.data) });
                } catch {
                    // A malformed event carries nothing to act on; the next valid one still refreshes the page.
                }
            });
        });
    };

    connect();

    return () => {
        window.clearTimeout(retryTimer);
        source.close();
    };
}
