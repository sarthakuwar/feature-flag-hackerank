import { useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { createFlag } from "../../shared/api/flags.js";
import Dialog from "../../shared/Dialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import DependsOnPicker from "./DependsOnPicker.jsx";

const KEY_PATTERN = "[a-z][a-z0-9\\-]{1,63}";

function defaultVariationsFor(type) {
    if (type === "BOOLEAN") {
        return [
            { key: "true", name: "True", value: "true" },
            { key: "false", name: "False", value: "false" },
        ];
    }
    return [
        { key: "", name: "", value: "" },
        { key: "", name: "", value: "" },
    ];
}

export default function CreateFlagDialog({ onClose, onCreated }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [key, setKey] = useState("");
    const [name, setName] = useState("");
    const [description, setDescription] = useState("");
    const [owner, setOwner] = useState(actingAs);
    const [type, setType] = useState("BOOLEAN");
    const [variations, setVariations] = useState(() => defaultVariationsFor("BOOLEAN"));
    const [dependsOn, setDependsOn] = useState([]);

    const handleTypeChange = (nextType) => {
        setType(nextType);
        setVariations(defaultVariationsFor(nextType));
    };

    const updateVariation = (index, field, value) => {
        setVariations((prev) => prev.map((variation, i) => (i === index ? { ...variation, [field]: value } : variation)));
    };

    const addVariationRow = () => setVariations((prev) => [...prev, { key: "", name: "", value: "" }]);
    const removeVariationRow = (index) => setVariations((prev) => prev.filter((_, i) => i !== index));

    const handleSubmit = async (event) => {
        event.preventDefault();
        const { ok, result } = await run(() =>
            createFlag({
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
                dependsOn,
                changedBy: actingAs,
            }),
        );
        if (ok) onCreated(result);
    };

    return (
        <Dialog title="New flag" onClose={onClose} wide>
            <form onSubmit={handleSubmit} className="form">
                <div className="form-grid">
                    <label className="field">
                        <span>Key</span>
                        <input
                            value={key}
                            onChange={(event) => setKey(event.target.value)}
                            placeholder="checkout-redesign"
                            pattern={KEY_PATTERN}
                            title="Lowercase letters, numbers, and hyphens, starting with a letter."
                            required
                            data-autofocus
                        />
                    </label>
                    <label className="field">
                        <span>Name</span>
                        <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Checkout redesign" required />
                    </label>
                </div>
                <label className="field">
                    <span>Description</span>
                    <textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={2} />
                </label>
                <div className="form-grid">
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
                </div>

                <fieldset className="field-group">
                    <legend>Variations</legend>
                    {type === "BOOLEAN" && <p className="hint">Boolean flags always have exactly two variations, true and false.</p>}
                    <div className="variation-rows">
                        {variations.map((variation, index) => (
                            <div className="variation-row" key={index}>
                                <input
                                    placeholder="key"
                                    aria-label={`Variation ${index + 1} key`}
                                    value={variation.key}
                                    onChange={(event) => updateVariation(index, "key", event.target.value)}
                                    disabled={type === "BOOLEAN"}
                                    required
                                />
                                <input
                                    placeholder="name"
                                    aria-label={`Variation ${index + 1} name`}
                                    value={variation.name}
                                    onChange={(event) => updateVariation(index, "name", event.target.value)}
                                    disabled={type === "BOOLEAN"}
                                    required
                                />
                                <input
                                    placeholder="value"
                                    aria-label={`Variation ${index + 1} value`}
                                    value={variation.value}
                                    onChange={(event) => updateVariation(index, "value", event.target.value)}
                                    disabled={type === "BOOLEAN"}
                                    required
                                />
                                {type === "MULTIVARIATE" && variations.length > 2 && (
                                    <button
                                        type="button"
                                        className="button button-ghost"
                                        onClick={() => removeVariationRow(index)}
                                        aria-label={`Remove variation ${index + 1}`}
                                    >
                                        ×
                                    </button>
                                )}
                            </div>
                        ))}
                    </div>
                    {type === "MULTIVARIATE" && (
                        <button type="button" className="button button-ghost" onClick={addVariationRow}>
                            + Add variation
                        </button>
                    )}
                </fieldset>

                <DependsOnPicker selected={dependsOn} onChange={setDependsOn} />

                <ErrorBanner error={error} />

                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose}>
                        Cancel
                    </button>
                    <button type="submit" className="button button-primary" disabled={busy}>
                        {busy ? "Creating…" : "Create flag"}
                    </button>
                </div>
            </form>
        </Dialog>
    );
}
