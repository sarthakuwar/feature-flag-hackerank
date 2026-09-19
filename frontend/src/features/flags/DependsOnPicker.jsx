import { listFlags } from "../../shared/api/flags.js";
import useResource from "../../shared/useResource.js";

export default function DependsOnPicker({ ownKey, selected, onChange, disabled }) {
    const { data: flags, error } = useResource(() => listFlags("ACTIVE"), []);
    const choices = (flags || []).filter((flag) => flag.key !== ownKey);

    const toggle = (key) => onChange(selected.includes(key) ? selected.filter((entry) => entry !== key) : [...selected, key]);

    return (
        <fieldset className="depends-picker" disabled={disabled}>
            <legend>Depends on</legend>
            <p className="hint">A flag that others depend on cannot be archived until they are archived first.</p>
            {error ? (
                <p className="hint">Could not load flags: {error.message}</p>
            ) : flags === null ? (
                <p className="hint">Loading flags…</p>
            ) : choices.length === 0 ? (
                <p className="hint">No other active flags to depend on.</p>
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
        </fieldset>
    );
}
