import { useCallback, useEffect, useRef } from "react";

// Returns a stable function that runs `callback` once, `delayMs` after its last call, so a burst of triggers reloads once.
export default function useDebouncedCallback(callback, delayMs) {
    const callbackRef = useRef(callback);
    callbackRef.current = callback;
    const timerRef = useRef(null);

    useEffect(() => () => window.clearTimeout(timerRef.current), []);

    return useCallback(() => {
        window.clearTimeout(timerRef.current);
        timerRef.current = window.setTimeout(() => callbackRef.current(), delayMs);
    }, [delayMs]);
}
