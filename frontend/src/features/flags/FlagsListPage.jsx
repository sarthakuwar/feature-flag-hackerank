import { useMemo, useRef, useState } from "react";
import { Link, navigate } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { useLiveEvents } from "../../shared/state/LiveContext.jsx";
import { listFlags, bulkRestore } from "../../shared/api/flags.js";
import { formatEnumLabel, formatRelativeTime, pluralize, variationColor } from "../../shared/format.js";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import useResource from "../../shared/useResource.js";
import CreateFlagDialog from "./CreateFlagDialog.jsx";
import BulkArchiveDialog from "./BulkArchiveDialog.jsx";

const LIFECYCLE_FILTERS = [
    { value: "", label: "All" },
    { value: "ACTIVE", label: "Active" },
    { value: "ARCHIVED", label: "Archived" },
];

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
    const [selected, setSelected] = useState(() => new Set());
    const [createOpen, setCreateOpen] = useState(false);
    const [archiveOpen, setArchiveOpen] = useState(false);
    const [notice, setNotice] = useState(null);
    const { busy, error: actionError, run } = useAction();
    const headingRef = useRef(null);
    const newFlagButtonRef = useRef(null);

    const { data: flags, error: loadError, reload } = useResource(() => listFlags(lifecycle || undefined), [lifecycle]);
    useLiveEvents((event) => event.type === "flag-changed" && reload());

    const filtered = useMemo(() => {
        if (!flags) return [];
        const term = search.trim().toLowerCase();
        if (!term) return flags;
        return flags.filter(
            (flag) =>
                flag.key.toLowerCase().includes(term) || flag.name.toLowerCase().includes(term) || (flag.owner || "").toLowerCase().includes(term),
        );
    }, [flags, search]);

    const changeLifecycle = (value) => {
        setLifecycle(value);
        setSelected(new Set());
        setNotice(null);
    };

    const toggleSelected = (key) => {
        setSelected((prev) => {
            const next = new Set(prev);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            return next;
        });
    };

    const allVisibleSelected = filtered.length > 0 && filtered.every((flag) => selected.has(flag.key));
    const toggleAllVisible = () => setSelected(allVisibleSelected ? new Set() : new Set(filtered.map((flag) => flag.key)));
    const clearSelection = () => setSelected(new Set());

    const finishBulk = (result) => {
        setNotice(summarizeBulk(result));
        clearSelection();
        setArchiveOpen(false);
        reload();
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
            <ErrorBanner error={loadError || actionError} />

            {flags === null && !loadError ? (
                <div className="empty-state">Loading flags…</div>
            ) : filtered.length === 0 ? (
                <div className="empty-state">
                    {search ? "No flags match your search." : lifecycle === "ARCHIVED" ? "No archived flags." : "No flags yet. Create one to get started."}
                </div>
            ) : (
                <div className="table-scroll">
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
                            {filtered.map((flag) => (
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
