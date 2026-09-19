import { useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { stopRollout } from "../../shared/api/flags.js";
import ConfirmDialog from "../../shared/ConfirmDialog.jsx";
import useAction from "../../shared/useAction.js";
import { formatDateTime, formatDuration, formatEnumLabel, formatRelativeTime, variationColor } from "../../shared/format.js";
import StartRolloutDialog from "./StartRolloutDialog.jsx";

const FILTERS = [
    { value: "", label: "All" },
    { value: "ACTIVE", label: "Active" },
    { value: "COMPLETED", label: "Completed" },
    { value: "STOPPED", label: "Stopped" },
];

function stateOf(rollout) {
    if (rollout.status === "ACTIVE" && new Date(rollout.startAt).getTime() > Date.now()) return { label: "Scheduled", tone: "info" };
    if (rollout.status === "ACTIVE") return { label: "In progress", tone: "positive" };
    if (rollout.status === "COMPLETED") return { label: "Completed", tone: "positive" };
    return { label: "Stopped", tone: "muted" };
}

function StopDialog({ rollout, flag, environment, onClose, onStopped }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [variationKey, setVariationKey] = useState(rollout.fromVariationKey);

    const confirm = async () => {
        const { ok } = await run(() => stopRollout(flag.key, environment, rollout.id, variationKey, actingAs));
        if (ok) onStopped();
    };

    return (
        <ConfirmDialog
            title="Stop rollout"
            confirmLabel="Stop rollout"
            busyLabel="Stopping…"
            busy={busy}
            error={error}
            onConfirm={confirm}
            onClose={onClose}
        >
            <p>
                Stopping ends the ramp immediately. Choose what the {rollout.ruleId ? "rule" : "strategy"} serves from now on.
            </p>
            <label className="field">
                <span>Serve</span>
                <select value={variationKey} onChange={(event) => setVariationKey(event.target.value)}>
                    {flag.variations.map((variation) => (
                        <option key={variation.key} value={variation.key}>
                            {variation.name}
                            {variation.key === rollout.fromVariationKey ? " — roll back" : variation.key === rollout.toVariationKey ? " — finish now" : ""}
                        </option>
                    ))}
                </select>
            </label>
        </ConfirmDialog>
    );
}

function RolloutCard({ rollout, flag, targetLabel, readOnly, onStop }) {
    const state = stateOf(rollout);
    const active = rollout.status === "ACTIVE";
    const nameOf = (key) => flag.variations.find((variation) => variation.key === key)?.name || key;
    const fromIndex = flag.variations.findIndex((variation) => variation.key === rollout.fromVariationKey);
    const toIndex = flag.variations.findIndex((variation) => variation.key === rollout.toVariationKey);
    const startAt = new Date(rollout.startAt).getTime();

    return (
        <li className="rollout-card" data-status={rollout.status}>
            <div className="rollout-head">
                <div className="rollout-title">
                    <strong>{targetLabel}</strong>
                    <span className="badge" data-tone={state.tone}>
                        {state.label}
                    </span>
                </div>
                {active && !readOnly && (
                    <button type="button" className="button button-danger" onClick={onStop}>
                        Stop rollout
                    </button>
                )}
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
        </li>
    );
}

export default function RolloutsTab({ flag, environment, envConfig, rules, rollouts, readOnly, onChanged }) {
    const [filter, setFilter] = useState("");
    const [starting, setStarting] = useState(false);
    const [stopping, setStopping] = useState(null);

    const active = rollouts.find((rollout) => rollout.status === "ACTIVE");
    const hasExperiment = envConfig.strategies.some((strategy) => strategy.type === "EXPERIMENT_SPLIT");

    const targets = [
        ...rules
            .filter((rule) => rule.enabled && Object.keys(rule.rolloutWeights || {}).length === 0)
            .map((rule) => ({
                id: `rule:${rule.id}`,
                rawId: rule.id,
                kind: "rule",
                fromVariationKey: rule.servedVariationKey,
                label: `Rule: ${rule.description || "Untitled rule"}`,
            })),
        ...envConfig.strategies
            .filter((strategy) => strategy.type !== "EXPERIMENT_SPLIT")
            .map((strategy) => ({
                id: `strategy:${strategy.id}`,
                rawId: strategy.id,
                kind: "strategy",
                fromVariationKey: strategy.servedVariationKey,
                label: `Strategy: ${formatEnumLabel(strategy.type)} → ${strategy.servedVariationKey}`,
            })),
    ];

    const labelFor = (rollout) => {
        if (rollout.ruleId) {
            const rule = rules.find((entry) => entry.id === rollout.ruleId);
            return `Rule: ${rule ? rule.description || "Untitled rule" : "deleted rule"}`;
        }
        const strategy = envConfig.strategies.find((entry) => entry.id === rollout.strategyId);
        return `Strategy: ${strategy ? formatEnumLabel(strategy.type) : "removed strategy"}`;
    };

    let blocker = null;
    if (readOnly) blocker = "Restore this flag to start a rollout.";
    else if (active) blocker = "Only one rollout can run at a time in an environment. Stop or wait for the current one.";
    else if (hasExperiment) blocker = "An experiment split is running in this environment, and a rollout can’t run alongside it.";
    else if (targets.length === 0) blocker = "Add an enabled targeting rule that serves a single variation first, then ramp it here.";

    const visible = rollouts.filter((rollout) => !filter || rollout.status === filter);

    return (
        <section className="panel" aria-labelledby="rollouts-heading">
            <div className="panel-header">
                <div>
                    <h2 id="rollouts-heading">Progressive rollouts</h2>
                    <p className="hint">Ramp a rule from one variation to another on a schedule, in {formatEnumLabel(environment)}.</p>
                </div>
                <button type="button" className="button button-primary" disabled={blocker !== null} onClick={() => setStarting(true)}>
                    Start rollout
                </button>
            </div>

            {blocker && <p className="hint">{blocker}</p>}

            {rollouts.length > 0 && (
                <div className="segmented" role="group" aria-label="Filter rollouts by status">
                    {FILTERS.map((option) => (
                        <button key={option.value || "all"} type="button" aria-pressed={filter === option.value} onClick={() => setFilter(option.value)}>
                            {option.label}
                        </button>
                    ))}
                </div>
            )}

            {rollouts.length === 0 ? (
                <div className="empty-state compact">No rollouts yet in {formatEnumLabel(environment)}.</div>
            ) : visible.length === 0 ? (
                <div className="empty-state compact">No {formatEnumLabel(filter).toLowerCase()} rollouts.</div>
            ) : (
                <ul className="rollout-list">
                    {visible.map((rollout) => (
                        <RolloutCard
                            key={rollout.id}
                            rollout={rollout}
                            flag={flag}
                            targetLabel={labelFor(rollout)}
                            readOnly={readOnly}
                            onStop={() => setStopping(rollout)}
                        />
                    ))}
                </ul>
            )}

            {starting && (
                <StartRolloutDialog
                    flag={flag}
                    environment={environment}
                    targets={targets}
                    onClose={() => setStarting(false)}
                    onStarted={() => {
                        setStarting(false);
                        onChanged();
                    }}
                />
            )}

            {stopping && (
                <StopDialog
                    rollout={stopping}
                    flag={flag}
                    environment={environment}
                    onClose={() => setStopping(null)}
                    onStopped={() => {
                        setStopping(null);
                        onChanged();
                    }}
                />
            )}
        </section>
    );
}
