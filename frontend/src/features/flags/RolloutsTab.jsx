import { useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { stopRollout } from "../../shared/api/flags.js";
import ConfirmDialog from "../../shared/ConfirmDialog.jsx";
import useAction from "../../shared/useAction.js";
import { formatEnumLabel } from "../../shared/format.js";
import RolloutCard from "./RolloutCard.jsx";
import StartRolloutDialog from "./StartRolloutDialog.jsx";
import { rolloutTargetLabel } from "./rollouts.js";
import useRolloutRefresh from "./useRolloutRefresh.js";

const FILTERS = [
    { value: "", label: "All" },
    { value: "ACTIVE", label: "Active" },
    { value: "COMPLETED", label: "Completed" },
    { value: "STOPPED", label: "Stopped" },
];

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

export default function RolloutsTab({ flag, environment, envConfig, rules, rollouts, readOnly, onChanged, onRefresh }) {
    useRolloutRefresh(rollouts, onRefresh);
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
                            targetLabel={rolloutTargetLabel(rollout, rules, envConfig.strategies)}
                            detailHref={`/flags/${encodeURIComponent(flag.key)}/rollouts/${encodeURIComponent(rollout.id)}`}
                            onStop={readOnly ? undefined : () => setStopping(rollout)}
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
