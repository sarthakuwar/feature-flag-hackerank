import { AppStateProvider } from "./shared/state/AppStateContext.jsx";
import { RouterProvider, usePath, navigate } from "./shared/router.jsx";
import AppShell from "./layout/AppShell.jsx";
import FlagsListPage from "./features/flags/FlagsListPage.jsx";
import FlagDetailPage from "./features/flags/FlagDetailPage.jsx";

function Routes() {
    const path = usePath();

    if (path === "/" || path === "") {
        navigate("/flags");
        return null;
    }

    if (path.startsWith("/flags/")) {
        const flagKey = decodeURIComponent(path.slice("/flags/".length).split("/")[0]);
        return <FlagDetailPage flagKey={flagKey} />;
    }

    if (path === "/flags") {
        return <FlagsListPage />;
    }

    return (
        <div className="page">
            <div className="empty-state">Nothing here. Try the Flags list.</div>
        </div>
    );
}

export default function App() {
    return (
        <AppStateProvider>
            <RouterProvider>
                <AppShell>
                    <Routes />
                </AppShell>
            </RouterProvider>
        </AppStateProvider>
    );
}
