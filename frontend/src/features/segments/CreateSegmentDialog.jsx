import { useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { createSegment } from "../../shared/api/flags.js";
import Dialog from "../../shared/Dialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import { formatEnumLabel, parseKeyList } from "../../shared/format.js";
import { CONTEXT_KINDS, USER_KIND } from "../../shared/targeting.js";

export default function CreateSegmentDialog({ onClose, onCreated }) {
    const { actingAs, environment } = useAppState();
    const { busy, error, run } = useAction();
    const [key, setKey] = useState("");
    const [name, setName] = useState("");
    const [description, setDescription] = useState("");
    const [contextKind, setContextKind] = useState(USER_KIND);
    const [included, setIncluded] = useState("");
    const [excluded, setExcluded] = useState("");

    const handleSubmit = async (event) => {
        event.preventDefault();
        const { ok, result } = await run(() =>
            createSegment(environment, {
                key: key.trim(),
                name: name.trim(),
                description: description.trim(),
                contextKind: contextKind.trim() || USER_KIND,
                included: parseKeyList(included),
                excluded: parseKeyList(excluded),
                rules: [],
                changedBy: actingAs,
            }),
        );
        if (ok) onCreated(result);
    };

    return (
        <Dialog title={`New segment in ${formatEnumLabel(environment)}`} onClose={onClose} wide>
            <form className="form" onSubmit={handleSubmit}>
                <div className="form-grid">
                    <label className="field">
                        <span>Key</span>
                        <input
                            value={key}
                            onChange={(event) => setKey(event.target.value)}
                            placeholder="beta-testers"
                            pattern="[a-z][a-z0-9\-]*"
                            title="Lowercase letters, numbers, and hyphens, starting with a letter."
                            required
                            data-autofocus
                        />
                    </label>
                    <label className="field">
                        <span>Name</span>
                        <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Beta testers" required />
                    </label>
                </div>
                <label className="field">
                    <span>Description</span>
                    <textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={2} />
                </label>
                <label className="field">
                    <span>Context kind</span>
                    <input list="segment-context-kinds" value={contextKind} onChange={(event) => setContextKind(event.target.value)} required />
                    <datalist id="segment-context-kinds">
                        {CONTEXT_KINDS.map((kind) => (
                            <option key={kind} value={kind} />
                        ))}
                    </datalist>
                    <span className="hint">The kind of context this segment groups, such as user or organization.</span>
                </label>
                <div className="form-grid">
                    <label className="field">
                        <span>Included keys</span>
                        <textarea rows={3} value={included} onChange={(event) => setIncluded(event.target.value)} placeholder="One per line or comma separated" />
                    </label>
                    <label className="field">
                        <span>Excluded keys</span>
                        <textarea rows={3} value={excluded} onChange={(event) => setExcluded(event.target.value)} placeholder="One per line or comma separated" />
                    </label>
                </div>
                <p className="hint">Rules that add members by attribute can be added after the segment is created.</p>
                <ErrorBanner error={error} />
                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose}>
                        Cancel
                    </button>
                    <button type="submit" className="button button-primary" disabled={busy}>
                        {busy ? "Creating…" : "Create segment"}
                    </button>
                </div>
            </form>
        </Dialog>
    );
}
