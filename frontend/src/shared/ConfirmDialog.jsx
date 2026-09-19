import Dialog from "./Dialog.jsx";
import ErrorBanner from "./ErrorBanner.jsx";

export default function ConfirmDialog({ title, confirmLabel, busyLabel, busy, error, onConfirm, onClose, danger = true, children }) {
    return (
        <Dialog title={title} onClose={onClose}>
            <div className="form">
                <div className="dialog-body">{children}</div>
                <ErrorBanner error={error} />
                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose} data-autofocus>
                        Cancel
                    </button>
                    <button
                        type="button"
                        className={danger ? "button button-danger-solid" : "button button-primary"}
                        disabled={busy}
                        onClick={onConfirm}
                    >
                        {busy ? busyLabel : confirmLabel}
                    </button>
                </div>
            </div>
        </Dialog>
    );
}
