import { useState } from "react";
import { evaluateFlag } from "../../shared/api/flags.js";
import { formatEnumLabel } from "../../shared/format.js";

export default function EvaluatePanel({ flagKey, environment }) {
    const [userId, setUserId] = useState("test-user");
    const [attributes, setAttributes] = useState([{ key: "", value: "" }]);
    const [result, setResult] = useState(null);
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(false);

    const updateAttribute = (index, field, value) => {
        setAttributes((prev) => prev.map((attr, i) => (i === index ? { ...attr, [field]: value } : attr)));
    };
    const addAttribute = () => setAttributes((prev) => [...prev, { key: "", value: "" }]);
    const removeAttribute = (index) => setAttributes((prev) => prev.filter((_, i) => i !== index));

    const handleSubmit = async (event) => {
        event.preventDefault();
        setBusy(true);
        setError(null);
        setResult(null);
        try {
            const attributeMap = Object.fromEntries(
                attributes.filter((attr) => attr.key.trim()).map((attr) => [attr.key.trim(), attr.value]),
            );
            const evaluation = await evaluateFlag(flagKey, environment, { userId: userId.trim() || null, attributes: attributeMap });
            setResult(evaluation);
        } catch (err) {
            setError(err.message);
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="panel">
            <h2>Test evaluation</h2>
            <p className="hint">See which variation a given user would get in {formatEnumLabel(environment)} right now.</p>

            <form className="form" onSubmit={handleSubmit}>
                <label className="field">
                    <span>User ID</span>
                    <input value={userId} onChange={(event) => setUserId(event.target.value)} placeholder="user-123" />
                </label>

                <div className="field">
                    <span>Attributes</span>
                    <div className="variation-rows">
                        {attributes.map((attr, index) => (
                            <div className="variation-row" key={index}>
                                <input
                                    placeholder="key (e.g. country)"
                                    value={attr.key}
                                    onChange={(event) => updateAttribute(index, "key", event.target.value)}
                                />
                                <input placeholder="value" value={attr.value} onChange={(event) => updateAttribute(index, "value", event.target.value)} />
                                {attributes.length > 1 && (
                                    <button type="button" className="button button-ghost" onClick={() => removeAttribute(index)} aria-label="Remove attribute">
                                        ×
                                    </button>
                                )}
                            </div>
                        ))}
                    </div>
                    <button type="button" className="button button-ghost" onClick={addAttribute}>
                        Add attribute
                    </button>
                </div>

                <button type="submit" className="button button-primary" disabled={busy}>
                    {busy ? "Evaluating…" : "Evaluate"}
                </button>
            </form>

            {error && <div className="banner banner-error">{error}</div>}

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
                        {result.matchedStrategyId && <> · Rule {result.matchedStrategyId.slice(0, 8)}</>}
                    </p>
                </div>
            )}
        </section>
    );
}
