import { useState } from "react";
import { evaluateAllFlags, evaluateFlag } from "../../shared/api/flags.js";
import ContextBuilder, { emptyEvaluationContext, toEvaluationRequest } from "../../shared/ContextBuilder.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import { formatEnumLabel } from "../../shared/format.js";

export function describeMatch(result, rules) {
    if (result.matchedRuleId) {
        const rule = rules.find((entry) => entry.id === result.matchedRuleId);
        return rule ? `Rule “${rule.description || "Untitled rule"}”` : `Rule ${result.matchedRuleId.slice(0, 8)}`;
    }
    if (result.matchedStrategyId) return `Strategy ${result.matchedStrategyId.slice(0, 8)}`;
    return null;
}

export default function EvaluatePanel({ flagKey, environment, rules }) {
    const { busy, error, run } = useAction();
    const [context, setContext] = useState(emptyEvaluationContext);
    const [recordImpression, setRecordImpression] = useState(false);
    const [result, setResult] = useState(null);

    const handleSubmit = async (event) => {
        event.preventDefault();
        setResult(null);
        const body = toEvaluationRequest(context);
        const { ok, result: evaluation } = await run(async () => {
            if (recordImpression) return evaluateFlag(flagKey, environment, body);
            const all = await evaluateAllFlags(environment, body);
            return all.find((entry) => entry.flagKey === flagKey);
        });
        if (ok) setResult(evaluation || null);
    };

    const match = result ? describeMatch(result, rules) : null;

    return (
        <section className="panel" aria-labelledby="evaluate-heading">
            <div>
                <h2 id="evaluate-heading">Test evaluation</h2>
                <p className="hint">See which variation a context would get in {formatEnumLabel(environment)} right now.</p>
            </div>

            <form className="form" onSubmit={handleSubmit}>
                <ContextBuilder value={context} onChange={setContext} />

                <label className="check">
                    <input type="checkbox" checked={recordImpression} onChange={(event) => setRecordImpression(event.target.checked)} />
                    <span>Record an impression so this evaluation appears in experiment metrics</span>
                </label>

                <div>
                    <button type="submit" className="button button-primary" disabled={busy}>
                        {busy ? "Evaluating…" : "Evaluate"}
                    </button>
                </div>
            </form>

            <ErrorBanner error={error} />

            {result && (
                <div className="eval-result" role="status">
                    <div className="eval-result-row">
                        <span className="badge" data-tone="positive">
                            {result.variationKey}
                        </span>
                        <code className="flag-key">{result.value}</code>
                    </div>
                    <p className="meta-line">
                        Reason: {formatEnumLabel(result.reason)}
                        {match && <> · {match}</>}
                        {!result.includedInExperiment && <> · Not counted in experiment metrics</>}
                    </p>
                </div>
            )}
        </section>
    );
}
