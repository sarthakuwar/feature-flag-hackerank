import { Link } from "../../shared/router.jsx";
import { formatDateTime, formatDuration, formatRelativeTime, variationColor } from "../../shared/format.js";

function stateOf(rollout) {
    if (rollout.status === "ACTIVE" && new Date(rollout.startAt).getTime() > Date.now()) return { label: "Scheduled", tone: "info" };
    if (rollout.status === "ACTIVE") return { label: "In progress", tone: "positive" };
    if (rollout.status === "COMPLETED") return { label: "Completed", tone: "positive" };
    return { label: "Stopped", tone: "muted" };
}

// `detailHref` links a list entry to its own page; `onStop` is set only where the viewer may stop an active rollout.
export default function RolloutCard({ rollout, flag, targetLabel, detailHref, onStop, as: Container = "li" }) {
    const state = stateOf(rollout);
    const active = rollout.status === "ACTIVE";
    const nameOf = (key) => flag.variations.find((variation) => variation.key === key)?.name || key;
    const fromIndex = flag.variations.findIndex((variation) => variation.key === rollout.fromVariationKey);
    const toIndex = flag.variations.findIndex((variation) => variation.key === rollout.toVariationKey);
    const startAt = new Date(rollout.startAt).getTime();

    return (
        <Container className="rollout-card" data-status={rollout.status}>
            <div className="rollout-head">
                <div className="rollout-title">
                    <strong>{targetLabel}</strong>
                    <span className="badge" data-tone={state.tone}>
                        {state.label}
                    </span>
                </div>
                <div className="bulk-actions">
                    {detailHref && (
                        <Link to={detailHref} className="button button-ghost">
                            Details
                        </Link>
                    )}
                    {active && onStop && (
                        <button type="button" className="button button-danger" onClick={onStop}>
                            Stop rollout
                        </button>
                    )}
                </div>
            </div>

            <p className="rollout-route">
                <span className="variation-dot" style={{ background: variationColor(fromIndex) }} aria-hidden="true" />
                {nameOf(rollout.fromVariationKey)}
                <span className="strategy-arrow" aria-hidden="true">
                    →
                </span>
                <span className="variation-dot" style={{ background: variationColor(toIndex) }} aria-hidden="true" />
                {nameOf(rollout.toVariationKey)}
            </p>

            <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={rollout.currentPercentage} aria-label="Rollout progress">
                <div className="progress-fill" style={{ width: `${rollout.currentPercentage}%`, background: variationColor(toIndex) }} />
            </div>
            <p className="meta-line rollout-meta">
                <strong>{rollout.currentPercentage}%</strong> on {nameOf(rollout.toVariationKey)}
                {active && rollout.nextStageAt && <> · next stage {formatRelativeTime(rollout.nextStageAt)}</>}
                {active && rollout.completesAt && <> · completes {formatDateTime(rollout.completesAt)}</>}
                {!active && rollout.endedAt && (
                    <>
                        {" "}
                        · {rollout.status === "COMPLETED" ? "finished" : "stopped"} {formatRelativeTime(rollout.endedAt)}
                        {rollout.endedBy && rollout.status === "STOPPED" && <> by {rollout.endedBy}</>}
                        {rollout.resolvedVariationKey && <> · now serves {nameOf(rollout.resolvedVariationKey)}</>}
                    </>
                )}
            </p>

            <details className="rollout-stages">
                <summary>Schedule ({rollout.stages.length} stages)</summary>
                <table className="history-table">
                    <caption className="sr-only">Rollout stages</caption>
                    <thead>
                        <tr>
                            <th scope="col">Stage</th>
                            <th scope="col">Share</th>
                            <th scope="col">Starts</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rollout.stages.map((stage, index) => (
                            <tr key={index} data-current={active && index === rollout.currentStageIndex}>
                                <td>{index + 1}</td>
                                <td>{stage.percentage}%</td>
                                <td>
                                    {stage.offsetMinutes === 0 ? "At start" : `+${formatDuration(stage.offsetMinutes)}`}
                                    <span className="hint"> · {formatDateTime(new Date(startAt + stage.offsetMinutes * 60000).toISOString())}</span>
                                    {active && index === rollout.currentStageIndex && (
                                        <span className="badge" data-tone="positive">
                                            Current
                                        </span>
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </details>

            <p className="hint">
                Started by {rollout.startedBy} {formatRelativeTime(rollout.createdAt)}
            </p>
        </Container>
    );
}
