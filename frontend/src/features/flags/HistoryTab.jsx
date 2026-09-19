import { useMemo, useState } from "react";
import { formatDateTime, formatEnumLabel } from "../../shared/format.js";

const ACTION_TONES = { ROLLOUT_START: "positive", ROLLOUT_COMPLETE: "positive", PROMOTE: "positive", ARCHIVE: "muted", ROLLOUT_STOP: "muted" };

export default function HistoryTab({ audit }) {
    const [search, setSearch] = useState("");
    const [action, setAction] = useState("");

    const actions = useMemo(() => Array.from(new Set((audit || []).map((event) => event.action))).sort(), [audit]);

    const filtered = useMemo(() => {
        if (audit === null) return null;
        const term = search.trim().toLowerCase();
        return audit.filter((event) => {
            if (action && event.action !== action) return false;
            if (!term) return true;
            return event.summary.toLowerCase().includes(term) || event.changedBy.toLowerCase().includes(term) || event.action.toLowerCase().includes(term);
        });
    }, [audit, search, action]);

    return (
        <section className="panel" aria-labelledby="history-heading">
            <h2 id="history-heading">Flag history</h2>
            <div className="toolbar">
                <input
                    type="search"
                    className="search-input"
                    placeholder="Search history…"
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                    aria-label="Search flag history"
                />
                <label className="inline-select">
                    <span className="sr-only">Filter by event type</span>
                    <select value={action} onChange={(event) => setAction(event.target.value)}>
                        <option value="">All events</option>
                        {actions.map((entry) => (
                            <option key={entry} value={entry}>
                                {formatEnumLabel(entry)}
                            </option>
                        ))}
                    </select>
                </label>
            </div>
            {filtered === null ? (
                <p className="hint">Loading history…</p>
            ) : filtered.length === 0 ? (
                <p className="hint">{search || action ? "No matching history." : "No changes recorded yet."}</p>
            ) : (
                <div className="table-scroll">
                    <table className="history-table">
                        <thead>
                            <tr>
                                <th scope="col">Event</th>
                                <th scope="col">Description</th>
                                <th scope="col">Initiated by</th>
                                <th scope="col">Date</th>
                            </tr>
                        </thead>
                        <tbody>
                            {filtered.map((event) => (
                                <tr key={event.id}>
                                    <td>
                                        <span className="badge" data-tone={ACTION_TONES[event.action]}>
                                            {formatEnumLabel(event.action)}
                                        </span>
                                    </td>
                                    <td>{event.summary}</td>
                                    <td>{event.changedBy}</td>
                                    <td>
                                        <time dateTime={event.changedAt}>{formatDateTime(event.changedAt)}</time>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </section>
    );
}
