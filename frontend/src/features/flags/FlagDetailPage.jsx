import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "../../shared/router.jsx";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import EnvironmentSwitcher from "../../shared/EnvironmentSwitcher.jsx";
import {
    getFlag,
    getAudit,
    archiveFlag,
    restoreFlag,
    addVariation,
    removeVariation,
    updateEnvironment,
} from "../../shared/api/flags.js";
import { formatEnumLabel, formatRelativeTime, variationColor } from "../../shared/format.js";
import StrategyForm from "./StrategyForm.jsx";
import MetricsTab from "./MetricsTab.jsx";
import EvaluatePanel from "./EvaluatePanel.jsx";

const EMPTY_ENV_CONFIG = { enabled: false, offVariationKey: null, strategies: [] };

const TABS = [
    { id: "targeting", label: "Targeting" },
    { id: "variations", label: "Variations" },
    { id: "metrics", label: "Metrics" },
    { id: "history", label: "History" },
];

function summarizeStrategy(strategy) {
    if (strategy.type === "USER_ID") return `${strategy.userIds.length} user ID${strategy.userIds.length === 1 ? "" : "s"}`;
    if (strategy.type === "ATTRIBUTE") return `${strategy.attribute} = ${strategy.attributeValue}`;
    if (strategy.type === "PERCENTAGE_ROLLOUT") return `${strategy.percentage}% of traffic`;
    if (strategy.type === "EXPERIMENT_SPLIT") {
        return Object.entries(strategy.variationWeights || {})
            .map(([key, weight]) => `${key} ${weight}%`)
            .join(", ");
    }
    return "";
}

function toStrategyRequest(strategy) {
    return {
        id: strategy.id,
        type: strategy.type,
        userIds: strategy.userIds,
        attribute: strategy.attribute,
        attributeValue: strategy.attributeValue,
        percentage: strategy.percentage,
        servedVariationKey: strategy.servedVariationKey,
        variationWeights: strategy.variationWeights,
        banditAlgorithm: strategy.banditAlgorithm,
        epsilon: strategy.epsilon,
    };
}

function TabList({ activeTab, onChange }) {
    const tabRefs = useRef({});

    const focusTab = (id) => {
        onChange(id);
        tabRefs.current[id]?.focus();
    };

    const handleKeyDown = (event) => {
        const index = TABS.findIndex((tab) => tab.id === activeTab);
        if (event.key === "ArrowRight") {
            event.preventDefault();
            focusTab(TABS[(index + 1) % TABS.length].id);
        } else if (event.key === "ArrowLeft") {
            event.preventDefault();
            focusTab(TABS[(index - 1 + TABS.length) % TABS.length].id);
        } else if (event.key === "Home") {
            event.preventDefault();
            focusTab(TABS[0].id);
        } else if (event.key === "End") {
            event.preventDefault();
            focusTab(TABS[TABS.length - 1].id);
        }
    };

    return (
        <div className="tablist" role="tablist" aria-label="Flag sections" onKeyDown={handleKeyDown}>
            {TABS.map((tab) => (
                <button
                    key={tab.id}
                    ref={(el) => {
                        tabRefs.current[tab.id] = el;
                    }}
                    type="button"
                    role="tab"
                    id={`tab-${tab.id}`}
                    aria-selected={activeTab === tab.id}
                    aria-controls={`panel-${tab.id}`}
                    tabIndex={activeTab === tab.id ? 0 : -1}
                    className="tab"
                    onClick={() => onChange(tab.id)}
                >
                    {tab.label}
                </button>
            ))}
        </div>
    );
}

export default function FlagDetailPage({ flagKey }) {
    const { environment, actingAs } = useAppState();
    const [flag, setFlag] = useState(null);
    const [audit, setAudit] = useState(null);
    const [error, setError] = useState(null);
    const [notFound, setNotFound] = useState(false);
    const [busy, setBusy] = useState(false);
    const [activeTab, setActiveTab] = useState("targeting");
    const [editingStrategyId, setEditingStrategyId] = useState(null);
    const [addingStrategy, setAddingStrategy] = useState(false);
    const [newVariation, setNewVariation] = useState({ key: "", name: "", value: "" });
    const [historySearch, setHistorySearch] = useState("");
    const [copied, setCopied] = useState(false);
    const titleRef = useRef(null);
    const variationsHeadingRef = useRef(null);
    const rulesHeadingRef = useRef(null);

    const reload = useCallback(() => {
        setError(null);
        setNotFound(false);
        Promise.all([getFlag(flagKey), getAudit(flagKey)])
            .then(([flagData, auditData]) => {
                setFlag(flagData);
                setAudit(auditData);
            })
            .catch((err) => {
                if (err.message.toLowerCase().includes("not found")) setNotFound(true);
                setError(err.message);
            });
    }, [flagKey]);

    useEffect(() => {
        setFlag(null);
        setAudit(null);
        setActiveTab("targeting");
        setEditingStrategyId(null);
        setAddingStrategy(false);
        reload();
    }, [reload]);

    const withBusy = async (action) => {
        setBusy(true);
        setError(null);
        try {
            await action();
            reload();
            return true;
        } catch (err) {
            setError(err.message);
            return false;
        } finally {
            setBusy(false);
        }
    };

    if (notFound) {
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
                {error ? <div className="banner banner-error">{error}</div> : <div className="empty-state">Loading flag…</div>}
            </div>
        );
    }

    const readOnly = flag.lifecycle === "ARCHIVED";
    const variationsLocked = flag.type === "BOOLEAN";
    const envConfig = flag.environments?.[environment] || EMPTY_ENV_CONFIG;
    const experimentStrategy = envConfig.strategies.find((s) => s.type === "EXPERIMENT_SPLIT");

    const saveEnvironment = (overrides) =>
        withBusy(() =>
            updateEnvironment(flagKey, environment, {
                enabled: envConfig.enabled,
                offVariationKey: envConfig.offVariationKey,
                strategies: envConfig.strategies.map(toStrategyRequest),
                changedBy: actingAs,
                ...overrides,
            }),
        );

    const toggleEnabled = () => saveEnvironment({ enabled: !envConfig.enabled });
    const setOffVariation = (key) => saveEnvironment({ offVariationKey: key || null });

    const submitStrategy = async (payload) => {
        const strategies = editingStrategyId
            ? envConfig.strategies.map((s) => (s.id === editingStrategyId ? payload : toStrategyRequest(s)))
            : [...envConfig.strategies.map(toStrategyRequest), payload];
        const ok = await saveEnvironment({ strategies });
        if (ok) {
            setEditingStrategyId(null);
            setAddingStrategy(false);
            rulesHeadingRef.current?.focus();
        }
    };

    const removeStrategy = async (id) => {
        const strategies = envConfig.strategies.filter((s) => s.id !== id).map(toStrategyRequest);
        const ok = await saveEnvironment({ strategies });
        if (ok) rulesHeadingRef.current?.focus();
    };

    const moveStrategy = async (index, direction) => {
        const reordered = [...envConfig.strategies];
        const target = index + direction;
        if (target < 0 || target >= reordered.length) return;
        [reordered[index], reordered[target]] = [reordered[target], reordered[index]];
        const ok = await saveEnvironment({ strategies: reordered.map(toStrategyRequest) });
        if (ok) rulesHeadingRef.current?.focus();
    };

    const handleArchiveToggle = async () => {
        const ok = await withBusy(() => (flag.lifecycle === "ACTIVE" ? archiveFlag(flagKey, actingAs) : restoreFlag(flagKey, actingAs)));
        if (ok) titleRef.current?.focus();
    };

    const submitNewVariation = async (event) => {
        event.preventDefault();
        const ok = await withBusy(() => addVariation(flagKey, { ...newVariation, changedBy: actingAs }));
        if (ok) {
            setNewVariation({ key: "", name: "", value: "" });
            variationsHeadingRef.current?.focus();
        }
    };

    const removeVariationRow = async (variationKey) => {
        const ok = await withBusy(() => removeVariation(flagKey, variationKey, actingAs));
        if (ok) variationsHeadingRef.current?.focus();
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

    const filteredAudit =
        audit === null
            ? null
            : audit.filter((event) => {
                  const term = historySearch.trim().toLowerCase();
                  if (!term) return true;
                  return (
                      event.summary.toLowerCase().includes(term) ||
                      event.changedBy.toLowerCase().includes(term) ||
                      event.action.toLowerCase().includes(term)
                  );
              });

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
                </div>
            </header>

            {error && <div className="banner banner-error">{error}</div>}
            {readOnly && <div className="banner banner-muted">This flag is archived. Restore it to make changes.</div>}

            <div className="detail-layout">
                <div className="detail-main">
                    <TabList activeTab={activeTab} onChange={setActiveTab} />

                    <div id="panel-targeting" role="tabpanel" aria-labelledby="tab-targeting" hidden={activeTab !== "targeting"}>
                        <section className="panel">
                            <div className="panel-header">
                                <EnvironmentSwitcher label="Targeting for" className="inline-env-switcher" />
                                <label className="switch">
                                    <input type="checkbox" checked={envConfig.enabled} disabled={busy || readOnly} onChange={toggleEnabled} />
                                    <span>{envConfig.enabled ? "On" : "Off"}</span>
                                </label>
                            </div>

                            <label className="field">
                                <span>When off, serve</span>
                                <select
                                    value={envConfig.offVariationKey || ""}
                                    disabled={busy || readOnly}
                                    onChange={(event) => setOffVariation(event.target.value)}
                                >
                                    <option value="">No default variation</option>
                                    {flag.variations.map((variation) => (
                                        <option key={variation.key} value={variation.key}>
                                            {variation.name}
                                        </option>
                                    ))}
                                </select>
                            </label>

                            <h2 ref={rulesHeadingRef} tabIndex={-1}>
                                Targeting &amp; rollout rules
                            </h2>
                            <p className="hint">Evaluated top to bottom. The first match wins.</p>
                            {envConfig.strategies.length === 0 && !addingStrategy && (
                                <p className="hint">No rules yet — everyone gets the off variation above.</p>
                            )}
                            <ol className="strategy-list">
                                {envConfig.strategies.map((strategy, index) =>
                                    strategy.type === "EXPERIMENT_SPLIT" ? (
                                        <li key={strategy.id} className="strategy-item">
                                            <div className="strategy-summary">
                                                <span className="badge">Experiment split</span>
                                                <span>{summarizeStrategy(strategy)}</span>
                                                {strategy.banditAlgorithm !== "NONE" && (
                                                    <span className="badge" data-tone="positive">
                                                        {formatEnumLabel(strategy.banditAlgorithm)}
                                                    </span>
                                                )}
                                            </div>
                                            <div className="strategy-actions">
                                                <button type="button" className="button button-ghost" onClick={() => setActiveTab("metrics")}>
                                                    Manage in Metrics tab
                                                </button>
                                            </div>
                                        </li>
                                    ) : editingStrategyId === strategy.id ? (
                                        <li key={strategy.id} className="strategy-item">
                                            <StrategyForm
                                                strategy={strategy}
                                                variations={flag.variations}
                                                submitting={busy}
                                                onSubmit={submitStrategy}
                                                onCancel={() => setEditingStrategyId(null)}
                                            />
                                        </li>
                                    ) : (
                                        <li key={strategy.id} className="strategy-item">
                                            <div className="strategy-summary">
                                                <span className="badge">{formatEnumLabel(strategy.type)}</span>
                                                <span>{summarizeStrategy(strategy)}</span>
                                                <span className="strategy-arrow" aria-hidden="true">
                                                    →
                                                </span>
                                                <span
                                                    className="variation-dot"
                                                    aria-hidden="true"
                                                    style={{
                                                        background: variationColor(
                                                            flag.variations.findIndex((v) => v.key === strategy.servedVariationKey),
                                                        ),
                                                    }}
                                                />
                                                <strong>{strategy.servedVariationKey}</strong>
                                            </div>
                                            {!readOnly && (
                                                <div className="strategy-actions">
                                                    <button
                                                        type="button"
                                                        className="button button-ghost"
                                                        disabled={busy || index === 0}
                                                        onClick={() => moveStrategy(index, -1)}
                                                        aria-label="Move up"
                                                    >
                                                        ↑
                                                    </button>
                                                    <button
                                                        type="button"
                                                        className="button button-ghost"
                                                        disabled={busy || index === envConfig.strategies.length - 1}
                                                        onClick={() => moveStrategy(index, 1)}
                                                        aria-label="Move down"
                                                    >
                                                        ↓
                                                    </button>
                                                    <button
                                                        type="button"
                                                        className="button button-ghost"
                                                        disabled={busy}
                                                        onClick={() => setEditingStrategyId(strategy.id)}
                                                    >
                                                        Edit
                                                    </button>
                                                    <button type="button" className="button button-ghost" disabled={busy} onClick={() => removeStrategy(strategy.id)}>
                                                        Remove
                                                    </button>
                                                </div>
                                            )}
                                        </li>
                                    ),
                                )}
                            </ol>

                            {!readOnly &&
                                (addingStrategy ? (
                                    <StrategyForm
                                        variations={flag.variations}
                                        submitting={busy}
                                        onSubmit={submitStrategy}
                                        onCancel={() => setAddingStrategy(false)}
                                    />
                                ) : (
                                    <button type="button" className="button" onClick={() => setAddingStrategy(true)}>
                                        Add rule
                                    </button>
                                ))}
                        </section>

                        <EvaluatePanel flagKey={flag.key} environment={environment} />
                    </div>

                    <div id="panel-variations" role="tabpanel" aria-labelledby="tab-variations" hidden={activeTab !== "variations"}>
                        <section className="panel">
                            <h2 ref={variationsHeadingRef} tabIndex={-1}>
                                Variations
                            </h2>
                            {variationsLocked && <p className="hint">Boolean flags always have exactly two variations, true and false.</p>}
                            <ul className="variation-list">
                                {flag.variations.map((variation, index) => (
                                    <li key={variation.key} className="variation-item">
                                        <div>
                                            <span className="variation-dot" style={{ background: variationColor(index) }} aria-hidden="true" />
                                            <strong>{variation.name}</strong>
                                            <code className="flag-key">{variation.key}</code>
                                        </div>
                                        <span className="variation-value">{variation.value}</span>
                                        {!variationsLocked && (
                                            <button
                                                type="button"
                                                className="button button-ghost"
                                                disabled={busy || readOnly}
                                                onClick={() => removeVariationRow(variation.key)}
                                            >
                                                Remove
                                            </button>
                                        )}
                                    </li>
                                ))}
                            </ul>
                            {!readOnly && !variationsLocked && (
                                <form className="variation-row" onSubmit={submitNewVariation}>
                                    <input
                                        placeholder="key"
                                        value={newVariation.key}
                                        onChange={(event) => setNewVariation((v) => ({ ...v, key: event.target.value }))}
                                        required
                                    />
                                    <input
                                        placeholder="name"
                                        value={newVariation.name}
                                        onChange={(event) => setNewVariation((v) => ({ ...v, name: event.target.value }))}
                                        required
                                    />
                                    <input
                                        placeholder="value"
                                        value={newVariation.value}
                                        onChange={(event) => setNewVariation((v) => ({ ...v, value: event.target.value }))}
                                        required
                                    />
                                    <button type="submit" className="button" disabled={busy}>
                                        Add
                                    </button>
                                </form>
                            )}
                        </section>
                    </div>

                    <div id="panel-metrics" role="tabpanel" aria-labelledby="tab-metrics" hidden={activeTab !== "metrics"}>
                        <MetricsTab
                            flag={flag}
                            environment={environment}
                            actingAs={actingAs}
                            readOnly={readOnly}
                            strategy={experimentStrategy}
                            onChanged={reload}
                        />
                    </div>

                    <div id="panel-history" role="tabpanel" aria-labelledby="tab-history" hidden={activeTab !== "history"}>
                        <section className="panel">
                            <h2>Flag history</h2>
                            <input
                                type="search"
                                className="search-input"
                                placeholder="Search history…"
                                value={historySearch}
                                onChange={(event) => setHistorySearch(event.target.value)}
                                aria-label="Search flag history"
                            />
                            {filteredAudit === null ? (
                                <p className="hint">Loading history…</p>
                            ) : filteredAudit.length === 0 ? (
                                <p className="hint">{historySearch ? "No matching history." : "No changes recorded yet."}</p>
                            ) : (
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
                                        {filteredAudit.map((event) => (
                                            <tr key={event.id}>
                                                <td>
                                                    <span className="badge">{formatEnumLabel(event.action)}</span>
                                                </td>
                                                <td>{event.summary}</td>
                                                <td>{event.changedBy}</td>
                                                <td>{formatRelativeTime(event.changedAt)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            )}
                        </section>
                    </div>
                </div>

                <aside className="detail-rail">
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

                    {flag.dependsOn?.length > 0 && (
                        <div className="rail-field">
                            <span className="sidebar-label">Depends on</span>
                            <p>{flag.dependsOn.join(", ")}</p>
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
        </div>
    );
}
