import { useMemo, useState } from "react";
import { useAppState } from "../../shared/state/AppStateContext.jsx";
import { startRollout } from "../../shared/api/flags.js";
import Dialog from "../../shared/Dialog.jsx";
import ErrorBanner from "../../shared/ErrorBanner.jsx";
import useAction from "../../shared/useAction.js";
import { formatDateTime, formatDuration } from "../../shared/format.js";
import { linearStages } from "./rollouts.js";

const UNITS = [
    { value: 1, label: "minutes" },
    { value: 60, label: "hours" },
    { value: 1440, label: "days" },
];

function toLocalInputValue(date) {
    const offsetMs = date.getTimezoneOffset() * 60000;
    return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16);
}

export default function StartRolloutDialog({ flag, environment, targets, onClose, onStarted }) {
    const { actingAs } = useAppState();
    const { busy, error, run } = useAction();
    const [targetId, setTargetId] = useState(targets[0].id);
    const target = targets.find((entry) => entry.id === targetId);
    const choices = flag.variations.filter((variation) => variation.key !== target.fromVariationKey);
    const [toVariationKey, setToVariationKey] = useState(choices[0]?.key || "");
    const [mode, setMode] = useState("linear");
    const [steps, setSteps] = useState(5);
    const [duration, setDuration] = useState(60);
    const [unit, setUnit] = useState(60);
    const [customStages, setCustomStages] = useState([
        { percentage: 10, offsetMinutes: 0 },
        { percentage: 50, offsetMinutes: 60 },
        { percentage: 100, offsetMinutes: 240 },
    ]);
    const [startMode, setStartMode] = useState("now");
    const [startAt, setStartAt] = useState(() => toLocalInputValue(new Date(Date.now() + 3600000)));

    const effectiveTo = choices.some((variation) => variation.key === toVariationKey) ? toVariationKey : choices[0]?.key || "";
    const durationMinutes = Number(duration) * unit;
    const stages = useMemo(
        () => (mode === "linear" ? linearStages(Number(steps), durationMinutes) : customStages.map((stage) => ({ ...stage }))),
        [mode, steps, durationMinutes, customStages],
    );
    const beginsAt = startMode === "later" && startAt ? new Date(startAt) : new Date();
    const variationName = (key) => flag.variations.find((variation) => variation.key === key)?.name || key;

    const updateStage = (index, field, value) =>
        setCustomStages((previous) => previous.map((stage, position) => (position === index ? { ...stage, [field]: Number(value) } : stage)));

    const handleSubmit = async (event) => {
        event.preventDefault();
        const body = { toVariationKey: effectiveTo, changedBy: actingAs };
        if (target.kind === "rule") body.ruleId = target.rawId;
        else body.strategyId = target.rawId;
        if (mode === "linear") {
            body.steps = Number(steps);
            body.durationMinutes = durationMinutes;
        } else {
            body.stages = customStages;
        }
        if (startMode === "later") body.startAt = new Date(startAt).toISOString();
        const { ok } = await run(() => startRollout(flag.key, environment, body));
        if (ok) onStarted();
    };

    return (
        <Dialog title="Start progressive rollout" onClose={onClose} wide closeOnOverlay={false}>
            <form className="form" onSubmit={handleSubmit}>
                <p className="hint">
                    Gradually move everyone a rule or strategy serves from one variation to another. Each user keeps the same assignment as the percentage
                    rises.
                </p>

                <div className="form-grid">
                    <label className="field">
                        <span>Ramp</span>
                        <select
                            value={targetId}
                            onChange={(event) => {
                                setTargetId(event.target.value);
                                setToVariationKey("");
                            }}
                            data-autofocus
                        >
                            {targets.map((entry) => (
                                <option key={entry.id} value={entry.id}>
                                    {entry.label}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label className="field">
                        <span>
                            Move from {variationName(target.fromVariationKey)} to
                        </span>
                        <select value={effectiveTo} onChange={(event) => setToVariationKey(event.target.value)} required>
                            {choices.map((variation) => (
                                <option key={variation.key} value={variation.key}>
                                    {variation.name}
                                </option>
                            ))}
                        </select>
                    </label>
                </div>

                <fieldset className="field-group">
                    <legend>Schedule</legend>
                    <div className="segmented" role="group" aria-label="Schedule type">
                        <button type="button" aria-pressed={mode === "linear"} onClick={() => setMode("linear")}>
                            Even steps
                        </button>
                        <button type="button" aria-pressed={mode === "custom"} onClick={() => setMode("custom")}>
                            Custom stages
                        </button>
                    </div>

                    {mode === "linear" ? (
                        <div className="form-grid">
                            <label className="field">
                                <span>Number of steps</span>
                                <input type="number" min="2" max="50" value={steps} onChange={(event) => setSteps(event.target.value)} required />
                            </label>
                            <div className="field">
                                <span>Total duration</span>
                                <div className="variation-row">
                                    <input
                                        type="number"
                                        min="1"
                                        value={duration}
                                        onChange={(event) => setDuration(event.target.value)}
                                        aria-label="Total duration"
                                        required
                                    />
                                    <select value={unit} onChange={(event) => setUnit(Number(event.target.value))} aria-label="Duration unit">
                                        {UNITS.map((option) => (
                                            <option key={option.value} value={option.value}>
                                                {option.label}
                                            </option>
                                        ))}
                                    </select>
                                </div>
                            </div>
                        </div>
                    ) : (
                        <div className="variation-rows">
                            {customStages.map((stage, index) => (
                                <div className="variation-row" key={index}>
                                    <label className="field">
                                        <span>Stage {index + 1} percent</span>
                                        <input
                                            type="number"
                                            min="1"
                                            max="100"
                                            value={stage.percentage}
                                            onChange={(event) => updateStage(index, "percentage", event.target.value)}
                                        />
                                    </label>
                                    <label className="field">
                                        <span>Minutes after start</span>
                                        <input
                                            type="number"
                                            min="0"
                                            value={stage.offsetMinutes}
                                            disabled={index === 0}
                                            onChange={(event) => updateStage(index, "offsetMinutes", event.target.value)}
                                        />
                                    </label>
                                    {customStages.length > 2 && (
                                        <button
                                            type="button"
                                            className="button button-ghost"
                                            aria-label={`Remove stage ${index + 1}`}
                                            onClick={() => setCustomStages((previous) => previous.filter((_, position) => position !== index))}
                                        >
                                            ×
                                        </button>
                                    )}
                                </div>
                            ))}
                            <div>
                                <button
                                    type="button"
                                    className="button button-ghost"
                                    onClick={() =>
                                        setCustomStages((previous) => {
                                            const last = previous[previous.length - 1];
                                            return [...previous, { percentage: Math.min(100, last.percentage + 10), offsetMinutes: last.offsetMinutes + 60 }];
                                        })
                                    }
                                >
                                    + Add stage
                                </button>
                            </div>
                        </div>
                    )}

                    {stages.length > 0 && (
                        <table className="history-table stage-preview">
                            <caption className="sr-only">Rollout schedule preview</caption>
                            <thead>
                                <tr>
                                    <th scope="col">Stage</th>
                                    <th scope="col">{variationName(effectiveTo)} share</th>
                                    <th scope="col">Starts</th>
                                </tr>
                            </thead>
                            <tbody>
                                {stages.map((stage, index) => (
                                    <tr key={index}>
                                        <td>{index + 1}</td>
                                        <td>{stage.percentage}%</td>
                                        <td>
                                            {stage.offsetMinutes === 0 ? "At start" : `+${formatDuration(stage.offsetMinutes)}`}
                                            <span className="hint"> · {formatDateTime(new Date(beginsAt.getTime() + stage.offsetMinutes * 60000).toISOString())}</span>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </fieldset>

                <fieldset className="field-group">
                    <legend>Start</legend>
                    <div className="segmented" role="group" aria-label="When to start">
                        <button type="button" aria-pressed={startMode === "now"} onClick={() => setStartMode("now")}>
                            Now
                        </button>
                        <button type="button" aria-pressed={startMode === "later"} onClick={() => setStartMode("later")}>
                            Schedule
                        </button>
                    </div>
                    {startMode === "later" && (
                        <label className="field">
                            <span>Start at (your local time)</span>
                            <input type="datetime-local" value={startAt} onChange={(event) => setStartAt(event.target.value)} required />
                        </label>
                    )}
                </fieldset>

                <ErrorBanner error={error} />

                <div className="dialog-actions">
                    <button type="button" className="button" onClick={onClose}>
                        Cancel
                    </button>
                    <button type="submit" className="button button-primary" disabled={busy || !effectiveTo}>
                        {busy ? "Starting…" : "Start rollout"}
                    </button>
                </div>
            </form>
        </Dialog>
    );
}
