import { useCallback, useEffect, useState } from "react";
import {
    getExperimentMetrics,
    setExperimentWeights,
    configureBandit,
    reallocateExperiment,
    promoteExperiment,
    recordConversion,
} from "../../shared/api/flags.js";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import { formatEnumLabel, pluralize, variationColor } from "../../shared/format.js";

const BANDIT_OPTIONS = [
    { value: "NONE", label: "None (fixed weights)" },
    { value: "EPSILON_GREEDY", label: "Epsilon greedy" },
    { value: "THOMPSON_SAMPLING", label: "Thompson sampling" },
];

function formatPercent(rate) {
    return `${(rate * 100).toFixed(1)}%`;
}

function evenSplit(variations) {
    return Object.fromEntries(variations.map((variation) => [variation.key, Math.round(100 / variations.length)]));
}

export default function MetricsTab({ flag, environment, actingAs, readOnly, strategy, onChanged }) {
    const [metrics, setMetrics] = useState(null);
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(false);
    const [eventTypeDraft, setEventTypeDraft] = useState("");
    const [eventType, setEventType] = useState("");
    const [weightsDraft, setWeightsDraft] = useState(() =>
        strategy ? { ...strategy.variationWeights } : evenSplit(flag.variations),
    );
    const [banditAlgorithm, setBanditAlgorithm] = useState(strategy?.banditAlgorithm || "NONE");
    const [epsilon, setEpsilon] = useState(strategy?.epsilon ?? 0.2);
    const [promoteKey, setPromoteKey] = useState(flag.variations[0]?.key || "");
    const [conversionForm, setConversionForm] = useState({
        variationKey: flag.variations[0]?.key || "",
        userId: "test-user-1",
        eventType: "conversion",
        value: "",
    });

    const loadMetrics = useCallback(() => {
        setError(null);
        getExperimentMetrics(flag.key, environment, eventType)
            .then(setMetrics)
            .catch(setError);
    }, [flag.key, environment, eventType]);

    useEffect(() => {
        setMetrics(null);
        loadMetrics();
    }, [loadMetrics]);

    useEffect(() => {
        setWeightsDraft(strategy ? { ...strategy.variationWeights } : evenSplit(flag.variations));
        setBanditAlgorithm(strategy?.banditAlgorithm || "NONE");
        setEpsilon(strategy?.epsilon ?? 0.2);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [strategy, environment]);

    const withBusy = async (action) => {
        setBusy(true);
        setError(null);
        try {
            await action();
            loadMetrics();
            onChanged();
            return true;
        } catch (err) {
            setError(err);
            return false;
        } finally {
            setBusy(false);
        }
    };

    const hasExperiment = Boolean(strategy);
    const weightSum = Object.values(weightsDraft).reduce((sum, value) => sum + Number(value || 0), 0);
    const maxImpressions = metrics ? Math.max(1, ...metrics.variations.map((v) => v.impressions)) : 1;

    const submitWeights = (event) => {
        event.preventDefault();
        const weights = Object.fromEntries(Object.entries(weightsDraft).map(([key, value]) => [key, Number(value)]));
        withBusy(() => setExperimentWeights(flag.key, environment, weights, actingAs));
    };

    const submitBandit = (event) => {
        event.preventDefault();
        withBusy(() =>
            configureBandit(
                flag.key,
                environment,
                banditAlgorithm,
                banditAlgorithm === "EPSILON_GREEDY" ? Number(epsilon) : null,
                actingAs,
            ),
        );
    };

    const runReallocate = () => withBusy(() => reallocateExperiment(flag.key, environment, actingAs));

    const runPromote = () => withBusy(() => promoteExperiment(flag.key, environment, promoteKey, actingAs));

    const submitConversion = (event) => {
        event.preventDefault();
        withBusy(() =>
            recordConversion({
                flagKey: flag.key,
                environment,
                variationKey: conversionForm.variationKey,
                userId: conversionForm.userId,
                eventType: conversionForm.eventType,
                value: conversionForm.value === "" ? null : Number(conversionForm.value),
            }),
        );
    };

    return (
        <section className="panel" aria-labelledby="metrics-heading">
            <h2 id="metrics-heading">Experiment metrics</h2>

            <ErrorBanner error={error} />

            {hasExperiment ? (
                <p className="meta-line">
                    Allocation: {formatEnumLabel(strategy.banditAlgorithm)}
                    {strategy.banditAlgorithm === "EPSILON_GREEDY" && strategy.epsilon != null && <> · ε = {strategy.epsilon}</>}
                </p>
            ) : (
                <p className="hint">No experiment split configured for {formatEnumLabel(environment)} yet. Set variation weights below to start one.</p>
            )}

            <form
                className="toolbar"
                onSubmit={(event) => {
                    event.preventDefault();
                    setEventType(eventTypeDraft.trim());
                }}
            >
                <label className="field metrics-filter">
                    <span>Conversion event type</span>
                    <input
                        type="search"
                        value={eventTypeDraft}
                        onChange={(event) => setEventTypeDraft(event.target.value)}
                        placeholder="All event types, e.g. purchase"
                    />
                </label>
                <button type="submit" className="button">
                    Apply filter
                </button>
            </form>

            {metrics?.excludedImpressions > 0 && (
                <p className="hint">
                    {pluralize(metrics.excludedImpressions, "evaluation")} left out: they came from individual targets or rules that aren’t counted in the
                    experiment.
                </p>
            )}

            {metrics === null && !error ? (
                <p className="hint">Loading metrics…</p>
            ) : (
                metrics && (
                    <ul className="metrics-list">
                        {metrics.variations.map((variationMetric, index) => (
                            <li key={variationMetric.variationKey} className="metrics-item">
                                <div className="metrics-item-header">
                                    <span className="variation-dot" style={{ background: variationColor(index) }} aria-hidden="true" />
                                    <strong>{variationMetric.variationKey}</strong>
                                    {variationMetric.currentWeight != null && <span className="badge">{variationMetric.currentWeight}% traffic</span>}
                                </div>
                                <div className="metrics-bar-track">
                                    <div
                                        className="metrics-bar-fill"
                                        style={{
                                            width: `${(variationMetric.impressions / maxImpressions) * 100}%`,
                                            background: variationColor(index),
                                        }}
                                    />
                                </div>
                                <div className="metrics-numbers">
                                    <span>{variationMetric.impressions} impressions</span>
                                    <span>{variationMetric.conversions} conversions</span>
                                    <span>{formatPercent(variationMetric.conversionRate)} rate</span>
                                </div>
                            </li>
                        ))}
                    </ul>
                )
            )}

            {!readOnly && (
                <>
                    <h3>Set variation weights</h3>
                    <form className="weights-form" onSubmit={submitWeights}>
                        <div className="weights-row">
                            {flag.variations.map((variation) => (
                                <label key={variation.key} className="field weight-field">
                                    <span>{variation.name}</span>
                                    <input
                                        type="number"
                                        min="0"
                                        max="100"
                                        value={weightsDraft[variation.key] ?? 0}
                                        onChange={(event) => setWeightsDraft((prev) => ({ ...prev, [variation.key]: event.target.value }))}
                                    />
                                </label>
                            ))}
                        </div>
                        <p className="hint">Sum: {weightSum}{weightSum !== 100 ? " — must equal 100" : ""}</p>
                        <button type="submit" className="button button-primary" disabled={busy || weightSum !== 100}>
                            Save weights
                        </button>
                    </form>

                    <h3>Bandit allocation</h3>
                    {!hasExperiment && <p className="hint">Set variation weights first to enable bandit allocation.</p>}
                    <form className="form" onSubmit={submitBandit}>
                        <label className="field">
                            <span>Algorithm</span>
                            <select value={banditAlgorithm} disabled={!hasExperiment} onChange={(event) => setBanditAlgorithm(event.target.value)}>
                                {BANDIT_OPTIONS.map((option) => (
                                    <option key={option.value} value={option.value}>
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                        </label>
                        {banditAlgorithm === "EPSILON_GREEDY" && (
                            <label className="field">
                                <span>Epsilon (exploration rate, 0-1)</span>
                                <input
                                    type="number"
                                    min="0.01"
                                    max="1"
                                    step="0.01"
                                    value={epsilon}
                                    onChange={(event) => setEpsilon(event.target.value)}
                                    required
                                />
                            </label>
                        )}
                        <div className="button-row">
                            <button type="submit" className="button" disabled={busy || !hasExperiment}>
                                Save bandit config
                            </button>
                            <button
                                type="button"
                                className="button"
                                disabled={busy || !hasExperiment || strategy?.banditAlgorithm === "NONE"}
                                onClick={runReallocate}
                            >
                                Reallocate now
                            </button>
                        </div>
                    </form>

                    <h3>Promote a winner</h3>
                    <p className="hint">Sets the chosen variation to 100% of traffic and turns off the bandit.</p>
                    <div className="variation-row">
                        <select value={promoteKey} onChange={(event) => setPromoteKey(event.target.value)}>
                            {flag.variations.map((variation) => (
                                <option key={variation.key} value={variation.key}>
                                    {variation.name}
                                </option>
                            ))}
                        </select>
                        <button type="button" className="button button-primary" disabled={busy} onClick={runPromote}>
                            Promote to 100%
                        </button>
                    </div>

                    <h3>Record a test event</h3>
                    <p className="hint">Simulates what an SDK would send after a conversion, so you can watch metrics move.</p>
                    <form className="form" onSubmit={submitConversion}>
                        <label className="field">
                            <span>Variation</span>
                            <select
                                value={conversionForm.variationKey}
                                onChange={(event) => setConversionForm((f) => ({ ...f, variationKey: event.target.value }))}
                            >
                                {flag.variations.map((variation) => (
                                    <option key={variation.key} value={variation.key}>
                                        {variation.name}
                                    </option>
                                ))}
                            </select>
                        </label>
                        <label className="field">
                            <span>User ID</span>
                            <input
                                value={conversionForm.userId}
                                onChange={(event) => setConversionForm((f) => ({ ...f, userId: event.target.value }))}
                                required
                            />
                        </label>
                        <label className="field">
                            <span>Event type</span>
                            <input
                                value={conversionForm.eventType}
                                onChange={(event) => setConversionForm((f) => ({ ...f, eventType: event.target.value }))}
                                required
                            />
                        </label>
                        <label className="field">
                            <span>Value (optional)</span>
                            <input
                                type="number"
                                step="any"
                                value={conversionForm.value}
                                onChange={(event) => setConversionForm((f) => ({ ...f, value: event.target.value }))}
                            />
                        </label>
                        <button type="submit" className="button" disabled={busy}>
                            Record conversion
                        </button>
                    </form>
                </>
            )}
        </section>
    );
}
