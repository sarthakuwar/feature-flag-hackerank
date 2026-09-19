import ClauseEditor from "./ClauseEditor.jsx";
import { emptyClause } from "./targeting.js";
import useRowKeys from "./useRowKeys.js";

export default function ClauseListEditor({ clauses, segments = null, disabled, onChange }) {
    const { keys, removeAt } = useRowKeys(clauses.length);
    const update = (index, clause) => onChange(clauses.map((entry, position) => (position === index ? clause : entry)));
    const remove = (index) => {
        removeAt(index);
        onChange(clauses.filter((_, position) => position !== index));
    };

    return (
        <div className="clause-list">
            {clauses.map((clause, index) => (
                <div key={keys[index]} className="clause-row">
                    {index > 0 && <span className="clause-and">and</span>}
                    <ClauseEditor
                        clause={clause}
                        index={index}
                        segments={segments || []}
                        allowSegment={segments !== null}
                        disabled={disabled}
                        removable={clauses.length > 1}
                        onChange={(next) => update(index, next)}
                        onRemove={() => remove(index)}
                    />
                </div>
            ))}
            <button type="button" className="button button-ghost" disabled={disabled} onClick={() => onChange([...clauses, emptyClause()])}>
                + Add condition
            </button>
        </div>
    );
}
