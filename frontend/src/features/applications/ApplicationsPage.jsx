import { useRef, useState } from "react";
import { Link } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { useLiveEvents } from "../../shared/state/LiveContext.jsx";
import { createApplication, deleteApplication, listApplications, updateApplication } from "../../shared/api/flags.js";
import ConfirmDialog from "../../shared/ConfirmDialog.jsx";
import Dialog from "../../shared/Dialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import Pager from "../../shared/Pager.jsx";
import useAction from "../../shared/useAction.js";
import useDebouncedCallback from "../../shared/useDebouncedCallback.js";
import usePagedList from "../../shared/usePagedList.js";
import { formatRelativeTime } from "../../shared/format.js";

const PAGE_SIZE = 25;
const LIVE_RELOAD_DELAY_MS = 300;

function ApplicationDialog({ application, onClose, onSaved }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [key, setKey] = useState(application?.key || "");
    const [name, setName] = useState(application?.name || "");
    const [minSupportedVersion, setMinSupportedVersion] = useState(application?.minSupportedVersion || "");

    const handleSubmit = async (event) => {
        event.preventDefault();
        const body = { name: name.trim(), minSupportedVersion: minSupportedVersion.trim(), changedBy: actingAs };
        const { ok } = await run(() => (application ? updateApplication(application.key, body) : createApplication({ ...body, key: key.trim() })));
        if (ok) onSaved();
    };

    return (
        <Dialog title={application ? `Edit ${application.name}` : "New application"} onClose={onClose}>
            <form className="form" onSubmit={handleSubmit}>
                {!application && (
                    <label className="field">
                        <span>Key</span>
                        <input
                            value={key}
                            onChange={(event) => setKey(event.target.value)}
                            placeholder="com.example.mobile"
                            pattern="[A-Za-z0-9][A-Za-z0-9._\-]*"
                            title="Letters, numbers, dots, underscores, and hyphens."
                            required
                            data-autofocus
                        />
                    </label>
                )}
                <label className="field">
                    <span>Name</span>
                    <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Example Mobile" required data-autofocus={application ? true : undefined} />
                </label>
                <label className="field">
                    <span>Minimum supported version</span>
                    <input value={minSupportedVersion} onChange={(event) => setMinSupportedVersion(event.target.value)} placeholder="2.4.0" required />
                    <span className="hint">Rules that check “runs a supported app version” match versions at or above this.</span>
                </label>
                <ErrorBanner error={error} />
                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose}>
                        Cancel
                    </button>
                    <button type="submit" className="button button-primary" disabled={busy}>
                        {busy ? "Saving…" : application ? "Save changes" : "Create application"}
                    </button>
                </div>
            </form>
        </Dialog>
    );
}

export default function ApplicationsPage() {
    const { actingAs } = useAppState();
    const { busy, error: deleteError, run } = useAction();
    const [editing, setEditing] = useState(null);
    const [deleting, setDeleting] = useState(null);
    const headingRef = useRef(null);

    const list = usePagedList(({ page, limit }) => listApplications({ page, limit }), [], PAGE_SIZE);
    const { items: applications, error } = list;
    const scheduleReload = useDebouncedCallback(list.reload, LIVE_RELOAD_DELAY_MS);
    useLiveEvents((event) => (event.type === "application-changed" || event.type === "resync") && scheduleReload());

    const confirmDelete = async () => {
        const { ok } = await run(() => deleteApplication(deleting.key, actingAs));
        if (ok) {
            setDeleting(null);
            list.reload();
            headingRef.current?.focus();
        }
    };

    return (
        <div className="page">
            <header className="page-header">
                <div>
                    <h1 ref={headingRef} tabIndex={-1}>
                        Applications
                    </h1>
                    <p className="page-subtitle">
                        Mobile apps and the oldest version each one supports. Applications are shared by every environment and used by{" "}
                        <Link to="/flags">flag rules</Link> that target <code className="flag-key">ld_application</code> contexts.
                    </p>
                </div>
                <button type="button" className="button button-primary" onClick={() => setEditing({})}>
                    New application
                </button>
            </header>

            <ErrorBanner error={error} onReload={list.reload} />

            {applications === null && !error ? (
                <div className="empty-state">Loading applications…</div>
            ) : applications && applications.length === 0 ? (
                <div className="empty-state">No applications yet. Register one to target app versions in flag rules.</div>
            ) : (
                applications && (
                    <div className="table-scroll">
                        <table className="flags-table">
                            <thead>
                                <tr>
                                    <th scope="col">Application</th>
                                    <th scope="col">Minimum version</th>
                                    <th scope="col">Updated</th>
                                    <th scope="col">
                                        <span className="sr-only">Actions</span>
                                    </th>
                                </tr>
                            </thead>
                            <tbody>
                                {applications.map((application) => (
                                    <tr key={application.key}>
                                        <td>
                                            <Link to={`/applications/${encodeURIComponent(application.key)}`} className="flag-cell">
                                                <span className="flag-name">{application.name}</span>
                                                <code className="flag-key">{application.key}</code>
                                            </Link>
                                        </td>
                                        <td>
                                            <code className="flag-key">{application.minSupportedVersion}</code>
                                        </td>
                                        <td>
                                            {formatRelativeTime(application.updatedAt)}
                                            {application.updatedBy && <span className="hint"> by {application.updatedBy}</span>}
                                        </td>
                                        <td className="col-actions">
                                            <button type="button" className="button button-ghost" onClick={() => setEditing(application)}>
                                                Edit
                                            </button>
                                            <button type="button" className="button button-ghost" onClick={() => setDeleting(application)}>
                                                Delete
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )
            )}

            <Pager meta={list.meta} onPageChange={list.setPage} label="Applications pages" />

            {editing && (
                <ApplicationDialog
                    application={editing.key ? editing : null}
                    onClose={() => setEditing(null)}
                    onSaved={() => {
                        setEditing(null);
                        list.reload();
                    }}
                />
            )}

            {deleting && (
                <ConfirmDialog
                    title="Delete application"
                    confirmLabel="Delete application"
                    busyLabel="Deleting…"
                    busy={busy}
                    error={deleteError}
                    onConfirm={confirmDelete}
                    onClose={() => setDeleting(null)}
                >
                    <p>
                        Delete <strong>{deleting.name}</strong>? Rules that check for a supported app version will no longer match its contexts.
                    </p>
                </ConfirmDialog>
            )}
        </div>
    );
}
