import { useId } from "react";
import { Link } from "./router.jsx";
import TagInput from "./TagInput.jsx";
import { APPLICATION_KIND, CONTEXT_KINDS, OPERATORS, operatorLabel, usesAttribute } from "./targeting.js";

const NEGATABLE = new Set(["IN", "STARTS_WITH", "ENDS_WITH", "CONTAINS", "MATCHES", "IN_SEGMENT", "APP_VERSION_SUPPORTED"]);

function operatorOptions(clause, allowSegment) {
    const options = [];
    OPERATORS.filter((operator) => allowSegment || operator.value !== "IN_SEGMENT").forEach((operator) => {
        options.push({ id: operator.value, label: operator.label, group: operator.group });
        if (NEGATABLE.has(operator.value)) {
            options.push({ id: `!${operator.value}`, label: operatorLabel(operator.value, true), group: operator.group });
        }
    });
    const currentId = `${clause.negate ? "!" : ""}${clause.operator}`;
    if (!options.some((option) => option.id === currentId)) {
        options.push({ id: currentId, label: operatorLabel(clause.operator, clause.negate), group: "Current" });
    }
    return options;
}

function groupOptions(options) {
    const groups = [];
    options.forEach((option) => {
        const group = groups.find((entry) => entry.name === option.group);
        if (group) group.options.push(option);
        else groups.push({ name: option.group, options: [option] });
    });
    return groups;
}

export default function ClauseEditor({ clause, index, segments, allowSegment, disabled, onChange, onRemove, removable }) {
    const listId = useId();
    const groups = groupOptions(operatorOptions(clause, allowSegment));
    const segmentChoices = segments.filter((segment) => segment.contextKind === clause.contextKind);

    const changeOperator = (encoded) => {
        const negate = encoded.startsWith("!");
        const operator = negate ? encoded.slice(1) : encoded;
        const next = { ...clause, operator, negate, values: [] };
        if (operator === "APP_VERSION_SUPPORTED") next.contextKind = APPLICATION_KIND;
        onChange(next);
    };

    const toggleSegment = (key) => {
        const values = clause.values.includes(key) ? clause.values.filter((value) => value !== key) : [...clause.values, key];
        onChange({ ...clause, values });
    };

    return (
        <div className="clause">
            <div className="clause-fields">
                <label className="field">
                    <span>Context kind</span>
                    <input
                        list={listId}
                        value={clause.contextKind}
                        disabled={disabled || clause.operator === "APP_VERSION_SUPPORTED"}
                        onChange={(event) => onChange({ ...clause, contextKind: event.target.value.trim(), values: clause.operator === "IN_SEGMENT" ? [] : clause.values })}
                        aria-label={`Condition ${index + 1} context kind`}
                        required
                    />
                    <datalist id={listId}>
                        {CONTEXT_KINDS.map((kind) => (
                            <option key={kind} value={kind} />
                        ))}
                    </datalist>
                </label>

                {usesAttribute(clause.operator) && (
                    <label className="field">
                        <span>Attribute</span>
                        <input
                            value={clause.attribute}
                            disabled={disabled}
                            placeholder="country"
                            onChange={(event) => onChange({ ...clause, attribute: event.target.value })}
                            aria-label={`Condition ${index + 1} attribute`}
                            required
                        />
                    </label>
                )}

                <label className="field">
                    <span>Operator</span>
                    <select
                        value={`${clause.negate ? "!" : ""}${clause.operator}`}
                        disabled={disabled}
                        onChange={(event) => changeOperator(event.target.value)}
                        aria-label={`Condition ${index + 1} operator`}
                    >
                        {groups.map((group) => (
                            <optgroup key={group.name} label={group.name}>
                                {group.options.map((option) => (
                                    <option key={option.id} value={option.id}>
                                        {option.label}
                                    </option>
                                ))}
                            </optgroup>
                        ))}
                    </select>
                </label>

                {removable && (
                    <button
                        type="button"
                        className="button button-ghost clause-remove"
                        disabled={disabled}
                        onClick={onRemove}
                        aria-label={`Remove condition ${index + 1}`}
                    >
                        Remove
                    </button>
                )}
            </div>

            {clause.operator === "IN_SEGMENT" ? (
                <fieldset className="segment-choices" disabled={disabled}>
                    <legend>Segments</legend>
                    {segmentChoices.length === 0 ? (
                        <p className="hint">
                            No segments target {clause.contextKind || "this context kind"} in this environment. <Link to="/segments">Create one</Link> first.
                        </p>
                    ) : (
                        segmentChoices.map((segment) => (
                            <label key={segment.key} className="check">
                                <input type="checkbox" checked={clause.values.includes(segment.key)} onChange={() => toggleSegment(segment.key)} />
                                <span>{segment.name}</span>
                                <code className="flag-key">{segment.key}</code>
                            </label>
                        ))
                    )}
                    {clause.values
                        .filter((key) => !segmentChoices.some((segment) => segment.key === key))
                        .map((key) => (
                            <label key={key} className="check">
                                <input type="checkbox" checked onChange={() => toggleSegment(key)} />
                                <span>Unknown segment</span>
                                <code className="flag-key">{key}</code>
                            </label>
                        ))}
                </fieldset>
            ) : clause.operator === "APP_VERSION_SUPPORTED" ? (
                <p className="hint">
                    Matches app versions at or above the minimum set for the application. Manage minimums under <Link to="/applications">Applications</Link>.
                </p>
            ) : (
                <div className="field">
                    <span>Values</span>
                    <TagInput
                        values={clause.values}
                        disabled={disabled}
                        splitOnComma={clause.operator !== "MATCHES"}
                        label={`Condition ${index + 1} values`}
                        placeholder="Type a value and press Enter"
                        onChange={(values) => onChange({ ...clause, values })}
                    />
                </div>
            )}
        </div>
    );
}
