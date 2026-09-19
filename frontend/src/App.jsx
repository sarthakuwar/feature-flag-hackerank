import { useEffect } from "react";
import ErrorBoundary from "./shared/ErrorBoundary.jsx";
import { AppStateProvider } from "./shared/state/AppStateContext.jsx";
import { LiveProvider } from "./shared/state/LiveContext.jsx";
import { RouterProvider, usePath, navigate } from "./shared/router.jsx";
import AppShell from "./layout/AppShell.jsx";
import FlagsListPage from "./features/flags/FlagsListPage.jsx";
import FlagDetailPage from "./features/flags/FlagDetailPage.jsx";
import RolloutDetailPage from "./features/flags/RolloutDetailPage.jsx";
import SegmentsPage from "./features/segments/SegmentsPage.jsx";
import SegmentDetailPage from "./features/segments/SegmentDetailPage.jsx";
import ApplicationsPage from "./features/applications/ApplicationsPage.jsx";
import ApplicationDetailPage from "./features/applications/ApplicationDetailPage.jsx";
import PlaygroundPage from "./features/playground/PlaygroundPage.jsx";

function segmentsOf(path, prefix) {
    return path
        .slice(prefix.length)
        .split("/")
        .filter(Boolean)
        .map((segment) => decodeURIComponent(segment));
}

function Routes() {
    const path = usePath();
    const atRoot = path === "/" || path === "";

    useEffect(() => {
        if (atRoot) navigate("/flags");
    }, [atRoot]);

    if (atRoot) return null;

    if (path.startsWith("/flags/")) {
        const [flagKey, tab, rolloutId] = segmentsOf(path, "/flags/");
        if (tab === "rollouts" && rolloutId) return <RolloutDetailPage flagKey={flagKey} rolloutId={rolloutId} />;
        return <FlagDetailPage flagKey={flagKey} tab={tab} />;
    }

    if (path === "/flags") return <FlagsListPage />;

    if (path.startsWith("/segments/")) {
        const [segmentKey] = segmentsOf(path, "/segments/");
        return <SegmentDetailPage segmentKey={segmentKey} />;
    }

    if (path === "/segments") return <SegmentsPage />;
    if (path.startsWith("/applications/")) {
        const [applicationKey] = segmentsOf(path, "/applications/");
        return <ApplicationDetailPage applicationKey={applicationKey} />;
    }

    if (path === "/applications") return <ApplicationsPage />;
    if (path === "/playground") return <PlaygroundPage />;

    return (
        <div className="page">
            <div className="empty-state">Nothing here. Try the Flags list.</div>
        </div>
    );
}

export default function App() {
    return (
        <ErrorBoundary>
            <AppStateProvider>
                <LiveProvider>
                    <RouterProvider>
                        <AppShell>
                            <Routes />
                        </AppShell>
                    </RouterProvider>
                </LiveProvider>
            </AppStateProvider>
        </ErrorBoundary>
    );
}
