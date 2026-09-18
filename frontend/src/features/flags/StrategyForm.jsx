import { useState } from "react";

const TYPE_OPTIONS = [
    { value: "USER_ID", label: "User ID" },
    { value: "ATTRIBUTE", label: "Attribute" },
    { value: "PERCENTAGE_ROLLOUT", label: "Percentage rollout" },
];

function toFormState(strategy) {
    return {
        type: strategy?.type || "PERCENTAGE_ROLLOUT",
        userIds: strategy?.userIds?.join(", ") || "",
        attribute: strategy?.attribute || "",
        attributeValue: strategy?.attributeValue || "",
        percentage: strategy?.percentage ?? 0,
        servedVariationKey: strategy?.servedVariationKey || "",
    };
}

export default function StrategyForm({ strategy, variations, onSubmit, onCancel, submitting }) {
    const [form, setForm] = useState(() => toFormState(strategy));

    const update = (field, value) => setForm((prev) => ({ ...prev, [field]: value }));

    const handleSubmit = (event) => {
        event.preventDefault();
        onSubmit({
            id: strategy?.id,
            type: form.type,
            userIds: form.type === "USER_ID" ? form.userIds.split(",").map((id) => id.trim()).filter(Boolean) : [],
            attribute: form.type === "ATTRIBUTE" ? form.attribute.trim() : null,
            attributeValue: form.type === "ATTRIBUTE" ? form.attributeValue.trim() : null,
            percentage: form.type === "PERCENTAGE_ROLLOUT" ? Number(form.percentage) : 0,
            servedVariationKey: form.servedVariationKey,
        });
    };

    return (
        <form className="strategy-form" onSubmit={handleSubmit}>
            <label className="field">
                <span>Rule type</span>
                <select value={form.type} onChange={(event) => update("type", event.target.value)}>
                    {TYPE_OPTIONS.map((option) => (
                        <option key={option.value} value={option.value}>
                            {option.label}
                        </option>
                    ))}
                </select>
            </label>

            {form.type === "USER_ID" && (
                <label className="field">
                    <span>User IDs (comma separated)</span>
                    <input value={form.userIds} onChange={(event) => update("userIds", event.target.value)} placeholder="user-1, user-2" required />
                </label>
            )}

            {form.type === "ATTRIBUTE" && (
                <>
                    <label className="field">
                        <span>Attribute</span>
                        <input value={form.attribute} onChange={(event) => update("attribute", event.target.value)} placeholder="plan" required />
                    </label>
                    <label className="field">
                        <span>Value</span>
                        <input value={form.attributeValue} onChange={(event) => update("attributeValue", event.target.value)} placeholder="enterprise" required />
                    </label>
                </>
            )}

            {form.type === "PERCENTAGE_ROLLOUT" && (
                <label className="field">
                    <span>Percentage of traffic ({form.percentage}%)</span>
                    <input type="range" min="0" max="100" value={form.percentage} onChange={(event) => update("percentage", event.target.value)} />
                </label>
            )}

            <label className="field">
                <span>Serves variation</span>
                <select value={form.servedVariationKey} onChange={(event) => update("servedVariationKey", event.target.value)} required>
                    <option value="" disabled>
                        Choose a variation
                    </option>
                    {variations.map((variation) => (
                        <option key={variation.key} value={variation.key}>
                            {variation.name}
                        </option>
                    ))}
                </select>
            </label>

            <div className="dialog-actions">
                <button type="button" className="button button-ghost" onClick={onCancel}>
                    Cancel
                </button>
                <button type="submit" className="button button-primary" disabled={submitting}>
                    {submitting ? "Saving…" : strategy ? "Save changes" : "Add rule"}
                </button>
            </div>
        </form>
    );
}
