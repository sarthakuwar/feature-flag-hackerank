import { useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { updateFlag } from "../../shared/api/flags.js";
import Dialog from "../../shared/Dialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import DependsOnPicker from "./DependsOnPicker.jsx";

export default function EditFlagDialog({ flag, onClose, onSaved }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [name, setName] = useState(flag.name);
    const [description, setDescription] = useState(flag.description || "");
    const [owner, setOwner] = useState(flag.owner || "");
    const [dependsOn, setDependsOn] = useState(flag.dependsOn || []);

    const handleSubmit = async (event) => {
        event.preventDefault();
        const { ok } = await run(() =>
            updateFlag(flag.key, { name: name.trim(), description: description.trim(), owner: owner.trim(), dependsOn, changedBy: actingAs }),
        );
        if (ok) onSaved();
    };

    return (
        <Dialog title="Edit flag details" onClose={onClose}>
            <form onSubmit={handleSubmit} className="form">
                <label className="field">
                    <span>Name</span>
                    <input value={name} onChange={(event) => setName(event.target.value)} required data-autofocus />
                </label>
                <label className="field">
                    <span>Description</span>
                    <textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={3} />
                </label>
                <label className="field">
                    <span>Owner</span>
                    <input value={owner} onChange={(event) => setOwner(event.target.value)} required />
                </label>
                <DependsOnPicker ownKey={flag.key} selected={dependsOn} onChange={setDependsOn} />
                <ErrorBanner error={error} />
                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose}>
                        Cancel
                    </button>
                    <button type="submit" className="button button-primary" disabled={busy}>
                        {busy ? "Saving…" : "Save changes"}
                    </button>
                </div>
            </form>
        </Dialog>
    );
}
