import { useRef, useState } from "react";
import { Link, navigate } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { useLiveEvents } from "../../shared/state/LiveContext.jsx";
import { archiveFlag, getAudit, getFlag, getTargeting, listRollouts, listSegments, restoreFlag } from "../../shared/api/flags.js";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import TabList, { tabPanelProps } from "../../shared/TabList.jsx";
import useAction from "../../shared/useAction.js";
import useResource from "../../shared/useResource.js";
import { formatEnumLabel, formatRelativeTime } from "../../shared/format.js";
import EditFlagDialog from "./EditFlagDialog.jsx";
import HistoryTab from "./HistoryTab.jsx";
import MetricsTab from "./MetricsTab.jsx";
import RolloutsTab from "./RolloutsTab.jsx";
import TargetingTab from "./TargetingTab.jsx";
import VariationsTab from "./VariationsTab.jsx";

const EMPTY_ENV_CONFIG = { enabled: false, offVariationKey: null, strategies: [] };
const EMPTY_TARGETING = {
    individualTargets: [],
    rules: [],
    limits: { maxRules: 0, maxValues: 0, maxIndividualKeys: 0, ruleCount: 0, valueCount: 0, individualKeyCount: 0 },
};

export default function FlagDetailPage({ flagKey, tab }) {
    const { environment, environments, actingAs } = useAppState();
    const [editOpen, setEditOpen] = useState(false);
    const [copied, setCopied] = useState(false);
    const titleRef = useRef(null);
    const { busy, error: actionError, run } = useAction();

    const flagRes = useResource(() => getFlag(flagKey), [flagKey]);
    const auditRes = useResource(() => getAudit(flagKey), [flagKey]);
    const targetingRes = useResource(() => getTargeting(flagKey, environment), [flagKey, environment]);
    const rolloutsRes = useResource(() => listRollouts(flagKey, environment), [flagKey, environment]);
    const segmentsRes = useResource(() => listSegments(environment), [environment]);

    const reloadAll = () => {
        flagRes.reload();
        auditRes.reload();
        targetingRes.reload();
        rolloutsRes.reload();
    };

    useLiveEvents((event) => {
        if (event.type === "flag-changed" && event.flagKey === flagKey) reloadAll();
        if (event.type === "segment-changed") segmentsRes.reload();
    });

    const flag = flagRes.data;
    const rollouts = rolloutsRes.data || [];
    const activeRollout = rollouts.find((rollout) => rollout.status === "ACTIVE") || null;
    const activeTab = ["targeting", "rollouts", "variations", "metrics", "history"].includes(tab) ? tab : "targeting";
    const openTab = (id) => navigate(`/flags/${encodeURIComponent(flagKey)}/${id}`);

    if (flagRes.error?.status === 404) {
        return (
            <div className="page">
                <Link to="/flags" className="back-link">
                    ← Flags
                </Link>
                <div className="empty-state">No flag exists with key "{flagKey}".</div>
            </div>
        );
    }

    if (!flag) {
        return (
            <div className="page">
                <Link to="/flags" className="back-link">
                    ← Flags
                </Link>
                {flagRes.error ? <ErrorBanner error={flagRes.error} /> : <div className="empty-state">Loading flag…</div>}
            </div>
        );
    }

    const readOnly = flag.lifecycle === "ARCHIVED";
    const envConfig = flag.environments?.[environment] || EMPTY_ENV_CONFIG;
    const targeting = targetingRes.data || EMPTY_TARGETING;
    const experimentStrategy = envConfig.strategies.find((strategy) => strategy.type === "EXPERIMENT_SPLIT");

    const tabs = [
        { id: "targeting", label: "Targeting" },
        { id: "rollouts", label: "Rollouts", count: activeRollout ? 1 : null },
        { id: "variations", label: "Variations", count: flag.variations.length },
        { id: "metrics", label: "Metrics" },
        { id: "history", label: "History" },
    ];

    const handleArchiveToggle = async () => {
        const { ok } = await run(() => (flag.lifecycle === "ACTIVE" ? archiveFlag(flagKey, actingAs) : restoreFlag(flagKey, actingAs)));
        if (ok) {
            reloadAll();
            titleRef.current?.focus();
        }
    };

    const copyKey = async () => {
        try {
            await navigator.clipboard.writeText(flag.key);
            setCopied(true);
            window.setTimeout(() => setCopied(false), 2000);
        } catch {
            // Clipboard access can be blocked by permissions; the key is still visible to select manually.
        }
    };

    return (
        <div className="page">
            <nav aria-label="Breadcrumb" className="breadcrumb">
                <Link to="/flags">Flags</Link>
                <span aria-hidden="true">/</span>
                <span aria-current="page">{flag.name}</span>
            </nav>

            <header className="page-header flag-header">
                <div className="flag-title-row">
                    <h1 ref={titleRef} tabIndex={-1}>
                        {flag.name}
                    </h1>
                    <span className="badge">{formatEnumLabel(flag.type)}</span>
                    {readOnly && (
                        <span className="badge" data-tone="muted">
                            Archived
                        </span>
                    )}
                </div>
                {!readOnly && (
                    <button type="button" className="button" onClick={() => setEditOpen(true)}>
                        Edit details
                    </button>
                )}
            </header>

            <ErrorBanner error={actionError || targetingRes.error || rolloutsRes.error} />
            {readOnly && <div className="banner banner-muted">This flag is archived. Restore it to make changes.</div>}

            <div className="detail-layout">
                <div className="detail-main">
                    <TabList tabs={tabs} activeTab={activeTab} onChange={openTab} label="Flag sections" />

                    <div {...tabPanelProps("targeting", activeTab)} className="tab-panel">
                        {activeTab === "targeting" &&
                            (targetingRes.data ? (
                                <TargetingTab
                                    flag={flag}
                                    environment={environment}
                                    envConfig={envConfig}
                                    targeting={targeting}
                                    segments={segmentsRes.data || []}
                                    activeRollout={activeRollout}
                                    readOnly={readOnly}
                                    onChanged={reloadAll}
                                    onOpenTab={openTab}
                                />
                            ) : (
                                <div className="empty-state">Loading targeting…</div>
                            ))}
                    </div>

                    <div {...tabPanelProps("rollouts", activeTab)} className="tab-panel">
                        {activeTab === "rollouts" &&
                            (rolloutsRes.data && targetingRes.data ? (
                                <RolloutsTab
                                    flag={flag}
                                    environment={environment}
                                    envConfig={envConfig}
                                    rules={targeting.rules}
                                    rollouts={rollouts}
                                    readOnly={readOnly}
                                    onChanged={reloadAll}
                                />
                            ) : (
                                <div className="empty-state">Loading rollouts…</div>
                            ))}
                    </div>

                    <div {...tabPanelProps("variations", activeTab)} className="tab-panel">
                        {activeTab === "variations" && <VariationsTab flag={flag} readOnly={readOnly} onChanged={reloadAll} />}
                    </div>

                    <div {...tabPanelProps("metrics", activeTab)} className="tab-panel">
                        {activeTab === "metrics" && (
                            <MetricsTab
                                flag={flag}
                                environment={environment}
                                actingAs={actingAs}
                                readOnly={readOnly}
                                strategy={experimentStrategy}
                                onChanged={reloadAll}
                            />
                        )}
                    </div>

                    <div {...tabPanelProps("history", activeTab)} className="tab-panel">
                        {activeTab === "history" && <HistoryTab audit={auditRes.data} />}
                    </div>
                </div>

                <aside className="detail-rail" aria-label="Flag details">
                    <div className="rail-field">
                        <span className="sidebar-label">Key</span>
                        <div className="rail-key-row">
                            <code className="flag-key">{flag.key}</code>
                            <button type="button" className="button button-ghost" onClick={copyKey}>
                                {copied ? "Copied" : "Copy"}
                            </button>
                        </div>
                    </div>

                    {flag.description && (
                        <div className="rail-field">
                            <span className="sidebar-label">Description</span>
                            <p>{flag.description}</p>
                        </div>
                    )}

                    <div className="rail-field">
                        <span className="sidebar-label">Owner</span>
                        <p>{flag.owner || "—"}</p>
                    </div>

                    <div className="rail-field">
                        <span className="sidebar-label">Environments</span>
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
                    </div>

                    {flag.dependsOn?.length > 0 && (
                        <div className="rail-field">
                            <span className="sidebar-label">Depends on</span>
                            <ul className="rail-links">
                                {flag.dependsOn.map((dependency) => (
                                    <li key={dependency}>
                                        <Link to={`/flags/${encodeURIComponent(dependency)}`}>{dependency}</Link>
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}

                    <div className="rail-field">
                        <span className="sidebar-label">Lifecycle status</span>
                        <span className="badge" data-tone={flag.lifecycle === "ARCHIVED" ? "muted" : "positive"}>
                            {flag.lifecycle === "ACTIVE" ? "Live" : "Archived"}
                        </span>
                    </div>

                    <div className="rail-field">
                        <span className="sidebar-label">Created</span>
                        <p>{formatRelativeTime(flag.createdAt)}</p>
                    </div>

                    <div className="rail-field">
                        <span className="sidebar-label">Updated</span>
                        <p>{formatRelativeTime(flag.updatedAt)}</p>
                    </div>

                    <button type="button" className="button button-ghost" disabled={busy} onClick={handleArchiveToggle}>
                        {flag.lifecycle === "ACTIVE" ? "Archive flag" : "Restore flag"}
                    </button>
                </aside>
            </div>

            {editOpen && (
                <EditFlagDialog
                    flag={flag}
                    onClose={() => setEditOpen(false)}
                    onSaved={() => {
                        setEditOpen(false);
                        reloadAll();
                    }}
                />
            )}
        </div>
    );
}
