import { useEffect, useMemo, useRef, useState } from "react";
import { Link, navigate } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { listFlags, bulkArchive, bulkRestore } from "../../shared/api/flags.js";
import { formatEnumLabel, formatRelativeTime, variationColor } from "../../shared/format.js";
import CreateFlagDialog from "./CreateFlagDialog.jsx";

const LIFECYCLE_FILTERS = [
    { value: "", label: "All" },
    { value: "ACTIVE", label: "Active" },
    { value: "ARCHIVED", label: "Archived" },
];

export default function FlagsListPage() {
    const { environment, actingAs, environments } = useAppState();
    const [flags, setFlags] = useState(null);
    const [error, setError] = useState(null);
    const [lifecycle, setLifecycle] = useState("ACTIVE");
    const [search, setSearch] = useState("");
    const [selected, setSelected] = useState(() => new Set());
    const [createOpen, setCreateOpen] = useState(false);
    const [busy, setBusy] = useState(false);
    const headingRef = useRef(null);
    const newFlagButtonRef = useRef(null);

    const load = () => {
        setError(null);
        listFlags(lifecycle || undefined)
            .then(setFlags)
            .catch((err) => setError(err.message));
    };

    useEffect(() => {
        setFlags(null);
        setSelected(new Set());
        load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [lifecycle]);

    const filtered = useMemo(() => {
        if (!flags) return [];
        const term = search.trim().toLowerCase();
        if (!term) return flags;
        return flags.filter((flag) => flag.key.toLowerCase().includes(term) || flag.name.toLowerCase().includes(term));
    }, [flags, search]);

    const toggleSelected = (key) => {
        setSelected((prev) => {
            const next = new Set(prev);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            return next;
        });
    };

    const clearSelection = () => setSelected(new Set());

    const runBulk = async (action) => {
        setBusy(true);
        setError(null);
        try {
            const keys = Array.from(selected);
            if (action === "archive") await bulkArchive(keys, actingAs);
            else await bulkRestore(keys, actingAs);
            clearSelection();
            load();
            headingRef.current?.focus();
        } catch (err) {
            setError(err.message);
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="page">
            <header className="page-header">
                <div>
                    <h1 ref={headingRef} tabIndex={-1}>
                        Flags
                    </h1>
                    <p className="page-subtitle">Editing {formatEnumLabel(environment)}</p>
                </div>
                <button type="button" ref={newFlagButtonRef} className="button button-primary" onClick={() => setCreateOpen(true)}>
                    New flag
                </button>
            </header>

            <div className="toolbar">
                <input
                    type="search"
                    className="search-input"
                    placeholder="Search by key or name"
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
                            onClick={() => setLifecycle(filter.value)}
                        >
                            {filter.label}
                        </button>
                    ))}
                </div>
            </div>

            {selected.size > 0 && (
                <div className="bulk-bar">
                    <span>{selected.size} selected</span>
                    <div className="bulk-actions">
                        <button type="button" className="button" disabled={busy} onClick={() => runBulk("restore")}>
                            Restore
                        </button>
                        <button type="button" className="button button-danger" disabled={busy} onClick={() => runBulk("archive")}>
                            Archive
                        </button>
                        <button type="button" className="button button-ghost" onClick={clearSelection}>
                            Clear
                        </button>
                    </div>
                </div>
            )}

            {error && <div className="banner banner-error">{error}</div>}

            {flags === null && !error ? (
                <div className="empty-state">Loading flags…</div>
            ) : filtered.length === 0 ? (
                <div className="empty-state">{search ? "No flags match your search." : "No flags yet. Create one to get started."}</div>
            ) : (
                <table className="flags-table">
                    <thead>
                        <tr>
                            <th scope="col" className="col-select">
                                <span className="sr-only">Select</span>
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
                            <tr key={flag.key}>
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
            )}

            {createOpen && (
                <CreateFlagDialog
                    onClose={() => {
                        setCreateOpen(false);
                        newFlagButtonRef.current?.focus();
                    }}
                    onCreated={(flag) => {
                        setCreateOpen(false);
                        navigate(`/flags/${encodeURIComponent(flag.key)}`);
                    }}
                />
            )}
        </div>
    );
}
