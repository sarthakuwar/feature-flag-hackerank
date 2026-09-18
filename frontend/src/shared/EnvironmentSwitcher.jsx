import { useId } from "react";
import { useAppState } from "./state/AppStateContext.jsx";
import { formatEnumLabel } from "./format.js";

export default function EnvironmentSwitcher({ label = "Environment", hideLabel = false, className = "" }) {
    const { environment, setEnvironment, environments } = useAppState();
    const id = useId();

    return (
        <div className={`environment-select-wrap ${className}`}>
            <label className={hideLabel ? "sr-only" : "sidebar-label"} htmlFor={id}>
                {label}
            </label>
            <div className="environment-select-control">
                <span className="env-swatch" data-env={environment} aria-hidden="true" />
                <select id={id} className="environment-select" value={environment} onChange={(event) => setEnvironment(event.target.value)}>
                    {environments.map((env) => (
                        <option key={env} value={env}>
                            {formatEnumLabel(env)}
                        </option>
                    ))}
                </select>
            </div>
        </div>
    );
}
