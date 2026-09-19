import { useCallback, useEffect, useRef, useState } from "react";

// `copied` turns true for `durationMs` after `flash()`; a second call restarts the countdown and unmounting cancels it.
export default function useCopied(durationMs = 2000) {
    const [copied, setCopied] = useState(false);
    const timerRef = useRef(null);

    useEffect(() => () => window.clearTimeout(timerRef.current), []);

    const flash = useCallback(() => {
        window.clearTimeout(timerRef.current);
        setCopied(true);
        timerRef.current = window.setTimeout(() => setCopied(false), durationMs);
    }, [durationMs]);

    return [copied, flash];
}
