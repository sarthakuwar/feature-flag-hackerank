import { useId, useState } from "react";
import { parseKeyList } from "./format.js";

export default function TagInput({ values, onChange, label, placeholder, splitOnComma = true, disabled = false }) {
    const [draft, setDraft] = useState("");
    const inputId = useId();

    const commit = (text) => {
        const additions = splitOnComma ? parseKeyList(text) : [text.trim()].filter(Boolean);
        if (additions.length > 0) onChange(Array.from(new Set([...values, ...additions])));
        setDraft("");
    };

    const handleKeyDown = (event) => {
        if (event.key === "Enter" || (splitOnComma && event.key === ",")) {
            event.preventDefault();
            commit(draft);
        } else if (event.key === "Backspace" && draft === "" && values.length > 0) {
            onChange(values.slice(0, -1));
        }
    };

    const handlePaste = (event) => {
        const text = event.clipboardData.getData("text");
        if (splitOnComma && /[\n,]/.test(text)) {
            event.preventDefault();
            commit(`${draft}${text}`);
        }
    };

    return (
        <div className="tag-input" data-disabled={disabled}>
            {values.map((value) => (
                <span key={value} className="chip">
                    <span className="chip-text">{value}</span>
                    {!disabled && (
                        <button
                            type="button"
                            className="chip-remove"
                            aria-label={`Remove ${value}`}
                            onClick={() => onChange(values.filter((entry) => entry !== value))}
                        >
                            ×
                        </button>
                    )}
                </span>
            ))}
            <input
                id={inputId}
                className="tag-input-field"
                aria-label={label}
                value={draft}
                placeholder={values.length === 0 ? placeholder : ""}
                disabled={disabled}
                onChange={(event) => setDraft(event.target.value)}
                onKeyDown={handleKeyDown}
                onPaste={handlePaste}
                onBlur={() => commit(draft)}
            />
        </div>
    );
}
