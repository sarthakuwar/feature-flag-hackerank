import { describeClause } from "./targeting.js";

const VISIBLE_VALUES = 4;

export default function ClauseSummary({ clause }) {
    const { subject, verb, values } = describeClause(clause);
    const shown = values.slice(0, VISIBLE_VALUES);

    return (
        <span className="clause-summary">
            <span className="clause-subject">{subject}</span>
            <span className="clause-verb">{verb}</span>
            {shown.map((value) => (
                <code key={value} className="flag-key">
                    {value}
                </code>
            ))}
            {values.length > VISIBLE_VALUES && <span className="hint">+{values.length - VISIBLE_VALUES} more</span>}
        </span>
    );
}
