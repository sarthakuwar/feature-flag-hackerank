import { useEffect, useState } from "react";
import { Link, navigate } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { useLiveEvents } from "../../shared/state/LiveContext.jsx";
import { listSegments } from "../../shared/api/flags.js";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useResource from "../../shared/useResource.js";
import { formatEnumLabel, formatRelativeTime } from "../../shared/format.js";
import { CONTEXT_KINDS } from "../../shared/targeting.js";
import CreateSegmentDialog from "./CreateSegmentDialog.jsx";

export default function SegmentsPage() {
    const { environment } = useAppState();
    const [search, setSearch] = useState("");
    const [query, setQuery] = useState("");
    const [contextKind, setContextKind] = useState("");
    const [createOpen, setCreateOpen] = useState(false);

    useEffect(() => {
        const timer = window.setTimeout(() => setQuery(search.trim()), 250);
        return () => window.clearTimeout(timer);
    }, [search]);

    const { data: segments, error, reload } = useResource(() => listSegments(environment, { query, contextKind }), [environment, query, contextKind]);
    useLiveEvents((event) => event.type === "segment-changed" && reload());

    const filtered = query || contextKind;

    return (
        <div className="page">
            <header className="page-header">
                <div>
                    <h1>Segments</h1>
                    <p className="page-subtitle">
                        Reusable audiences for {formatEnumLabel(environment)}. Target a segment from any flag’s rules.
                    </p>
                </div>
                <button type="button" className="button button-primary" onClick={() => setCreateOpen(true)}>
                    New segment
                </button>
            </header>

            <div className="toolbar">
                <input
                    type="search"
                    className="search-input"
                    placeholder="Search by key or name"
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                    aria-label="Search segments"
                />
                <label className="inline-select">
                    <span className="sr-only">Filter by context kind</span>
                    <select value={contextKind} onChange={(event) => setContextKind(event.target.value)}>
                        <option value="">All context kinds</option>
                        {CONTEXT_KINDS.map((kind) => (
                            <option key={kind} value={kind}>
                                {kind}
                            </option>
                        ))}
                    </select>
                </label>
            </div>

            <ErrorBanner error={error} />

            {segments === null && !error ? (
                <div className="empty-state">Loading segments…</div>
            ) : segments && segments.length === 0 ? (
                <div className="empty-state">
                    {filtered ? "No segments match your filters." : `No segments in ${formatEnumLabel(environment)} yet. Create one to reuse an audience across flags.`}
                </div>
            ) : (
                segments && (
                    <div className="table-scroll">
                        <table className="flags-table">
                            <thead>
                                <tr>
                                    <th scope="col">Segment</th>
                                    <th scope="col">Context kind</th>
                                    <th scope="col">Members</th>
                                    <th scope="col">Rules</th>
                                    <th scope="col">Updated</th>
                                </tr>
                            </thead>
                            <tbody>
                                {segments.map((segment) => (
                                    <tr key={segment.key}>
                                        <td>
                                            <Link to={`/segments/${encodeURIComponent(segment.key)}`} className="flag-cell">
                                                <span className="flag-name">{segment.name}</span>
                                                <code className="flag-key">{segment.key}</code>
                                            </Link>
                                        </td>
                                        <td>
                                            <span className="badge">{segment.contextKind}</span>
                                        </td>
                                        <td>
                                            {segment.includedCount.toLocaleString("en")} included · {segment.excludedCount.toLocaleString("en")} excluded
                                        </td>
                                        <td>{segment.ruleCount}</td>
                                        <td>{formatRelativeTime(segment.updatedAt)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )
            )}

            {createOpen && (
                <CreateSegmentDialog
                    onClose={() => setCreateOpen(false)}
                    onCreated={(segment) => {
                        setCreateOpen(false);
                        navigate(`/segments/${encodeURIComponent(segment.key)}`);
                    }}
                />
            )}
        </div>
    );
}
