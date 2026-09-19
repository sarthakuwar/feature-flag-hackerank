import { useMemo } from "react";
import { Link } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { useLiveEvents } from "../../shared/state/LiveContext.jsx";
import { getFlag, getRollout, getTargeting } from "../../shared/api/flags.js";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useDebouncedCallback from "../../shared/useDebouncedCallback.js";
import useResource from "../../shared/useResource.js";
import { formatEnumLabel } from "../../shared/format.js";
import RolloutCard from "./RolloutCard.jsx";
import { rolloutTargetLabel } from "./rollouts.js";
import useRolloutRefresh from "./useRolloutRefresh.js";

const LIVE_RELOAD_DELAY_MS = 300;
const NO_ROLLOUTS = [];

export default function RolloutDetailPage({ flagKey, rolloutId }) {
    const { environment } = useAppState();

    const rolloutRes = useResource(() => getRollout(flagKey, environment, rolloutId), [flagKey, environment, rolloutId]);
    const flagRes = useResource(() => getFlag(flagKey), [flagKey]);
    const targetingRes = useResource(() => getTargeting(flagKey, environment), [flagKey, environment]);

    const reloadAll = () => {
        rolloutRes.reload();
        flagRes.reload();
        targetingRes.reload();
    };
    const scheduleReload = useDebouncedCallback(reloadAll, LIVE_RELOAD_DELAY_MS);
    useLiveEvents((event) => {
        const affectsThis = event.type === "flag-changed" && event.flagKey === flagKey;
        if (event.type === "resync" || affectsThis) scheduleReload();
    });

    const rollout = rolloutRes.data;
    const tracked = useMemo(() => (rollout ? [rollout] : NO_ROLLOUTS), [rollout]);
    useRolloutRefresh(tracked, reloadAll);

    const back = (
        <Link to={`/flags/${encodeURIComponent(flagKey)}/rollouts`} className="back-link">
            ← Rollouts
        </Link>
    );

    if (rolloutRes.error?.status === 404 || flagRes.error?.status === 404) {
        return (
            <div className="page">
                {back}
                <div className="empty-state">
                    No rollout “{rolloutId}” exists for {flagKey} in {formatEnumLabel(environment)}. Rollouts belong to one environment; switch
                    environments to look elsewhere.
                </div>
            </div>
        );
    }

    const failure = rolloutRes.error || flagRes.error || targetingRes.error;
    if (!rollout || !flagRes.data || !targetingRes.data) {
        return (
            <div className="page">
                {back}
                {failure ? <ErrorBanner error={failure} onReload={reloadAll} /> : <div className="empty-state">Loading rollout…</div>}
            </div>
        );
    }

    const flag = flagRes.data;
    const strategies = flag.environments?.[environment]?.strategies ?? [];

    return (
        <div className="page">
            <nav aria-label="Breadcrumb" className="breadcrumb">
                <Link to="/flags">Flags</Link>
                <span aria-hidden="true">/</span>
                <Link to={`/flags/${encodeURIComponent(flagKey)}/rollouts`}>{flag.name}</Link>
                <span aria-hidden="true">/</span>
                <span aria-current="page">Rollout</span>
            </nav>

            <header className="page-header">
                <div>
                    <h1>Progressive rollout</h1>
                    <p className="page-subtitle">
                        {flag.name} in {formatEnumLabel(environment)}. To stop this rollout, use the Rollouts tab.
                    </p>
                </div>
            </header>

            <ErrorBanner error={failure} onReload={reloadAll} />

            <RolloutCard
                as="div"
                rollout={rollout}
                flag={flag}
                targetLabel={rolloutTargetLabel(rollout, targetingRes.data.rules, strategies)}
            />
        </div>
    );
}
