import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { openEventStream } from "../api/stream.js";
import { useAppState } from "./AppStateContext.jsx";

const LiveContext = createContext(null);

// One event stream for the selected environment, shared by every page. Pages register a handler with
// useLiveEvents and refresh their data when another client changes something.
export function LiveProvider({ children }) {
    const { environment } = useAppState();
    const [status, setStatus] = useState("connecting");
    const handlers = useRef(new Set());

    useEffect(() => {
        setStatus("connecting");
        return openEventStream(environment, {
            onStatus: setStatus,
            onEvent: (event) => handlers.current.forEach((handler) => handler(event)),
        });
    }, [environment]);

    const subscribe = useCallback((handler) => {
        handlers.current.add(handler);
        return () => handlers.current.delete(handler);
    }, []);

    const value = useMemo(() => ({ status, subscribe }), [status, subscribe]);

    return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}

export function useLiveStatus() {
    const ctx = useContext(LiveContext);
    if (!ctx) throw new Error("useLiveStatus must be used within a LiveProvider");
    return ctx.status;
}

export function useLiveEvents(handler) {
    const ctx = useContext(LiveContext);
    if (!ctx) throw new Error("useLiveEvents must be used within a LiveProvider");
    const handlerRef = useRef(handler);
    handlerRef.current = handler;
    const { subscribe } = ctx;

    useEffect(() => subscribe((event) => handlerRef.current(event)), [subscribe]);
}
