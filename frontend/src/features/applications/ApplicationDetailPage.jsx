import { Link } from "../../shared/router.jsx";
import { useLiveEvents } from "../../shared/state/LiveContext.jsx";
import { getApplication } from "../../shared/api/flags.js";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useDebouncedCallback from "../../shared/useDebouncedCallback.js";
import useResource from "../../shared/useResource.js";
import { formatDateTime, formatRelativeTime } from "../../shared/format.js";

const LIVE_RELOAD_DELAY_MS = 300;

export default function ApplicationDetailPage({ applicationKey }) {
    const { data: application, error, reload } = useResource(() => getApplication(applicationKey), [applicationKey]);
    const scheduleReload = useDebouncedCallback(reload, LIVE_RELOAD_DELAY_MS);
    useLiveEvents((event) => {
        const affectsThis = event.type === "application-changed" && event.applicationKey === applicationKey;
        if (event.type === "resync" || affectsThis) scheduleReload();
    });

    const back = (
        <Link to="/applications" className="back-link">
            ← Applications
        </Link>
    );

    if (error?.status === 404) {
        return (
            <div className="page">
                {back}
                <div className="empty-state">No application exists with key “{applicationKey}”. It may have been deleted.</div>
            </div>
        );
    }

    if (!application) {
        return (
            <div className="page">
                {back}
                {error ? <ErrorBanner error={error} onReload={reload} /> : <div className="empty-state">Loading application…</div>}
            </div>
        );
    }

    return (
        <div className="page">
            <nav aria-label="Breadcrumb" className="breadcrumb">
                <Link to="/applications">Applications</Link>
                <span aria-hidden="true">/</span>
                <span aria-current="page">{application.name}</span>
            </nav>

            <header className="page-header">
                <div>
                    <div className="flag-title-row">
                        <h1>{application.name}</h1>
                        <code className="flag-key">{application.key}</code>
                    </div>
                    <p className="page-subtitle">
                        Flag rules that check “runs a supported app version” match this application’s contexts at or above the minimum
                        version.
                    </p>
                </div>
            </header>

            <ErrorBanner error={error} onReload={reload} />

            <section className="panel" aria-labelledby="application-details-heading">
                <h2 id="application-details-heading">Details</h2>
                <div className="rail-field">
                    <span className="sidebar-label">Minimum supported version</span>
                    <p>
                        <code className="flag-key">{application.minSupportedVersion}</code>
                    </p>
                </div>
                <div className="rail-field">
                    <span className="sidebar-label">Created</span>
                    <p>
                        <time dateTime={application.createdAt}>{formatDateTime(application.createdAt)}</time>
                    </p>
                </div>
                <div className="rail-field">
                    <span className="sidebar-label">Last updated</span>
                    <p>
                        {formatRelativeTime(application.updatedAt)}
                        {application.updatedBy && <span className="hint"> by {application.updatedBy}</span>}
                    </p>
                </div>
            </section>
        </div>
    );
}
