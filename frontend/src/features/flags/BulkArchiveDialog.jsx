import { useEffect, useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { bulkArchive, validateBulkArchive } from "../../shared/api/flags.js";
import Dialog from "../../shared/Dialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import { pluralize } from "../../shared/format.js";

export default function BulkArchiveDialog({ keys: requestedKeys, onClose, onDone }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [keys] = useState(requestedKeys);
    const [blocked, setBlocked] = useState(null);
    const [validationError, setValidationError] = useState(null);

    useEffect(() => {
        let cancelled = false;
        validateBulkArchive(keys).then(
            (result) => !cancelled && setBlocked(result.blocked),
            (err) => !cancelled && setValidationError(err),
        );
        return () => {
            cancelled = true;
        };
    }, [keys]);

    const blockedKeys = blocked ? Object.keys(blocked) : [];
    const archivableCount = keys.length - blockedKeys.length;

    const confirm = async () => {
        const { ok, result } = await run(() => bulkArchive(keys, actingAs));
        if (ok) onDone(result);
    };

    return (
        <Dialog title={`Archive ${pluralize(keys.length, "flag")}`} onClose={onClose}>
            <div className="form">
                {blocked === null && !validationError && <p className="hint">Checking dependencies…</p>}
                <ErrorBanner error={validationError} />

                {blocked !== null && blockedKeys.length === 0 && (
                    <p>Archived flags stop serving in every environment. You can restore them later.</p>
                )}

                {blockedKeys.length > 0 && (
                    <div className="banner banner-warning" role="alert">
                        <p className="banner-title">
                            {pluralize(blockedKeys.length, "flag")} can’t be archived because active flags depend on {blockedKeys.length === 1 ? "it" : "them"}.
                        </p>
                        <ul className="banner-list">
                            {blockedKeys.map((key) => (
                                <li key={key}>
                                    <code className="flag-key">{key}</code> is needed by {blocked[key].join(", ")}
                                </li>
                            ))}
                        </ul>
                    </div>
                )}

                <ErrorBanner error={error} />

                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose} data-autofocus>
                        Cancel
                    </button>
                    <button
                        type="button"
                        className="button button-danger-solid"
                        disabled={busy || blocked === null || archivableCount === 0}
                        onClick={confirm}
                    >
                        {busy ? "Archiving…" : blockedKeys.length > 0 ? `Archive the other ${archivableCount}` : `Archive ${pluralize(archivableCount, "flag")}`}
                    </button>
                </div>
            </div>
        </Dialog>
    );
}
