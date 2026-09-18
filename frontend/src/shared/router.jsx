import { createContext, useCallback, useContext, useEffect, useState } from "react";

const RouterContext = createContext(null);

function currentPath() {
    return window.location.pathname || "/";
}

export function navigate(path) {
    if (path === currentPath()) return;
    window.history.pushState(null, "", path);
    window.dispatchEvent(new PopStateEvent("popstate"));
}

export function RouterProvider({ children }) {
    const [path, setPath] = useState(currentPath());

    useEffect(() => {
        const onPopState = () => setPath(currentPath());
        window.addEventListener("popstate", onPopState);
        return () => window.removeEventListener("popstate", onPopState);
    }, []);

    return <RouterContext.Provider value={path}>{children}</RouterContext.Provider>;
}

export function usePath() {
    const path = useContext(RouterContext);
    if (path === null) throw new Error("usePath must be used within a RouterProvider");
    return path;
}

export function Link({ to, children, ...rest }) {
    const handleClick = useCallback(
        (event) => {
            if (event.defaultPrevented || event.button !== 0) return;
            if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            navigate(to);
        },
        [to],
    );

    return (
        <a href={to} onClick={handleClick} {...rest}>
            {children}
        </a>
    );
}
