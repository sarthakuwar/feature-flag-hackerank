import { useRef, useState } from "react";
import { Link, navigate } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { useLiveEvents } from "../../shared/state/LiveContext.jsx";
import { changeSegmentMembers, deleteSegment, getSegment, getSegmentUsage, updateSegment } from "../../shared/api/flags.js";
import ClauseListEditor from "../../shared/ClauseListEditor.jsx";
import ClauseSummary from "../../shared/ClauseSummary.jsx";
import ConfirmDialog from "../../shared/ConfirmDialog.jsx";
import Dialog from "../../shared/Dialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import KeyList from "../../shared/KeyList.jsx";
import TabList, { tabPanelProps } from "../../shared/TabList.jsx";
import useAction from "../../shared/useAction.js";
import useResource from "../../shared/useResource.js";
import { formatEnumLabel, formatRelativeTime, parseKeyList, pluralize } from "../../shared/format.js";
import { emptyClause, toClauseRequest } from "../../shared/targeting.js";

const TABS = [
    { id: "members", label: "Members" },
    { id: "rules", label: "Rules" },
    { id: "usage", label: "Used by" },
];

function toRuleRequest(rule) {
    return {
        id: rule.id,
        description: rule.description,
        clauses: rule.clauses.map((clause) => toClauseRequest({ ...clause, attribute: clause.attribute || "" })),
    };
}

function MembersPanel({ segment, list, title, hint, readOnly, onChanged }) {
    const { actingAs, environment } = useAppState();
    const { busy, error, run } = useAction();
    const [text, setText] = useState("");
    const [notice, setNotice] = useState(null);
    const keys = list === "INCLUDED" ? segment.included : segment.excluded;

    const change = (operation, changing) =>
        run(() => changeSegmentMembers(environment, segment.key, { list, operation, keys: changing, changedBy: actingAs }));

    const add = async (event) => {
        event.preventDefault();
        const { ok, result } = await change("ADD", parseKeyList(text));
        if (ok) {
            const { changed, unchanged } = result.summary;
            setNotice(`Added ${pluralize(changed, "key")}${unchanged > 0 ? `; ${pluralize(unchanged, "key")} already in the list` : ""}.`);
            setText("");
            onChanged();
        }
    };

    const remove = async (key) => {
        const { ok } = await change("REMOVE", [key]);
        if (ok) {
            setNotice(null);
            onChanged();
        }
    };

    return (
        <section className="panel" aria-labelledby={`${list}-heading`}>
            <div>
                <h2 id={`${list}-heading`}>
                    {title} <span className="tab-count">{keys.length.toLocaleString("en")}</span>
                </h2>
                <p className="hint">{hint}</p>
            </div>
            {!readOnly && (
                <form className="form" onSubmit={add}>
                    <label className="field">
                        <span className="sr-only">Keys to add to {title.toLowerCase()}</span>
                        <textarea rows={2} value={text} onChange={(event) => setText(event.target.value)} placeholder="user-1001, user-1002" />
                    </label>
                    <div>
                        <button type="submit" className="button" disabled={busy || parseKeyList(text).length === 0}>
                            {busy ? "Adding…" : "Add keys"}
                        </button>
                    </div>
                </form>
            )}
            {notice && (
                <div className="banner banner-success" role="status">
                    {notice}
                </div>
            )}
            <ErrorBanner error={error} onReload={onChanged} />
            <KeyList keys={keys} label={`${title} keys`} emptyText={`No ${title.toLowerCase()} keys.`} disabled={readOnly || busy} onRemove={remove} />
        </section>
    );
}

function SegmentRuleDialog({ segment, rule, onClose, onSaved }) {
    const { actingAs, environment } = useAppState();
    const { busy, error, run } = useAction();
    const [description, setDescription] = useState(rule?.description || "");
    const [clauses, setClauses] = useState(() =>
        rule ? rule.clauses.map((clause) => ({ ...clause, attribute: clause.attribute || "" })) : [emptyClause()],
    );

    const handleSubmit = async (event) => {
        event.preventDefault();
        const next = { id: rule?.id, description: description.trim(), clauses: clauses.map(toClauseRequest) };
        const rules = rule
            ? segment.rules.map((entry) => (entry.id === rule.id ? next : toRuleRequest(entry)))
            : [...segment.rules.map(toRuleRequest), next];
        const { ok } = await run(() => updateSegment(environment, segment.key, { rules, changedBy: actingAs }));
        if (ok) onSaved();
    };

    return (
        <Dialog title={rule ? "Edit segment rule" : "Add segment rule"} onClose={onClose} wide closeOnOverlay={false}>
            <form className="form" onSubmit={handleSubmit}>
                <label className="field">
                    <span>Description</span>
                    <input value={description} onChange={(event) => setDescription(event.target.value)} placeholder="Company email" data-autofocus />
                </label>
                <fieldset className="field-group">
                    <legend>Members are {segment.contextKind} contexts where</legend>
                    <ClauseListEditor clauses={clauses} disabled={busy} onChange={setClauses} />
                </fieldset>
                <ErrorBanner error={error} />
                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose}>
                        Cancel
                    </button>
                    <button type="submit" className="button button-primary" disabled={busy}>
                        {busy ? "Saving…" : rule ? "Save rule" : "Add rule"}
                    </button>
                </div>
            </form>
        </Dialog>
    );
}

function RulesPanel({ segment, onChanged }) {
    const { actingAs, environment } = useAppState();
    const { busy, error, run } = useAction();
    const [editing, setEditing] = useState(null);
    const [deleting, setDeleting] = useState(null);

    const confirmDelete = async () => {
        const rules = segment.rules.filter((rule) => rule.id !== deleting.id).map(toRuleRequest);
        const { ok } = await run(() => updateSegment(environment, segment.key, { rules, changedBy: actingAs }));
        if (ok) {
            setDeleting(null);
            onChanged();
        }
    };

    return (
        <section className="panel" aria-labelledby="segment-rules-heading">
            <div className="panel-header">
                <div>
                    <h2 id="segment-rules-heading">Rules</h2>
                    <p className="hint">
                        Contexts matching any rule are members, unless they are excluded by key. Keys in the included list are always members.
                    </p>
                </div>
                <button type="button" className="button button-primary" onClick={() => setEditing({})}>
                    Add rule
                </button>
            </div>

            {segment.rules.length === 0 ? (
                <div className="empty-state compact">No rules. Only the included keys are members.</div>
            ) : (
                <ul className="rule-list">
                    {segment.rules.map((rule) => (
                        <li key={rule.id} className="rule-card">
                            <div className="rule-head">
                                <div className="rule-title">
                                    <strong>{rule.description || "Untitled rule"}</strong>
                                </div>
                            </div>
                            <div className="rule-body">
                                <div className="rule-row">
                                    <span className="rule-label">If</span>
                                    <div className="rule-clauses">
                                        {rule.clauses.map((clause, position) => (
                                            <div key={position} className="rule-clause">
                                                {position > 0 && <span className="clause-and">and</span>}
                                                <ClauseSummary clause={clause} />
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            </div>
                            <div className="rule-actions">
                                <button type="button" className="button button-ghost" disabled={busy} onClick={() => setEditing(rule)}>
                                    Edit
                                </button>
                                <button type="button" className="button button-ghost" disabled={busy} onClick={() => setDeleting(rule)}>
                                    Delete
                                </button>
                            </div>
                        </li>
                    ))}
                </ul>
            )}

            {!deleting && <ErrorBanner error={error} onReload={onChanged} />}

            {editing && (
                <SegmentRuleDialog
                    segment={segment}
                    rule={editing.id ? editing : null}
                    onClose={() => setEditing(null)}
                    onSaved={() => {
                        setEditing(null);
                        onChanged();
                    }}
                />
            )}

            {deleting && (
                <ConfirmDialog
                    title="Delete segment rule"
                    confirmLabel="Delete rule"
                    busyLabel="Deleting…"
                    busy={busy}
                    error={error}
                    onConfirm={confirmDelete}
                    onClose={() => setDeleting(null)}
                >
                    <p>
                        Delete <strong>{deleting.description || "this rule"}</strong>? Contexts it matched stop being members unless they are included by key.
                    </p>
                </ConfirmDialog>
            )}
        </section>
    );
}

function UsagePanel({ usage }) {
    return (
        <section className="panel" aria-labelledby="usage-heading">
            <div>
                <h2 id="usage-heading">Used by</h2>
                <p className="hint">Flag rules that target this segment. Changing its members changes who those rules serve.</p>
            </div>
            {usage === null ? (
                <p className="hint">Loading usage…</p>
            ) : usage.length === 0 ? (
                <div className="empty-state compact">No flag rules use this segment yet.</div>
            ) : (
                <ul className="usage-list">
                    {usage.map((entry) => (
                        <li key={`${entry.flagKey}:${entry.ruleId}`}>
                            <Link to={`/flags/${encodeURIComponent(entry.flagKey)}/targeting`}>{entry.flagName}</Link>
                            <span className="hint">{entry.ruleDescription || "Untitled rule"}</span>
                            {entry.lifecycle === "ARCHIVED" && (
                                <span className="badge" data-tone="muted">
                                    Archived
                                </span>
                            )}
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}

function EditSegmentDialog({ segment, onClose, onSaved }) {
    const { actingAs, environment } = useAppState();
    const { busy, error, run } = useAction();
    const [name, setName] = useState(segment.name);
    const [description, setDescription] = useState(segment.description || "");

    const handleSubmit = async (event) => {
        event.preventDefault();
        const { ok } = await run(() => updateSegment(environment, segment.key, { name: name.trim(), description: description.trim(), changedBy: actingAs }));
        if (ok) onSaved();
    };

    return (
        <Dialog title="Edit segment" onClose={onClose}>
            <form className="form" onSubmit={handleSubmit}>
                <label className="field">
                    <span>Name</span>
                    <input value={name} onChange={(event) => setName(event.target.value)} required data-autofocus />
                </label>
                <label className="field">
                    <span>Description</span>
                    <textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={3} />
                </label>
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

export default function SegmentDetailPage({ segmentKey }) {
    const { environment, actingAs } = useAppState();
    const [tab, setTab] = useState("members");
    const [editOpen, setEditOpen] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const { busy, error: deleteError, run } = useAction();
    const headingRef = useRef(null);

    const segmentRes = useResource(() => getSegment(environment, segmentKey), [environment, segmentKey]);
    const usageRes = useResource(() => getSegmentUsage(environment, segmentKey), [environment, segmentKey]);

    const reloadAll = () => {
        segmentRes.reload();
        usageRes.reload();
    };
    useLiveEvents(
        (event) => (event.type === "resync" || (event.type === "segment-changed" && event.segmentKey === segmentKey)) && reloadAll(),
    );

    const segment = segmentRes.data;

    if (segmentRes.error?.status === 404) {
        return (
            <div className="page">
                <Link to="/segments" className="back-link">
                    ← Segments
                </Link>
                <div className="empty-state">
                    No segment “{segmentKey}” exists in {formatEnumLabel(environment)}. Segments belong to one environment; switch environments to look elsewhere.
                </div>
            </div>
        );
    }

    if (!segment) {
        return (
            <div className="page">
                <Link to="/segments" className="back-link">
                    ← Segments
                </Link>
                {segmentRes.error ? <ErrorBanner error={segmentRes.error} onReload={reloadAll} /> : <div className="empty-state">Loading segment…</div>}
            </div>
        );
    }

    const usage = usageRes.data;
    const tabs = TABS.map((entry) => (entry.id === "usage" && usage ? { ...entry, count: usage.length } : entry));

    const confirmDelete = async () => {
        const { ok } = await run(() => deleteSegment(environment, segmentKey, actingAs));
        if (ok) navigate("/segments");
    };

    return (
        <div className="page">
            <nav aria-label="Breadcrumb" className="breadcrumb">
                <Link to="/segments">Segments</Link>
                <span aria-hidden="true">/</span>
                <span aria-current="page">{segment.name}</span>
            </nav>

            <header className="page-header flag-header">
                <div>
                    <div className="flag-title-row">
                        <h1 ref={headingRef} tabIndex={-1}>
                            {segment.name}
                        </h1>
                        <code className="flag-key">{segment.key}</code>
                        <span className="badge">{segment.contextKind}</span>
                    </div>
                    {segment.description && <p className="page-subtitle">{segment.description}</p>}
                    <p className="meta-line">
                        {formatEnumLabel(environment)} · updated {formatRelativeTime(segment.updatedAt)}
                        {segment.updatedBy && <> by {segment.updatedBy}</>}
                    </p>
                </div>
                <div className="bulk-actions">
                    <button type="button" className="button" onClick={() => setEditOpen(true)}>
                        Edit details
                    </button>
                    <button type="button" className="button button-danger" onClick={() => setDeleteOpen(true)}>
                        Delete
                    </button>
                </div>
            </header>

            <TabList tabs={tabs} activeTab={tab} onChange={setTab} label="Segment sections" />

            <div {...tabPanelProps("members", tab)} className="tab-panel">
                {tab === "members" && (
                    <>
                        <MembersPanel
                            segment={segment}
                            list="INCLUDED"
                            title="Included"
                            hint="Always members, whatever the rules say."
                            onChanged={reloadAll}
                        />
                        <MembersPanel
                            segment={segment}
                            list="EXCLUDED"
                            title="Excluded"
                            hint="Never members, unless they are also in the included list. A key can be in only one list."
                            onChanged={reloadAll}
                        />
                    </>
                )}
            </div>

            <div {...tabPanelProps("rules", tab)} className="tab-panel">
                {tab === "rules" && <RulesPanel segment={segment} onChanged={reloadAll} />}
            </div>

            <div {...tabPanelProps("usage", tab)} className="tab-panel">
                {tab === "usage" && <UsagePanel usage={usage} />}
            </div>

            {editOpen && (
                <EditSegmentDialog
                    segment={segment}
                    onClose={() => setEditOpen(false)}
                    onSaved={() => {
                        setEditOpen(false);
                        reloadAll();
                    }}
                />
            )}

            {deleteOpen && (
                <ConfirmDialog
                    title="Delete segment"
                    confirmLabel="Delete segment"
                    busyLabel="Deleting…"
                    busy={busy}
                    error={deleteError}
                    onConfirm={confirmDelete}
                    onClose={() => setDeleteOpen(false)}
                >
                    <p>
                        Delete <strong>{segment.name}</strong> from {formatEnumLabel(environment)}?{" "}
                        {usage && usage.length > 0
                            ? `It is still used by ${pluralize(usage.length, "flag rule")}, so the server will refuse until those rules stop using it.`
                            : "This can’t be undone."}
                    </p>
                </ConfirmDialog>
            )}
        </div>
    );
}
