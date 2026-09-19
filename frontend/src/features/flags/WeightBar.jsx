import { variationColor } from "../../shared/format.js";

export default function WeightBar({ weights, variations }) {
    const parts = variations
        .map((variation, index) => ({ variation, index, weight: weights?.[variation.key] ?? 0 }))
        .filter((part) => part.weight > 0);

    return (
        <div className="weight-bar-wrap">
            <div className="weight-bar" role="img" aria-label={parts.map((part) => `${part.variation.name} ${part.weight}%`).join(", ")}>
                {parts.map((part) => (
                    <span key={part.variation.key} className="weight-bar-part" style={{ flexGrow: part.weight, background: variationColor(part.index) }} />
                ))}
            </div>
            <ul className="weight-legend">
                {parts.map((part) => (
                    <li key={part.variation.key}>
                        <span className="variation-dot" style={{ background: variationColor(part.index) }} aria-hidden="true" />
                        {part.variation.name} <strong>{part.weight}%</strong>
                    </li>
                ))}
            </ul>
        </div>
    );
}
