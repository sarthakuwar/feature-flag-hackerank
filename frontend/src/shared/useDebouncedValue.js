import { useEffect, useState } from "react";

// Follows `value` after it has stopped changing for `delayMs`.
export default function useDebouncedValue(value, delayMs) {
    const [debounced, setDebounced] = useState(value);

    useEffect(() => {
        const timer = window.setTimeout(() => setDebounced(value), delayMs);
        return () => window.clearTimeout(timer);
    }, [value, delayMs]);

    return debounced;
}
