import { useState } from "react";
import { Link } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { useLiveEvents } from "../../shared/state/LiveContext.jsx";
import { evaluateAllFlags, getApplicationFeed, getBootstrap, getSegmentFeed } from "../../shared/api/flags.js";
import ContextBuilder, { emptyEvaluationContext, toEvaluationRequest } from "../../shared/ContextBuilder.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import TabList, { tabPanelProps } from "../../shared/TabList.jsx";
import useAction from "../../shared/useAction.js";
import useResource from "../../shared/useResource.js";
import { formatDateTime, formatEnumLabel } from "../../shared/format.js";

const TABS = [
    { id: "evaluate", label: "Evaluate all flags" },
    { id: "sdk", label: "SDK data" },
];

const MAX_LOGGED_EVENTS = 25;

function EvaluateAll() {
    const { environment } = useAppState();
    const { busy, error, run } = useAction();
    const [context, setContext] = useState(emptyEvaluationContext);
    const [results, setResults] = useState(null);

    const handleSubmit = async (event) => {
        event.preventDefault();
        const { ok, result } = await run(() => evaluateAllFlags(environment, toEvaluationRequest(context)));
        if (ok) setResults(result);
    };

    return (
        <>
            <section className="panel" aria-labelledby="context-heading">
                <div>
                    <h2 id="context-heading">Context</h2>
                    <p className="hint">
                        Describe who is asking and see what every active flag returns in {formatEnumLabel(environment)}. Nothing is recorded in experiment
                        metrics.
                    </p>
                </div>
                <form className="form" onSubmit={handleSubmit}>
                    <ContextBuilder value={context} onChange={setContext} />
                    <div>
                        <button type="submit" className="button button-primary" disabled={busy}>
                            {busy ? "Evaluating…" : "Evaluate all flags"}
                        </button>
                    </div>
                </form>
                <ErrorBanner error={error} />
            </section>

            {results && (
                <section className="panel" aria-labelledby="results-heading">
                    <h2 id="results-heading">Results</h2>
                    {results.length === 0 ? (
                        <p className="hint">There are no active flags to evaluate.</p>
                    ) : (
                        <div className="table-scroll">
                            <table className="history-table">
                                <thead>
                                    <tr>
                                        <th scope="col">Flag</th>
                                        <th scope="col">Variation</th>
                                        <th scope="col">Value</th>
                                        <th scope="col">Reason</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {results.map((result) => (
                                        <tr key={result.flagKey}>
                                            <td>
                                                <Link to={`/flags/${encodeURIComponent(result.flagKey)}`}>
                                                    <code className="flag-key">{result.flagKey}</code>
                                                </Link>
                                            </td>
                                            <td>
                                                <span className="badge" data-tone="positive">
                                                    {result.variationKey}
                                                </span>
                                            </td>
                                            <td>
                                                <code className="flag-key">{result.value}</code>
                                            </td>
                                            <td>{formatEnumLabel(result.reason)}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </section>
            )}
        </>
    );
}

function JsonPanel({ title, hint, resource }) {
    const [copied, setCopied] = useState(false);
    const text = resource.data ? JSON.stringify(resource.data, null, 2) : "";

    const copy = async () => {
        try {
            await navigator.clipboard.writeText(text);
            setCopied(true);
            window.setTimeout(() => setCopied(false), 2000);
        } catch {
            // Clipboard access can be blocked by permissions; the JSON stays selectable on screen.
        }
    };

    return (
        <section className="panel">
            <div className="panel-header">
                <div>
                    <h2>{title}</h2>
                    <p className="hint">{hint}</p>
                </div>
                <div className="bulk-actions">
                    <button type="button" className="button" onClick={resource.reload}>
                        Refresh
                    </button>
                    <button type="button" className="button" disabled={!text} onClick={copy}>
                        {copied ? "Copied" : "Copy JSON"}
                    </button>
                </div>
            </div>
            <ErrorBanner error={resource.error} />
            {resource.data === null && !resource.error ? (
                <p className="hint">Loading…</p>
            ) : (
                resource.data && (
                    <details>
                        <summary>{Array.isArray(resource.data) ? `${resource.data.length} entries` : "Payload"}</summary>
                        <pre className="json-view" tabIndex={0}>
                            {text}
                        </pre>
                    </details>
                )
            )}
        </section>
    );
}

function LiveLog() {
    const [events, setEvents] = useState([]);

    useLiveEvents((event) => {
        setEvents((previous) => [{ ...event, receivedAt: new Date().toISOString() }, ...previous].slice(0, MAX_LOGGED_EVENTS));
    });

    return (
        <section className="panel" aria-labelledby="live-heading">
            <div>
                <h2 id="live-heading">Live event stream</h2>
                <p className="hint">Change events that connected SDKs receive. Edit a flag, segment, or application in another tab to see one arrive.</p>
            </div>
            {events.length === 0 ? (
                <p className="hint">Waiting for changes…</p>
            ) : (
                <ol className="event-log" aria-live="polite">
                    {events.map((event, index) => (
                        <li key={`${event.receivedAt}:${index}`}>
                            <span className="badge">{formatEnumLabel(event.type.replace("-", "_"))}</span>
                            <code className="flag-key">{event.flagKey || event.segmentKey || event.applicationKey}</code>
                            <span className="hint">{formatEnumLabel(event.action)}</span>
                            <time className="hint" dateTime={event.receivedAt}>
                                {formatDateTime(event.receivedAt)}
                            </time>
                        </li>
                    ))}
                </ol>
            )}
        </section>
    );
}

function SdkData() {
    const { environment } = useAppState();
    const bootstrap = useResource(() => getBootstrap(environment), [environment]);
    const segments = useResource(() => getSegmentFeed(environment), [environment]);
    const applications = useResource(() => getApplicationFeed(), []);

    return (
        <>
            <LiveLog />
            <JsonPanel
                title="Flag bootstrap"
                hint={`Every active flag definition an SDK loads once at startup for ${formatEnumLabel(environment)}.`}
                resource={bootstrap}
            />
            <JsonPanel title="Segment feed" hint="Segment definitions SDKs cache to evaluate segment rules locally." resource={segments} />
            <JsonPanel title="Application feed" hint="Registered mobile applications and their minimum supported versions." resource={applications} />
        </>
    );
}

export default function PlaygroundPage() {
    const { environment } = useAppState();
    const [tab, setTab] = useState("evaluate");

    return (
        <div className="page">
            <header className="page-header">
                <div>
                    <h1>Playground</h1>
                    <p className="page-subtitle">Try evaluations and inspect what SDKs receive from {formatEnumLabel(environment)}.</p>
                </div>
            </header>

            <TabList tabs={TABS} activeTab={tab} onChange={setTab} label="Playground sections" />

            <div {...tabPanelProps("evaluate", tab)} className="tab-panel">
                {tab === "evaluate" && <EvaluateAll />}
            </div>
            <div {...tabPanelProps("sdk", tab)} className="tab-panel">
                {tab === "sdk" && <SdkData />}
            </div>
        </div>
    );
}
