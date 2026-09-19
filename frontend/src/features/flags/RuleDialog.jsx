import { useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { createRule, updateRule } from "../../shared/api/flags.js";
import ClauseListEditor from "../../shared/ClauseListEditor.jsx";
import Dialog from "../../shared/Dialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import { emptyClause, evenWeights, toClauseRequest } from "../../shared/targeting.js";
import { variationColor } from "../../shared/format.js";

function initialState(rule, variations) {
    if (!rule) {
        return {
            description: "",
            enabled: true,
            clauses: [emptyClause()],
            serveMode: "variation",
            servedVariationKey: variations[0]?.key || "",
            weights: evenWeights(variations),
            includeInExperiment: false,
        };
    }
    const servesRollout = Object.keys(rule.rolloutWeights || {}).length > 0;
    return {
        description: rule.description || "",
        enabled: rule.enabled,
        clauses: rule.clauses.map((clause) => ({ ...clause, attribute: clause.attribute || "" })),
        serveMode: servesRollout ? "rollout" : "variation",
        servedVariationKey: rule.servedVariationKey || variations[0]?.key || "",
        weights: servesRollout ? { ...evenWeightsZero(variations), ...rule.rolloutWeights } : evenWeights(variations),
        includeInExperiment: Boolean(rule.includeInExperiment),
    };
}

function evenWeightsZero(variations) {
    return Object.fromEntries(variations.map((variation) => [variation.key, 0]));
}

export default function RuleDialog({ rule, flag, environment, segments, hasActiveRollout, onClose, onSaved }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [form, setForm] = useState(() => initialState(rule, flag.variations));

    const update = (patch) => setForm((previous) => ({ ...previous, ...patch }));
    const weightTotal = Object.values(form.weights).reduce((sum, value) => sum + Number(value || 0), 0);
    const rolloutInvalid = form.serveMode === "rollout" && weightTotal !== 100;
    const serveLocked = hasActiveRollout;

    const handleSubmit = async (event) => {
        event.preventDefault();
        const body = {
            description: form.description.trim(),
            enabled: form.enabled,
            clauses: form.clauses.map(toClauseRequest),
            changedBy: actingAs,
        };
        if (form.serveMode === "variation") {
            body.servedVariationKey = form.servedVariationKey;
        } else {
            body.rolloutWeights = Object.fromEntries(Object.entries(form.weights).map(([key, value]) => [key, Number(value || 0)]));
            body.includeInExperiment = form.includeInExperiment;
        }
        const { ok } = await run(() => (rule ? updateRule(flag.key, environment, rule.id, body) : createRule(flag.key, environment, body)));
        if (ok) onSaved();
    };

    return (
        <Dialog title={rule ? "Edit rule" : "Add rule"} onClose={onClose} wide closeOnOverlay={false}>
            <form className="form" onSubmit={handleSubmit}>
                <label className="field">
                    <span>Description</span>
                    <input
                        value={form.description}
                        maxLength={200}
                        placeholder="Beta testers get the new checkout"
                        onChange={(event) => update({ description: event.target.value })}
                        data-autofocus
                    />
                </label>

                <fieldset className="field-group">
                    <legend>If</legend>
                    <ClauseListEditor clauses={form.clauses} segments={segments} disabled={busy} onChange={(clauses) => update({ clauses })} />
                </fieldset>

                <fieldset className="field-group">
                    <legend>Then serve</legend>
                    {serveLocked && (
                        <p className="hint">This rule is being ramped by a progressive rollout, so what it serves can’t change until the rollout ends.</p>
                    )}
                    <div className="segmented" role="group" aria-label="What the rule serves">
                        <button type="button" aria-pressed={form.serveMode === "variation"} disabled={serveLocked} onClick={() => update({ serveMode: "variation" })}>
                            A variation
                        </button>
                        <button type="button" aria-pressed={form.serveMode === "rollout"} disabled={serveLocked} onClick={() => update({ serveMode: "rollout" })}>
                            Percentage rollout
                        </button>
                    </div>

                    {form.serveMode === "variation" ? (
                        <label className="field">
                            <span>Variation</span>
                            <select
                                value={form.servedVariationKey}
                                disabled={serveLocked}
                                onChange={(event) => update({ servedVariationKey: event.target.value })}
                                required
                            >
                                {flag.variations.map((variation) => (
                                    <option key={variation.key} value={variation.key}>
                                        {variation.name}
                                    </option>
                                ))}
                            </select>
                        </label>
                    ) : (
                        <>
                            <div className="weights-row">
                                {flag.variations.map((variation, index) => (
                                    <label key={variation.key} className="field weight-field">
                                        <span>
                                            <span className="variation-dot" style={{ background: variationColor(index) }} aria-hidden="true" /> {variation.name} %
                                        </span>
                                        <input
                                            type="number"
                                            min="0"
                                            max="100"
                                            value={form.weights[variation.key] ?? 0}
                                            onChange={(event) => update({ weights: { ...form.weights, [variation.key]: event.target.value } })}
                                        />
                                    </label>
                                ))}
                            </div>
                            <p className="hint" data-invalid={rolloutInvalid}>
                                Total: {weightTotal}%{rolloutInvalid ? " — must equal 100" : ""}{" "}
                                <button type="button" className="link-button" onClick={() => update({ weights: evenWeights(flag.variations) })}>
                                    Split evenly
                                </button>
                            </p>
                            <label className="check">
                                <input
                                    type="checkbox"
                                    checked={form.includeInExperiment}
                                    onChange={(event) => update({ includeInExperiment: event.target.checked })}
                                />
                                <span>Count this traffic in experiment metrics</span>
                            </label>
                        </>
                    )}
                </fieldset>

                <label className="switch">
                    <input type="checkbox" checked={form.enabled} onChange={(event) => update({ enabled: event.target.checked })} />
                    <span>{form.enabled ? "Rule is on" : "Rule is off"}</span>
                </label>

                <ErrorBanner error={error} />

                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose}>
                        Cancel
                    </button>
                    <button type="submit" className="button button-primary" disabled={busy || rolloutInvalid}>
                        {busy ? "Saving…" : rule ? "Save rule" : "Add rule"}
                    </button>
                </div>
            </form>
        </Dialog>
    );
}
