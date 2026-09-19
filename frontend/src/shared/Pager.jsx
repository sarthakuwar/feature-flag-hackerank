export default function Pager({ meta, onPageChange, label }) {
    if (!meta || meta.total === 0) return null;
    const first = (meta.page - 1) * meta.limit + 1;
    const last = Math.min(meta.total, meta.page * meta.limit);

    return (
        <nav className="pager" aria-label={label}>
            <p className="pager-summary" role="status">
                Showing {first.toLocaleString("en")}–{last.toLocaleString("en")} of {meta.total.toLocaleString("en")}
            </p>
            <div className="pager-actions">
                <button type="button" className="button" disabled={meta.page <= 1} onClick={() => onPageChange(meta.page - 1)}>
                    Previous
                </button>
                <button type="button" className="button" disabled={last >= meta.total} onClick={() => onPageChange(meta.page + 1)}>
                    Next
                </button>
            </div>
        </nav>
    );
}
