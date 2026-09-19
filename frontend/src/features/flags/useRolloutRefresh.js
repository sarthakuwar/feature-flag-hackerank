import { useEffect, useRef } from "react";

const GRACE_MS = 1000;
const MIN_DELAY_MS = 2000;
const MAX_TIMER_MS = 2 ** 31 - 1;

// A stage advance is time-driven and sends no live event, so the percentage and current stage shown for an active rollout go
// stale. Refetches once the earliest upcoming stage change (or completion) of `rollouts` has passed; each refetch re-arms it.
export default function useRolloutRefresh(rollouts, refresh) {
    const refreshRef = useRef(refresh);
    refreshRef.current = refresh;

    useEffect(() => {
        const times = rollouts
            .filter((rollout) => rollout.status === "ACTIVE")
            .map((rollout) => rollout.nextStageAt || rollout.completesAt)
            .filter(Boolean)
            .map((iso) => new Date(iso).getTime());
        if (times.length === 0) return undefined;
        const delay = Math.min(MAX_TIMER_MS, Math.max(MIN_DELAY_MS, Math.min(...times) - Date.now() + GRACE_MS));
        const timer = window.setTimeout(() => refreshRef.current(), delay);
        return () => window.clearTimeout(timer);
    }, [rollouts]);
}
