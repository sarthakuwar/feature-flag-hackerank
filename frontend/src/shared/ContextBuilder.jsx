import { CONTEXT_KINDS, USER_KIND } from "./targeting.js";

export function emptyEvaluationContext() {
    return { userId: "test-user", attributes: [{ key: "", value: "" }], extraContexts: [] };
}

function attributeMap(rows) {
    return Object.fromEntries(rows.filter((row) => row.key.trim()).map((row) => [row.key.trim(), row.value]));
}

export function toEvaluationRequest(context) {
    const contexts = {};
    context.extraContexts
        .filter((entry) => entry.kind.trim() && entry.kind.trim() !== USER_KIND)
        .forEach((entry) => {
            contexts[entry.kind.trim()] = { key: entry.key.trim() || null, attributes: attributeMap(entry.attributes) };
        });
    return { userId: context.userId.trim() || null, attributes: attributeMap(context.attributes), contexts };
}

function AttributeRows({ rows, onChange, label }) {
    const update = (index, field, value) => onChange(rows.map((row, position) => (position === index ? { ...row, [field]: value } : row)));

    return (
        <div className="field">
            <span>{label}</span>
            <div className="variation-rows">
                {rows.map((row, index) => (
                    <div className="variation-row" key={index}>
                        <input
                            placeholder="name (e.g. country)"
                            value={row.key}
                            onChange={(event) => update(index, "key", event.target.value)}
                            aria-label={`${label} ${index + 1} name`}
                        />
                        <input
                            placeholder="value"
                            value={row.value}
                            onChange={(event) => update(index, "value", event.target.value)}
                            aria-label={`${label} ${index + 1} value`}
                        />
                        {rows.length > 1 && (
                            <button
                                type="button"
                                className="button button-ghost"
                                onClick={() => onChange(rows.filter((_, position) => position !== index))}
                                aria-label={`Remove ${label.toLowerCase()} ${index + 1}`}
                            >
                                ×
                            </button>
                        )}
                    </div>
                ))}
            </div>
            <button type="button" className="button button-ghost" onClick={() => onChange([...rows, { key: "", value: "" }])}>
                + Add attribute
            </button>
        </div>
    );
}

export default function ContextBuilder({ value, onChange }) {
    const setExtra = (index, patch) =>
        onChange({ ...value, extraContexts: value.extraContexts.map((entry, position) => (position === index ? { ...entry, ...patch } : entry)) });

    return (
        <div className="form">
            <label className="field">
                <span>User key</span>
                <input value={value.userId} onChange={(event) => onChange({ ...value, userId: event.target.value })} placeholder="user-123" />
            </label>
            <AttributeRows label="User attributes" rows={value.attributes} onChange={(attributes) => onChange({ ...value, attributes })} />

            {value.extraContexts.map((entry, index) => (
                <fieldset key={index} className="extra-context">
                    <legend>Additional context {index + 1}</legend>
                    <div className="variation-row">
                        <label className="field">
                            <span>Kind</span>
                            <input
                                list="evaluation-context-kinds"
                                value={entry.kind}
                                onChange={(event) => setExtra(index, { kind: event.target.value })}
                                placeholder="ld_application"
                            />
                        </label>
                        <label className="field">
                            <span>Key</span>
                            <input value={entry.key} onChange={(event) => setExtra(index, { key: event.target.value })} placeholder="com.featureflag.mobile" />
                        </label>
                    </div>
                    <AttributeRows label={`Context ${index + 1} attributes`} rows={entry.attributes} onChange={(attributes) => setExtra(index, { attributes })} />
                    <button
                        type="button"
                        className="button button-ghost"
                        onClick={() => onChange({ ...value, extraContexts: value.extraContexts.filter((_, position) => position !== index) })}
                    >
                        Remove context
                    </button>
                </fieldset>
            ))}
            <datalist id="evaluation-context-kinds">
                {CONTEXT_KINDS.filter((kind) => kind !== USER_KIND).map((kind) => (
                    <option key={kind} value={kind} />
                ))}
            </datalist>

            <div>
                <button
                    type="button"
                    className="button button-ghost"
                    onClick={() =>
                        onChange({
                            ...value,
                            extraContexts: [...value.extraContexts, { kind: "", key: "", attributes: [{ key: "", value: "" }] }],
                        })
                    }
                >
                    + Add another context kind
                </button>
            </div>
        </div>
    );
}
