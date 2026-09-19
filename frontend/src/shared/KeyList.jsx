import { useMemo, useState } from "react";
import { pluralize } from "./format.js";

const PAGE_SIZE = 60;

// A searchable, paged list of keys (user IDs, segment members). Lists can hold tens of thousands of entries,
// so only one page is in the DOM at a time.
export default function KeyList({ keys, label, emptyText, disabled, onRemove }) {
    const [search, setSearch] = useState("");
    const [visible, setVisible] = useState(PAGE_SIZE);

    const filtered = useMemo(() => {
        const term = search.trim().toLowerCase();
        return term ? keys.filter((key) => key.toLowerCase().includes(term)) : keys;
    }, [keys, search]);

    if (keys.length === 0) return <p className="hint">{emptyText}</p>;

    return (
        <div className="key-list">
            {keys.length > PAGE_SIZE && (
                <input
                    type="search"
                    className="search-input"
                    placeholder={`Search ${pluralize(keys.length, "key")}`}
                    aria-label={`Search ${label}`}
                    value={search}
                    onChange={(event) => {
                        setSearch(event.target.value);
                        setVisible(PAGE_SIZE);
                    }}
                />
            )}
            <ul className="chip-list" aria-label={label}>
                {filtered.slice(0, visible).map((key) => (
                    <li key={key} className="chip">
                        <span className="chip-text">{key}</span>
                        {!disabled && (
                            <button type="button" className="chip-remove" aria-label={`Remove ${key}`} onClick={() => onRemove(key)}>
                                ×
                            </button>
                        )}
                    </li>
                ))}
            </ul>
            {filtered.length === 0 && <p className="hint">No keys match your search.</p>}
            {filtered.length > visible && (
                <button type="button" className="button button-ghost" onClick={() => setVisible((count) => count + PAGE_SIZE)}>
                    Show {Math.min(PAGE_SIZE, filtered.length - visible)} more of {filtered.length - visible} remaining
                </button>
            )}
        </div>
    );
}
