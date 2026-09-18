import { createContext, useContext, useMemo, useState } from "react";

const ENVIRONMENTS = ["DEVELOPMENT", "STAGING", "PRODUCTION"];
const ENVIRONMENT_STORAGE_KEY = "ff.environment";
const ACTOR_STORAGE_KEY = "ff.actingAs";
const DEFAULT_ACTOR = "you@local";

const AppStateContext = createContext(null);

function readStorage(key, fallback) {
    try {
        return window.localStorage.getItem(key) || fallback;
    } catch {
        return fallback;
    }
}

function writeStorage(key, value) {
    try {
        window.localStorage.setItem(key, value);
    } catch {
        // Storage can be unavailable (private browsing, blocked cookies); the app still works,
        // it just won't remember the choice on the next visit.
    }
}

export function AppStateProvider({ children }) {
    const [environment, setEnvironmentState] = useState(() => readStorage(ENVIRONMENT_STORAGE_KEY, ENVIRONMENTS[0]));
    const [actingAs, setActingAsState] = useState(() => readStorage(ACTOR_STORAGE_KEY, DEFAULT_ACTOR));

    const setEnvironment = (value) => {
        setEnvironmentState(value);
        writeStorage(ENVIRONMENT_STORAGE_KEY, value);
    };

    const setActingAs = (value) => {
        setActingAsState(value);
        writeStorage(ACTOR_STORAGE_KEY, value);
    };

    const value = useMemo(
        () => ({ environment, setEnvironment, actingAs, setActingAs, environments: ENVIRONMENTS }),
        [environment, actingAs],
    );

    return <AppStateContext.Provider value={value}>{children}</AppStateContext.Provider>;
}

export function useAppState() {
    const ctx = useContext(AppStateContext);
    if (!ctx) throw new Error("useAppState must be used within an AppStateProvider");
    return ctx;
}
