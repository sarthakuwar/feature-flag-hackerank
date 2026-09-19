import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { updateEnvironment } from "../../shared/api/flags.js";
import EnvironmentSwitcher from "../../shared/EnvironmentSwitcher.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import { pluralize } from "../../shared/format.js";
import EvaluatePanel from "./EvaluatePanel.jsx";
import IndividualTargetsPanel from "./IndividualTargetsPanel.jsx";
import RulesPanel from "./RulesPanel.jsx";
import StrategiesPanel, { toStrategyRequest } from "./StrategiesPanel.jsx";

function EvaluationOrder({ enabled, targeting, strategyCount, offVariationName }) {
    const individualCount = targeting.limits.individualKeyCount;
    const steps = [
        { title: "Flag switch", detail: enabled ? "On" : "Off — serves the off variation" },
        { title: "Individual targets", detail: pluralize(individualCount, "key") },
        { title: "Targeting rules", detail: pluralize(targeting.rules.length, "rule") },
        { title: "Default strategies", detail: pluralize(strategyCount, "strategy", "strategies") },
        { title: "Off variation", detail: offVariationName },
    ];

    return (
        <ol className="eval-order" aria-label="Evaluation order">
            {steps.map((step, index) => (
                <li key={step.title}>
                    <span className="eval-order-index" aria-hidden="true">
                        {index + 1}
                    </span>
                    <span className="eval-order-title">{step.title}</span>
                    <span className="eval-order-detail">{step.detail}</span>
                </li>
            ))}
        </ol>
    );
}

export default function TargetingTab({ flag, environment, envConfig, targeting, segments, activeRollout, readOnly, onChanged, onOpenTab }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();

    const saveEnvironment = async (overrides) => {
        const { ok } = await run(() =>
            updateEnvironment(flag.key, environment, {
                enabled: envConfig.enabled,
                offVariationKey: envConfig.offVariationKey,
                strategies: envConfig.strategies.map(toStrategyRequest),
                changedBy: actingAs,
                ...overrides,
            }),
        );
        if (ok) onChanged();
        return ok;
    };

    const offVariation = flag.variations.find((variation) => variation.key === envConfig.offVariationKey);

    return (
        <>
            <section className="panel" aria-labelledby="switch-heading">
                <div className="panel-header">
                    <div>
                        <h2 id="switch-heading">Flag status</h2>
                        <p className="hint">Each environment has its own switch, targets, and rules.</p>
                    </div>
                    <EnvironmentSwitcher label="Targeting for" className="inline-env-switcher" />
                </div>

                <div className="status-row">
                    <label className="switch">
                        <input
                            type="checkbox"
                            checked={envConfig.enabled}
                            disabled={busy || readOnly}
                            onChange={() => saveEnvironment({ enabled: !envConfig.enabled })}
                        />
                        <span>{envConfig.enabled ? "On" : "Off"}</span>
                    </label>
                    <label className="field status-off">
                        <span>When off, and for everyone no rule matches, serve</span>
                        <select
                            value={envConfig.offVariationKey || ""}
                            disabled={busy || readOnly}
                            onChange={(event) => saveEnvironment({ offVariationKey: event.target.value || null })}
                        >
                            <option value="">No default variation</option>
                            {flag.variations.map((variation) => (
                                <option key={variation.key} value={variation.key}>
                                    {variation.name}
                                </option>
                            ))}
                        </select>
                    </label>
                </div>

                <EvaluationOrder
                    enabled={envConfig.enabled}
                    targeting={targeting}
                    strategyCount={envConfig.strategies.length}
                    offVariationName={offVariation?.name || "None"}
                />

                <ErrorBanner error={error} />
            </section>

            <IndividualTargetsPanel flag={flag} environment={environment} targeting={targeting} readOnly={readOnly} onChanged={onChanged} />

            <RulesPanel
                flag={flag}
                environment={environment}
                targeting={targeting}
                segments={segments}
                activeRollout={activeRollout}
                readOnly={readOnly}
                onChanged={onChanged}
                onOpenRollouts={() => onOpenTab("rollouts")}
            />

            <StrategiesPanel
                flag={flag}
                strategies={envConfig.strategies}
                activeRollout={activeRollout}
                readOnly={readOnly}
                busy={busy}
                onSave={(strategies) => saveEnvironment({ strategies })}
                onOpenMetrics={() => onOpenTab("metrics")}
                onOpenRollouts={() => onOpenTab("rollouts")}
            />

            <EvaluatePanel flagKey={flag.key} environment={environment} rules={targeting.rules} />
        </>
    );
}
