import { useCallback, useEffect, useRef, useState } from "react";

// Loads data whenever `deps` change. `data` is null while the first response is pending; a reload keeps the
// previous data on screen until the new response replaces it.
export default function useResource(loader, deps) {
    const key = JSON.stringify(deps);
    const loaderRef = useRef(loader);
    loaderRef.current = loader;
    const [state, setState] = useState({ key: null, data: null, error: null });
    const [version, setVersion] = useState(0);

    useEffect(() => {
        let cancelled = false;
        loaderRef.current().then(
            (data) => {
                if (!cancelled) setState({ key, data, error: null });
            },
            (error) => {
                if (!cancelled) setState((previous) => ({ key, data: previous.key === key ? previous.data : null, error }));
            },
        );
        return () => {
            cancelled = true;
        };
    }, [key, version]);

    const reload = useCallback(() => setVersion((current) => current + 1), []);
    const current = state.key === key;

    return {
        data: current ? state.data : null,
        error: current ? state.error : null,
        loading: !current || (state.data === null && state.error === null),
        reload,
    };
}
