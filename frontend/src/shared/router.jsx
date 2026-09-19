import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";

const RouterContext = createContext(null);

function currentPath() {
    return window.location.pathname || "/";
}

export function navigate(path) {
    if (path === currentPath()) return;
    window.history.pushState(null, "", path);
    window.dispatchEvent(new PopStateEvent("popstate"));
}

// After a route change, keyboard and screen reader users start at the new page: focus moves to its main heading (or the main
// region while the page is still loading) and a live region announces it. Focus stays put when the change came from a tab
// list or the app redirected from "/".
function useRouteFocus(path, setAnnouncement) {
    const previousPath = useRef(path);

    useEffect(() => {
        if (previousPath.current === path) return;
        const previous = previousPath.current;
        previousPath.current = path;
        if (previous === "/" || previous === "") return;
        const main = document.getElementById("main-content");
        const heading = main?.querySelector("h1");
        setAnnouncement(heading?.textContent || "Loading page");
        if (document.activeElement?.getAttribute("role") === "tab") return;
        const target = heading || main;
        if (!target) return;
        if (!target.hasAttribute("tabindex")) target.setAttribute("tabindex", "-1");
        target.focus();
    }, [path, setAnnouncement]);
}

export function RouterProvider({ children }) {
    const [path, setPath] = useState(currentPath());
    const [announcement, setAnnouncement] = useState("");
    useRouteFocus(path, setAnnouncement);

    useEffect(() => {
        const onPopState = () => setPath(currentPath());
        window.addEventListener("popstate", onPopState);
        return () => window.removeEventListener("popstate", onPopState);
    }, []);

    return (
        <RouterContext.Provider value={path}>
            {children}
            <p className="sr-only" role="status" aria-live="polite">
                {announcement}
            </p>
        </RouterContext.Provider>
    );
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
