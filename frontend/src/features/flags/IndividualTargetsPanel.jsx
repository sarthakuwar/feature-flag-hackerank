import { useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { bulkTarget, replaceIndividualTargets } from "../../shared/api/flags.js";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import KeyList from "../../shared/KeyList.jsx";
import useAction from "../../shared/useAction.js";
import { parseKeyList, pluralize, variationColor } from "../../shared/format.js";
import { CONTEXT_KINDS, USER_KIND } from "../../shared/targeting.js";

export default function IndividualTargetsPanel({ flag, environment, targeting, readOnly, onChanged }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [variationKey, setVariationKey] = useState(flag.variations[0]?.key || "");
    const [contextKind, setContextKind] = useState(USER_KIND);
    const [keysText, setKeysText] = useState("");
    const [notice, setNotice] = useState(null);

    const entries = targeting.individualTargets.filter((target) => target.keys.length > 0);
    const totalKeys = targeting.limits.individualKeyCount;

    const add = async (event) => {
        event.preventDefault();
        const keys = parseKeyList(keysText);
        const { ok, result } = await run(() =>
            bulkTarget(flag.key, environment, { contextKind: contextKind.trim() || USER_KIND, variationKey, operation: "ADD", keys, changedBy: actingAs }),
        );
        if (ok) {
            const { changed, unchanged } = result.summary;
            setNotice(`Targeted ${pluralize(changed, "key")}${unchanged > 0 ? `; ${pluralize(unchanged, "key")} already targeted` : ""}.`);
            setKeysText("");
            onChanged();
        }
    };

    const removeKey = async (target, key) => {
        const { ok } = await run(() =>
            bulkTarget(flag.key, environment, { contextKind: target.contextKind, variationKey: target.variationKey, operation: "REMOVE", keys: [key], changedBy: actingAs }),
        );
        if (ok) {
            setNotice(null);
            onChanged();
        }
    };

    const clearEntry = async (target) => {
        const remaining = entries
            .filter((entry) => entry !== target)
            .map((entry) => ({ contextKind: entry.contextKind, variationKey: entry.variationKey, keys: entry.keys }));
        const { ok } = await run(() => replaceIndividualTargets(flag.key, environment, remaining, actingAs));
        if (ok) {
            setNotice(null);
            onChanged();
        }
    };

    return (
        <section className="panel" aria-labelledby="individual-heading">
            <div>
                <h2 id="individual-heading">Individual targets</h2>
                <p className="hint">Serve a fixed variation to specific keys. These are checked before any rule, and a key can only have one variation.</p>
            </div>

            {!readOnly && (
                <form className="target-form" onSubmit={add}>
                    <label className="field">
                        <span>Serve</span>
                        <select value={variationKey} onChange={(event) => setVariationKey(event.target.value)}>
                            {flag.variations.map((variation) => (
                                <option key={variation.key} value={variation.key}>
                                    {variation.name}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label className="field">
                        <span>Context kind</span>
                        <input list="target-context-kinds" value={contextKind} onChange={(event) => setContextKind(event.target.value)} required />
                        <datalist id="target-context-kinds">
                            {CONTEXT_KINDS.map((kind) => (
                                <option key={kind} value={kind} />
                            ))}
                        </datalist>
                    </label>
                    <label className="field target-keys">
                        <span>Keys (comma or line separated)</span>
                        <textarea rows={2} value={keysText} onChange={(event) => setKeysText(event.target.value)} placeholder="user-1001, user-1002" required />
                    </label>
                    <button type="submit" className="button" disabled={busy || parseKeyList(keysText).length === 0}>
                        {busy ? "Adding…" : "Add targets"}
                    </button>
                </form>
            )}

            {notice && (
                <div className="banner banner-success" role="status">
                    {notice}
                </div>
            )}
            <ErrorBanner error={error} />

            {entries.length === 0 ? (
                <p className="hint">No individual targets yet.</p>
            ) : (
                <div className="target-groups">
                    {flag.variations.map((variation, index) => {
                        const forVariation = entries.filter((entry) => entry.variationKey === variation.key);
                        if (forVariation.length === 0) return null;
                        return (
                            <div key={variation.key} className="target-group">
                                <h3>
                                    <span className="variation-dot" style={{ background: variationColor(index) }} aria-hidden="true" />
                                    {variation.name}
                                </h3>
                                {forVariation.map((target) => (
                                    <div key={target.contextKind} className="target-entry">
                                        <div className="target-entry-head">
                                            <span className="badge">{target.contextKind}</span>
                                            <span className="hint">{pluralize(target.keys.length, "key")}</span>
                                            {!readOnly && (
                                                <button type="button" className="button button-ghost" disabled={busy} onClick={() => clearEntry(target)}>
                                                    Clear all
                                                </button>
                                            )}
                                        </div>
                                        <KeyList
                                            keys={target.keys}
                                            label={`${variation.name} ${target.contextKind} targets`}
                                            emptyText=""
                                            disabled={readOnly || busy}
                                            onRemove={(key) => removeKey(target, key)}
                                        />
                                    </div>
                                ))}
                            </div>
                        );
                    })}
                </div>
            )}

            <p className="hint">
                {totalKeys.toLocaleString("en")} of {targeting.limits.maxIndividualKeys.toLocaleString("en")} targeted keys used
            </p>
        </section>
    );
}
