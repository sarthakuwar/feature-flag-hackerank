import { useRef, useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { addVariation, removeVariation, updateVariation } from "../../shared/api/flags.js";
import ConfirmDialog from "../../shared/ConfirmDialog.jsx";
import Dialog from "../../shared/Dialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import { formatEnumLabel, variationColor } from "../../shared/format.js";

const PAYLOAD_TYPES = ["STRING", "JSON", "CSV", "NUMBER"];

function VariationDialog({ flag, variation, locked, onClose, onSaved }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [form, setForm] = useState({
        key: variation?.key || "",
        name: variation?.name || "",
        value: variation?.value || "",
        weight: variation?.weight ?? "",
        payloadType: variation?.payloadType || "",
        payloadValue: variation?.payloadValue || "",
    });
    const update = (patch) => setForm((previous) => ({ ...previous, ...patch }));
    const hasWeights = flag.variations.some((entry) => entry.weight != null);

    const handleSubmit = async (event) => {
        event.preventDefault();
        const body = {
            name: form.name.trim(),
            value: form.value,
            weight: form.weight === "" ? null : Number(form.weight),
            payloadType: form.payloadType || null,
            payloadValue: form.payloadType ? form.payloadValue : null,
            changedBy: actingAs,
        };
        const { ok } = await run(() => (variation ? updateVariation(flag.key, variation.key, body) : addVariation(flag.key, { ...body, key: form.key.trim() })));
        if (ok) onSaved();
    };

    return (
        <Dialog title={variation ? `Edit ${variation.name}` : "Add variation"} onClose={onClose}>
            <form className="form" onSubmit={handleSubmit}>
                {!variation && (
                    <label className="field">
                        <span>Key</span>
                        <input value={form.key} onChange={(event) => update({ key: event.target.value })} placeholder="new-hero" required data-autofocus />
                    </label>
                )}
                <label className="field">
                    <span>Name</span>
                    <input value={form.name} onChange={(event) => update({ name: event.target.value })} required data-autofocus={variation ? true : undefined} />
                </label>
                <label className="field">
                    <span>Value</span>
                    <input value={form.value} disabled={locked} onChange={(event) => update({ value: event.target.value })} required />
                </label>
                <label className="field">
                    <span>Weight (optional)</span>
                    <input
                        type="number"
                        min="0"
                        max="100"
                        value={form.weight}
                        onChange={(event) => update({ weight: event.target.value })}
                        placeholder={hasWeights ? "Keep current" : "Even split"}
                    />
                    <span className="hint">Weights across all variations must total 100.</span>
                </label>
                <div className="form-grid">
                    <label className="field">
                        <span>Payload type</span>
                        <select value={form.payloadType} onChange={(event) => update({ payloadType: event.target.value })}>
                            <option value="">None</option>
                            {PAYLOAD_TYPES.map((type) => (
                                <option key={type} value={type}>
                                    {formatEnumLabel(type)}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label className="field">
                        <span>Payload</span>
                        <textarea
                            rows={2}
                            value={form.payloadValue}
                            disabled={!form.payloadType}
                            onChange={(event) => update({ payloadValue: event.target.value })}
                            placeholder={form.payloadType === "JSON" ? '{"theme": "dark"}' : ""}
                        />
                    </label>
                </div>
                <ErrorBanner error={error} />
                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose}>
                        Cancel
                    </button>
                    <button type="submit" className="button button-primary" disabled={busy}>
                        {busy ? "Saving…" : variation ? "Save variation" : "Add variation"}
                    </button>
                </div>
            </form>
        </Dialog>
    );
}

export default function VariationsTab({ flag, readOnly, onChanged }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [editing, setEditing] = useState(null);
    const [removing, setRemoving] = useState(null);
    const headingRef = useRef(null);
    const isBoolean = flag.type === "BOOLEAN";

    const confirmRemove = async () => {
        const { ok } = await run(() => removeVariation(flag.key, removing.key, actingAs));
        if (ok) {
            setRemoving(null);
            onChanged();
            headingRef.current?.focus();
        }
    };

    return (
        <section className="panel" aria-labelledby="variations-heading">
            <div className="panel-header">
                <div>
                    <h2 id="variations-heading" ref={headingRef} tabIndex={-1}>
                        Variations
                    </h2>
                    {isBoolean && <p className="hint">Boolean flags always have exactly two variations, true and false.</p>}
                </div>
                {!readOnly && !isBoolean && (
                    <button type="button" className="button button-primary" onClick={() => setEditing({})}>
                        Add variation
                    </button>
                )}
            </div>

            <ul className="variation-list">
                {flag.variations.map((variation, index) => (
                    <li key={variation.key} className="variation-item">
                        <div>
                            <span className="variation-dot" style={{ background: variationColor(index) }} aria-hidden="true" />
                            <strong>{variation.name}</strong>
                            <code className="flag-key">{variation.key}</code>
                            {variation.weight != null && <span className="badge">{variation.weight}% weight</span>}
                            {variation.payloadType && <span className="badge">{formatEnumLabel(variation.payloadType)} payload</span>}
                        </div>
                        <span className="variation-value" title={variation.value}>
                            {variation.value}
                        </span>
                        {!readOnly && (
                            <div className="row-actions">
                                <button type="button" className="button button-ghost" onClick={() => setEditing(variation)}>
                                    Edit
                                </button>
                                {!isBoolean && (
                                    <button type="button" className="button button-ghost" onClick={() => setRemoving(variation)}>
                                        Remove
                                    </button>
                                )}
                            </div>
                        )}
                    </li>
                ))}
            </ul>

            {editing && (
                <VariationDialog
                    flag={flag}
                    variation={editing.key ? editing : null}
                    locked={isBoolean}
                    onClose={() => setEditing(null)}
                    onSaved={() => {
                        setEditing(null);
                        onChanged();
                    }}
                />
            )}

            {removing && (
                <ConfirmDialog
                    title="Remove variation"
                    confirmLabel="Remove variation"
                    busyLabel="Removing…"
                    busy={busy}
                    error={error}
                    onConfirm={confirmRemove}
                    onClose={() => setRemoving(null)}
                >
                    <p>
                        Remove <strong>{removing.name}</strong>? A variation that a rule, target, strategy, or rollout still uses can’t be removed.
                    </p>
                </ConfirmDialog>
            )}
        </section>
    );
}
