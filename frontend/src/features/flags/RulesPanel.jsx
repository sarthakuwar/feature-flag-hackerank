import { useRef, useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { deleteRule, moveRule, reorderRules, setRuleEnabled } from "../../shared/api/flags.js";
import ClauseSummary from "../../shared/ClauseSummary.jsx";
import ConfirmDialog from "../../shared/ConfirmDialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import { formatEnumLabel, variationColor } from "../../shared/format.js";
import { ruleCategory } from "../../shared/targeting.js";
import RuleDialog from "./RuleDialog.jsx";
import WeightBar from "./WeightBar.jsx";

const CATEGORY_TONES = { SEGMENT: "segment", MOBILE: "mobile", CUSTOM: "custom" };

function ServeSummary({ rule, variations }) {
    const weights = rule.rolloutWeights || {};
    if (Object.keys(weights).length > 0) {
        return (
            <div className="rule-serve-rollout">
                <WeightBar weights={weights} variations={variations} />
                {rule.includeInExperiment && (
                    <span className="badge" data-tone="positive">
                        Counts in experiment
                    </span>
                )}
            </div>
        );
    }
    const index = variations.findIndex((variation) => variation.key === rule.servedVariationKey);
    return (
        <span className="rule-serve-fixed">
            <span className="variation-dot" style={{ background: variationColor(index) }} aria-hidden="true" />
            <strong>{variations[index]?.name || rule.servedVariationKey}</strong>
        </span>
    );
}

export default function RulesPanel({ flag, environment, targeting, segments, activeRollout, readOnly, onChanged, onOpenRollouts }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [editing, setEditing] = useState(null);
    const [deleting, setDeleting] = useState(null);
    const [dragId, setDragId] = useState(null);
    const [overId, setOverId] = useState(null);
    const headingRef = useRef(null);
    const rules = targeting.rules;
    const locked = readOnly || busy;

    const afterWrite = async (action) => {
        const { ok } = await run(action);
        if (ok) onChanged();
        return ok;
    };

    const move = (rule, direction) => afterWrite(() => moveRule(flag.key, environment, rule.id, direction, actingAs));
    const toggle = (rule) => afterWrite(() => setRuleEnabled(flag.key, environment, rule.id, !rule.enabled, actingAs));

    const confirmDelete = async () => {
        const ok = await afterWrite(() => deleteRule(flag.key, environment, deleting.id, actingAs));
        if (ok) {
            setDeleting(null);
            headingRef.current?.focus();
        }
    };

    const endDrag = () => {
        setDragId(null);
        setOverId(null);
    };

    const drop = (targetId) => {
        if (!dragId || dragId === targetId) return endDrag();
        const ids = rules.map((rule) => rule.id).filter((id) => id !== dragId);
        ids.splice(ids.indexOf(targetId), 0, dragId);
        endDrag();
        afterWrite(() => reorderRules(flag.key, environment, ids, actingAs));
    };

    return (
        <section className="panel" aria-labelledby="rules-heading">
            <div className="panel-header">
                <div>
                    <h2 id="rules-heading" ref={headingRef} tabIndex={-1}>
                        Targeting rules
                    </h2>
                    <p className="hint">Checked top to bottom after individual targets. The first enabled rule that matches decides the variation.</p>
                </div>
                {!readOnly && (
                    <button type="button" className="button button-primary" disabled={busy} onClick={() => setEditing({})}>
                        Add rule
                    </button>
                )}
            </div>

            {!deleting && <ErrorBanner error={error} />}

            {rules.length === 0 ? (
                <div className="empty-state compact">
                    No targeting rules yet. Add one to serve a variation to a segment, an attribute match, or a mobile app version.
                </div>
            ) : (
                <ol className="rule-list">
                    {rules.map((rule, index) => {
                        const category = ruleCategory(rule);
                        const ramping = activeRollout?.ruleId === rule.id;
                        return (
                            <li
                                key={rule.id}
                                className="rule-card"
                                data-disabled={!rule.enabled}
                                data-dragging={dragId === rule.id}
                                data-over={overId === rule.id && dragId !== rule.id}
                                draggable={!locked}
                                onDragStart={(event) => {
                                    event.dataTransfer.effectAllowed = "move";
                                    setDragId(rule.id);
                                }}
                                onDragOver={(event) => {
                                    if (!dragId) return;
                                    event.preventDefault();
                                    setOverId(rule.id);
                                }}
                                onDrop={(event) => {
                                    event.preventDefault();
                                    drop(rule.id);
                                }}
                                onDragEnd={endDrag}
                            >
                                <div className="rule-head">
                                    {!readOnly && (
                                        <span className="drag-handle" aria-hidden="true" title="Drag to reorder">
                                            ⋮⋮
                                        </span>
                                    )}
                                    <span className="rule-index">{index + 1}</span>
                                    <div className="rule-title">
                                        <strong>{rule.description || "Untitled rule"}</strong>
                                        <span className="badge" data-tone={CATEGORY_TONES[category]}>
                                            {formatEnumLabel(category)}
                                        </span>
                                        {!rule.enabled && (
                                            <span className="badge" data-tone="muted">
                                                Off
                                            </span>
                                        )}
                                        {ramping && (
                                            <button type="button" className="badge badge-button" data-tone="positive" onClick={onOpenRollouts}>
                                                Ramping · {activeRollout.currentPercentage}%
                                            </button>
                                        )}
                                    </div>
                                    <label className="switch">
                                        <input
                                            type="checkbox"
                                            checked={rule.enabled}
                                            disabled={locked}
                                            onChange={() => toggle(rule)}
                                            aria-label={`${rule.enabled ? "Turn off" : "Turn on"} rule ${index + 1}`}
                                        />
                                    </label>
                                </div>

                                <div className="rule-body">
                                    <div className="rule-row">
                                        <span className="rule-label">If</span>
                                        <div className="rule-clauses">
                                            {rule.clauses.map((clause, position) => (
                                                <div key={position} className="rule-clause">
                                                    {position > 0 && <span className="clause-and">and</span>}
                                                    <ClauseSummary clause={clause} />
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                    <div className="rule-row">
                                        <span className="rule-label">Serve</span>
                                        <ServeSummary rule={rule} variations={flag.variations} />
                                    </div>
                                </div>

                                {!readOnly && (
                                    <div className="rule-actions">
                                        <button
                                            type="button"
                                            className="button button-ghost"
                                            disabled={locked || index === 0}
                                            onClick={() => move(rule, "UP")}
                                            aria-label={`Move rule ${index + 1} up`}
                                        >
                                            ↑
                                        </button>
                                        <button
                                            type="button"
                                            className="button button-ghost"
                                            disabled={locked || index === rules.length - 1}
                                            onClick={() => move(rule, "DOWN")}
                                            aria-label={`Move rule ${index + 1} down`}
                                        >
                                            ↓
                                        </button>
                                        <button type="button" className="button button-ghost" disabled={locked} onClick={() => setEditing(rule)}>
                                            Edit
                                        </button>
                                        <button type="button" className="button button-ghost" disabled={locked || ramping} onClick={() => setDeleting(rule)}>
                                            Delete
                                        </button>
                                    </div>
                                )}
                            </li>
                        );
                    })}
                </ol>
            )}

            <p className="hint">
                {targeting.limits.ruleCount.toLocaleString("en")} of {targeting.limits.maxRules.toLocaleString("en")} rules ·{" "}
                {targeting.limits.valueCount.toLocaleString("en")} of {targeting.limits.maxValues.toLocaleString("en")} condition values used
            </p>

            {editing && (
                <RuleDialog
                    rule={editing.id ? editing : null}
                    flag={flag}
                    environment={environment}
                    segments={segments}
                    hasActiveRollout={Boolean(editing.id && activeRollout?.ruleId === editing.id)}
                    onClose={() => setEditing(null)}
                    onSaved={() => {
                        setEditing(null);
                        onChanged();
                    }}
                />
            )}

            {deleting && (
                <ConfirmDialog
                    title="Delete rule"
                    confirmLabel="Delete rule"
                    busyLabel="Deleting…"
                    busy={busy}
                    error={error}
                    onConfirm={confirmDelete}
                    onClose={() => setDeleting(null)}
                >
                    <p>
                        Delete <strong>{deleting.description || "this rule"}</strong>? Contexts it matched fall through to the next rule, then to the flag’s
                        strategies and off variation.
                    </p>
                </ConfirmDialog>
            )}
        </section>
    );
}
