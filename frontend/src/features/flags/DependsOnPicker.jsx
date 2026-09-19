import { useState } from "react";
import { listFlags } from "../../shared/api/flags.js";
import useDebouncedValue from "../../shared/useDebouncedValue.js";
import useResource from "../../shared/useResource.js";

const MAX_CHOICES = 200;
const SEARCH_DELAY_MS = 300;

export default function DependsOnPicker({ ownKey, selected, onChange, disabled }) {
    const [search, setSearch] = useState("");
    const query = useDebouncedValue(search.trim(), SEARCH_DELAY_MS);
    const { data: result, error } = useResource(() => listFlags({ lifecycle: "ACTIVE", limit: MAX_CHOICES, q: query }), [query]);
    const flags = result?.items ?? null;
    const choices = (flags || []).filter((flag) => flag.key !== ownKey);
    const truncated = result !== null && result.meta.total > result.meta.limit;

    const toggle = (key) => onChange(selected.includes(key) ? selected.filter((entry) => entry !== key) : [...selected, key]);

    return (
        <fieldset className="depends-picker" disabled={disabled}>
            <legend>Depends on</legend>
            <p className="hint">A flag that others depend on cannot be archived until they are archived first.</p>
            <input
                type="search"
                className="search-input"
                placeholder="Search flags by key, name, or owner"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                onKeyDown={(event) => event.key === "Enter" && event.preventDefault()}
                aria-label="Search flags to depend on"
            />
            {error ? (
                <p className="hint">Could not load flags: {error.message}</p>
            ) : flags === null ? (
                <p className="hint">Loading flags…</p>
            ) : choices.length === 0 ? (
                <p className="hint">{query ? "No active flags match your search." : "No other active flags to depend on."}</p>
            ) : (
                <div className="check-list">
                    {choices.map((flag) => (
                        <label key={flag.key} className="check">
                            <input type="checkbox" checked={selected.includes(flag.key)} onChange={() => toggle(flag.key)} />
                            <span>{flag.name}</span>
                            <code className="flag-key">{flag.key}</code>
                        </label>
                    ))}
                </div>
            )}
            {truncated && <p className="hint">Showing the first {MAX_CHOICES} flags. Search to find others.</p>}
        </fieldset>
    );
}
