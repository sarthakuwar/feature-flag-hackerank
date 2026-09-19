import { useRef, useState } from "react";
import { formatEnumLabel, variationColor } from "../../shared/format.js";
import StrategyForm from "./StrategyForm.jsx";

function summarizeStrategy(strategy) {
    if (strategy.type === "USER_ID") return `${strategy.userIds.length} user ID${strategy.userIds.length === 1 ? "" : "s"}`;
    if (strategy.type === "ATTRIBUTE") return `${strategy.attribute} = ${strategy.attributeValue}`;
    if (strategy.type === "PERCENTAGE_ROLLOUT") return `${strategy.percentage}% of traffic`;
    if (strategy.type === "EXPERIMENT_SPLIT") {
        return Object.entries(strategy.variationWeights || {})
            .map(([key, weight]) => `${key} ${weight}%`)
            .join(", ");
    }
    return "";
}

export function toStrategyRequest(strategy) {
    return {
        id: strategy.id,
        type: strategy.type,
        userIds: strategy.userIds,
        attribute: strategy.attribute,
        attributeValue: strategy.attributeValue,
        percentage: strategy.percentage,
        servedVariationKey: strategy.servedVariationKey,
        variationWeights: strategy.variationWeights,
        banditAlgorithm: strategy.banditAlgorithm,
        epsilon: strategy.epsilon,
    };
}

export default function StrategiesPanel({ flag, strategies, activeRollout, readOnly, busy, onSave, onOpenMetrics, onOpenRollouts }) {
    const [editingId, setEditingId] = useState(null);
    const [adding, setAdding] = useState(false);
    const headingRef = useRef(null);

    const save = async (next) => {
        const ok = await onSave(next.map(toStrategyRequest));
        if (ok) headingRef.current?.focus();
        return ok;
    };

    const submit = async (payload) => {
        const next = editingId ? strategies.map((entry) => (entry.id === editingId ? payload : entry)) : [...strategies, payload];
        const ok = await save(next);
        if (ok) {
            setEditingId(null);
            setAdding(false);
        }
    };

    const move = (index, direction) => {
        const reordered = [...strategies];
        const target = index + direction;
        if (target < 0 || target >= reordered.length) return;
        [reordered[index], reordered[target]] = [reordered[target], reordered[index]];
        save(reordered);
    };

    return (
        <section className="panel" aria-labelledby="strategies-heading">
            <div>
                <h2 id="strategies-heading" ref={headingRef} tabIndex={-1}>
                    Default strategies
                </h2>
                <p className="hint">
                    Checked after targeting rules, in priority order. Use these for a simple percentage rollout or an experiment split. Anyone left over gets
                    the off variation.
                </p>
            </div>

            {strategies.length === 0 && !adding && <p className="hint">No strategies. Everyone the rules don’t match gets the off variation.</p>}

            <ol className="strategy-list">
                {strategies.map((strategy, index) => {
                    const ramping = activeRollout?.strategyId === strategy.id;
                    if (strategy.type === "EXPERIMENT_SPLIT") {
                        return (
                            <li key={strategy.id} className="strategy-item">
                                <div className="strategy-summary">
                                    <span className="badge">Experiment split</span>
                                    <span>{summarizeStrategy(strategy)}</span>
                                    {strategy.banditAlgorithm !== "NONE" && (
                                        <span className="badge" data-tone="positive">
                                            {formatEnumLabel(strategy.banditAlgorithm)}
                                        </span>
                                    )}
                                </div>
                                <div className="strategy-actions">
                                    <button type="button" className="button button-ghost" onClick={onOpenMetrics}>
                                        Manage in Metrics tab
                                    </button>
                                </div>
                            </li>
                        );
                    }
                    if (editingId === strategy.id) {
                        return (
                            <li key={strategy.id} className="strategy-item">
                                <StrategyForm
                                    strategy={strategy}
                                    variations={flag.variations}
                                    submitting={busy}
                                    onSubmit={submit}
                                    onCancel={() => setEditingId(null)}
                                />
                            </li>
                        );
                    }
                    return (
                        <li key={strategy.id} className="strategy-item">
                            <div className="strategy-summary">
                                <span className="badge">{formatEnumLabel(strategy.type)}</span>
                                <span>{summarizeStrategy(strategy)}</span>
                                <span className="strategy-arrow" aria-hidden="true">
                                    →
                                </span>
                                <span
                                    className="variation-dot"
                                    aria-hidden="true"
                                    style={{ background: variationColor(flag.variations.findIndex((variation) => variation.key === strategy.servedVariationKey)) }}
                                />
                                <strong>{strategy.servedVariationKey}</strong>
                                {ramping && (
                                    <button type="button" className="badge badge-button" data-tone="positive" onClick={onOpenRollouts}>
                                        Ramping · {activeRollout.currentPercentage}%
                                    </button>
                                )}
                            </div>
                            {!readOnly && (
                                <div className="strategy-actions">
                                    <button
                                        type="button"
                                        className="button button-ghost"
                                        disabled={busy || index === 0}
                                        onClick={() => move(index, -1)}
                                        aria-label={`Move strategy ${index + 1} up`}
                                    >
                                        ↑
                                    </button>
                                    <button
                                        type="button"
                                        className="button button-ghost"
                                        disabled={busy || index === strategies.length - 1}
                                        onClick={() => move(index, 1)}
                                        aria-label={`Move strategy ${index + 1} down`}
                                    >
                                        ↓
                                    </button>
                                    <button type="button" className="button button-ghost" disabled={busy} onClick={() => setEditingId(strategy.id)}>
                                        Edit
                                    </button>
                                    <button
                                        type="button"
                                        className="button button-ghost"
                                        disabled={busy || ramping}
                                        onClick={() => save(strategies.filter((entry) => entry.id !== strategy.id))}
                                    >
                                        Remove
                                    </button>
                                </div>
                            )}
                        </li>
                    );
                })}
            </ol>

            {!readOnly &&
                (adding ? (
                    <StrategyForm variations={flag.variations} submitting={busy} onSubmit={submit} onCancel={() => setAdding(false)} />
                ) : (
                    <div>
                        <button type="button" className="button" onClick={() => setAdding(true)}>
                            Add strategy
                        </button>
                    </div>
                ))}
        </section>
    );
}
