import { useRef, useState } from "react";
import { Link, navigate } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { useLiveEvents } from "../../shared/state/LiveContext.jsx";
import { listFlags, bulkRestore } from "../../shared/api/flags.js";
import { formatEnumLabel, formatRelativeTime, pluralize, variationColor } from "../../shared/format.js";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import Pager from "../../shared/Pager.jsx";
import useAction from "../../shared/useAction.js";
import useDebouncedCallback from "../../shared/useDebouncedCallback.js";
import useDebouncedValue from "../../shared/useDebouncedValue.js";
import usePagedList from "../../shared/usePagedList.js";
import CreateFlagDialog from "./CreateFlagDialog.jsx";
import BulkArchiveDialog from "./BulkArchiveDialog.jsx";

const LIFECYCLE_FILTERS = [
    { value: "", label: "All" },
    { value: "ACTIVE", label: "Active" },
    { value: "ARCHIVED", label: "Archived" },
];

const PAGE_SIZE = 25;
const SEARCH_DELAY_MS = 300;
const LIVE_RELOAD_DELAY_MS = 300;
const NO_SELECTION = new Set();

function summarizeBulk(result) {
    const parts = [];
    if (result.archived?.length) parts.push(`Archived ${pluralize(result.archived.length, "flag")}.`);
    if (result.restored?.length) parts.push(`Restored ${pluralize(result.restored.length, "flag")}.`);
    if (result.alreadyArchived?.length) parts.push(`${pluralize(result.alreadyArchived.length, "flag")} already archived.`);
    if (result.alreadyActive?.length) parts.push(`${pluralize(result.alreadyActive.length, "flag")} already active.`);
    if (result.blocked && Object.keys(result.blocked).length) {
        parts.push(`Skipped ${Object.keys(result.blocked).join(", ")}: other active flags depend on ${Object.keys(result.blocked).length === 1 ? "it" : "them"}.`);
    }
    if (result.notFound?.length) parts.push(`Not found: ${result.notFound.join(", ")}.`);
    return parts.join(" ");
}

export default function FlagsListPage() {
    const { environment, actingAs, environments } = useAppState();
    const [lifecycle, setLifecycle] = useState("ACTIVE");
    const [search, setSearch] = useState("");
    const [createOpen, setCreateOpen] = useState(false);
    const [archiveOpen, setArchiveOpen] = useState(false);
    const [notice, setNotice] = useState(null);
    const { busy, error: actionError, run } = useAction();
    const headingRef = useRef(null);
    const newFlagButtonRef = useRef(null);

    const query = useDebouncedValue(search.trim(), SEARCH_DELAY_MS);
    const list = usePagedList(({ page, limit }) => listFlags({ page, limit, q: query, lifecycle }), [lifecycle, query], PAGE_SIZE);
    const { items: flags, meta, error: loadError } = list;

    // Events from one edit arrive in a burst, and this page's own bulk actions trigger them too: reload once.
    const scheduleReload = useDebouncedCallback(list.reload, LIVE_RELOAD_DELAY_MS);
    useLiveEvents((event) => (event.type === "flag-changed" || event.type === "resync") && scheduleReload());

    // A selection belongs to the page and filters it was made on, so it is discarded when any of them change.
    const selectionScope = `${list.scope}:${list.page}`;
    const [selection, setSelection] = useState({ scope: selectionScope, keys: NO_SELECTION });
    const selected = selection.scope === selectionScope ? selection.keys : NO_SELECTION;
    const updateSelected = (keys) => setSelection({ scope: selectionScope, keys });

    const changeLifecycle = (value) => {
        setLifecycle(value);
        setNotice(null);
    };

    const toggleSelected = (key) => {
        const next = new Set(selected);
        if (next.has(key)) next.delete(key);
        else next.add(key);
        updateSelected(next);
    };

    const visible = flags || [];
    const allVisibleSelected = visible.length > 0 && visible.every((flag) => selected.has(flag.key));
    const toggleAllVisible = () => updateSelected(allVisibleSelected ? new Set() : new Set(visible.map((flag) => flag.key)));
    const clearSelection = () => updateSelected(new Set());

    const finishBulk = (result) => {
        setNotice(summarizeBulk(result));
        clearSelection();
        setArchiveOpen(false);
        scheduleReload();
        headingRef.current?.focus();
    };

    const restoreSelected = async () => {
        const { ok, result } = await run(() => bulkRestore(Array.from(selected), actingAs));
        if (ok) finishBulk(result);
    };

    return (
        <div className="page">
            <header className="page-header">
                <div>
                    <h1 ref={headingRef} tabIndex={-1}>
                        Flags
                    </h1>
                    <p className="page-subtitle">Showing {formatEnumLabel(environment)} status</p>
                </div>
                <button type="button" ref={newFlagButtonRef} className="button button-primary" onClick={() => setCreateOpen(true)}>
                    New flag
                </button>
            </header>

            <div className="toolbar">
                <input
                    type="search"
                    className="search-input"
                    placeholder="Search by key, name, or owner"
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                    aria-label="Search flags"
                />
                <div className="segmented" role="group" aria-label="Filter by lifecycle">
                    {LIFECYCLE_FILTERS.map((filter) => (
                        <button
                            key={filter.value || "all"}
                            type="button"
                            aria-pressed={lifecycle === filter.value}
                            onClick={() => changeLifecycle(filter.value)}
                        >
                            {filter.label}
                        </button>
                    ))}
                </div>
            </div>

            {selected.size > 0 && (
                <div className="bulk-bar" role="region" aria-label="Bulk actions">
                    <span>{selected.size} selected</span>
                    <div className="bulk-actions">
                        <button type="button" className="button" disabled={busy} onClick={restoreSelected}>
                            Restore
                        </button>
                        <button type="button" className="button button-danger" disabled={busy} onClick={() => setArchiveOpen(true)}>
                            Archive
                        </button>
                        <button type="button" className="button button-ghost" onClick={clearSelection}>
                            Clear
                        </button>
                    </div>
                </div>
            )}

            {notice && (
                <div className="banner banner-success" role="status">
                    {notice}
                </div>
            )}
            <ErrorBanner error={loadError || actionError} onReload={list.reload} />

            {flags === null && !loadError ? (
                <div className="empty-state">Loading flags…</div>
            ) : visible.length === 0 ? (
                <div className="empty-state">
                    {query ? "No flags match your search." : lifecycle === "ARCHIVED" ? "No archived flags." : "No flags yet. Create one to get started."}
                </div>
            ) : (
                <div className="table-scroll" aria-busy={list.loading}>
                    <table className="flags-table">
                        <thead>
                            <tr>
                                <th scope="col" className="col-select">
                                    <input type="checkbox" checked={allVisibleSelected} onChange={toggleAllVisible} aria-label="Select all visible flags" />
                                </th>
                                <th scope="col">Flag</th>
                                <th scope="col">Type</th>
                                <th scope="col">Environments</th>
                                <th scope="col">Owner</th>
                                <th scope="col">Updated</th>
                            </tr>
                        </thead>
                        <tbody>
                            {visible.map((flag) => (
                                <tr key={flag.key} data-archived={flag.lifecycle === "ARCHIVED"}>
                                    <td className="col-select">
                                        <input
                                            type="checkbox"
                                            checked={selected.has(flag.key)}
                                            onChange={() => toggleSelected(flag.key)}
                                            aria-label={`Select ${flag.name}`}
                                        />
                                    </td>
                                    <td>
                                        <Link to={`/flags/${encodeURIComponent(flag.key)}`} className="flag-cell">
                                            <span className="flag-name">{flag.name}</span>
                                            <span className="variation-dot-row" aria-hidden="true">
                                                {flag.variations.map((variation, index) => (
                                                    <span key={variation.key} className="variation-dot" style={{ background: variationColor(index) }} />
                                                ))}
                                            </span>
                                            <code className="flag-key">{flag.key}</code>
                                            {flag.lifecycle === "ARCHIVED" && (
                                                <span className="badge" data-tone="muted">
                                                    Archived
                                                </span>
                                            )}
                                        </Link>
                                    </td>
                                    <td>
                                        <span className="badge">{formatEnumLabel(flag.type)}</span>
                                    </td>
                                    <td>
                                        <div className="env-pill-row">
                                            {environments.map((env) => {
                                                const enabled = Boolean(flag.environments?.[env]?.enabled);
                                                return (
                                                    <span key={env} className="env-pill" data-env={env} data-enabled={enabled}>
                                                        <span className="env-pill-dot" aria-hidden="true" />
                                                        {formatEnumLabel(env).slice(0, 3)}
                                                        <span className="sr-only">
                                                            {" "}
                                                            in {formatEnumLabel(env)}: {enabled ? "on" : "off"}
                                                        </span>
                                                    </span>
                                                );
                                            })}
                                        </div>
                                    </td>
                                    <td>{flag.owner || "—"}</td>
                                    <td>{formatRelativeTime(flag.updatedAt)}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            <Pager meta={meta} onPageChange={list.setPage} label="Flags pages" />

            {createOpen && (
                <CreateFlagDialog
                    onClose={() => setCreateOpen(false)}
                    onCreated={(flag) => {
                        setCreateOpen(false);
                        navigate(`/flags/${encodeURIComponent(flag.key)}`);
                    }}
                />
            )}

            {archiveOpen && <BulkArchiveDialog keys={Array.from(selected)} onClose={() => setArchiveOpen(false)} onDone={finishBulk} />}
        </div>
    );
}
