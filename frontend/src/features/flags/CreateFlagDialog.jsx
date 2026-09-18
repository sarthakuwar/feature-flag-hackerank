import { useEffect, useRef, useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { createFlag } from "../../shared/api/flags.js";

const KEY_PATTERN = "[a-z][a-z0-9-]{1,63}";
const FOCUSABLE_SELECTOR = "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])";

function defaultVariationsFor(type) {
    if (type === "BOOLEAN") {
        return [
            { key: "true", name: "True", value: "true" },
            { key: "false", name: "False", value: "false" },
        ];
    }
    return [{ key: "", name: "", value: "" }];
}

export default function CreateFlagDialog({ onClose, onCreated }) {
    const { actingAs } = useAppState();
    const dialogRef = useRef(null);
    const [key, setKey] = useState("");
    const [name, setName] = useState("");
    const [description, setDescription] = useState("");
    const [owner, setOwner] = useState(actingAs);
    const [type, setType] = useState("BOOLEAN");
    const [variations, setVariations] = useState(() => defaultVariationsFor("BOOLEAN"));
    const [error, setError] = useState(null);
    const [submitting, setSubmitting] = useState(false);

    const handleTypeChange = (nextType) => {
        setType(nextType);
        setVariations(defaultVariationsFor(nextType));
    };

    const updateVariation = (index, field, value) => {
        setVariations((prev) => prev.map((variation, i) => (i === index ? { ...variation, [field]: value } : variation)));
    };

    const addVariationRow = () => setVariations((prev) => [...prev, { key: "", name: "", value: "" }]);
    const removeVariationRow = (index) => setVariations((prev) => prev.filter((_, i) => i !== index));

    useEffect(() => {
        const handleKeyDown = (event) => {
            if (event.key === "Escape") {
                event.preventDefault();
                onClose();
                return;
            }
            if (event.key !== "Tab" || !dialogRef.current) return;
            const focusable = Array.from(dialogRef.current.querySelectorAll(FOCUSABLE_SELECTOR));
            if (focusable.length === 0) return;
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        };
        document.addEventListener("keydown", handleKeyDown);
        return () => document.removeEventListener("keydown", handleKeyDown);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const handleSubmit = async (event) => {
        event.preventDefault();
        setError(null);
        setSubmitting(true);
        try {
            const flag = await createFlag({
                key: key.trim(),
                name: name.trim(),
                description: description.trim(),
                type,
                owner: owner.trim(),
                variations: variations.map((variation) => ({
                    key: variation.key.trim(),
                    name: variation.name.trim(),
                    value: variation.value,
                })),
                dependsOn: [],
                changedBy: actingAs,
            });
            onCreated(flag);
        } catch (err) {
            setError(err.message);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="dialog-overlay" role="presentation" onClick={onClose}>
            <div
                ref={dialogRef}
                className="dialog"
                role="dialog"
                aria-modal="true"
                aria-labelledby="create-flag-title"
                onClick={(event) => event.stopPropagation()}
            >
                <h2 id="create-flag-title">New flag</h2>
                <form onSubmit={handleSubmit} className="form">
                    <label className="field">
                        <span>Key</span>
                        <input
                            value={key}
                            onChange={(event) => setKey(event.target.value)}
                            placeholder="checkout-redesign"
                            pattern={KEY_PATTERN}
                            title="Lowercase letters, numbers, and hyphens, starting with a letter."
                            required
                            autoFocus
                        />
                    </label>
                    <label className="field">
                        <span>Name</span>
                        <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Checkout redesign" required />
                    </label>
                    <label className="field">
                        <span>Description</span>
                        <textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={2} />
                    </label>
                    <label className="field">
                        <span>Owner</span>
                        <input value={owner} onChange={(event) => setOwner(event.target.value)} required />
                    </label>
                    <label className="field">
                        <span>Type</span>
                        <select value={type} onChange={(event) => handleTypeChange(event.target.value)}>
                            <option value="BOOLEAN">Boolean</option>
                            <option value="MULTIVARIATE">Multivariate</option>
                        </select>
                    </label>

                    <div className="field">
                        <span>Variations</span>
                        <div className="variation-rows">
                            {variations.map((variation, index) => (
                                <div className="variation-row" key={index}>
                                    <input
                                        placeholder="key"
                                        value={variation.key}
                                        onChange={(event) => updateVariation(index, "key", event.target.value)}
                                        disabled={type === "BOOLEAN"}
                                        required
                                    />
                                    <input
                                        placeholder="name"
                                        value={variation.name}
                                        onChange={(event) => updateVariation(index, "name", event.target.value)}
                                        disabled={type === "BOOLEAN"}
                                        required
                                    />
                                    <input
                                        placeholder="value"
                                        value={variation.value}
                                        onChange={(event) => updateVariation(index, "value", event.target.value)}
                                        disabled={type === "BOOLEAN"}
                                        required
                                    />
                                    {type === "MULTIVARIATE" && variations.length > 1 && (
                                        <button type="button" className="button button-ghost" onClick={() => removeVariationRow(index)} aria-label="Remove variation">
                                            ×
                                        </button>
                                    )}
                                </div>
                            ))}
                        </div>
                        {type === "MULTIVARIATE" && (
                            <button type="button" className="button button-ghost" onClick={addVariationRow}>
                                Add variation
                            </button>
                        )}
                    </div>

                    {error && <div className="banner banner-error">{error}</div>}

                    <div className="dialog-actions">
                        <button type="button" className="button" onClick={onClose}>
                            Cancel
                        </button>
                        <button type="submit" className="button button-primary" disabled={submitting}>
                            {submitting ? "Creating…" : "Create flag"}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
