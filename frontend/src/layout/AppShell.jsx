import { useAppState } from "../shared/state/AppStateContext.jsx";
import { useLiveStatus } from "../shared/state/LiveContext.jsx";
import { usePath, Link } from "../shared/router.jsx";
import EnvironmentSwitcher from "../shared/EnvironmentSwitcher.jsx";

const NAV_ITEMS = [
    { to: "/flags", label: "Flags" },
    { to: "/segments", label: "Segments" },
    { to: "/applications", label: "Applications" },
    { to: "/playground", label: "Playground" },
];

const LIVE_LABELS = {
    live: "Live updates on",
    connecting: "Connecting to live updates…",
    offline: "Live updates offline",
};

export default function AppShell({ children }) {
    const path = usePath();
    const { actingAs, setActingAs } = useAppState();
    const liveStatus = useLiveStatus();

    const isActive = (to) => path === to || path.startsWith(`${to}/`);

    return (
        <div className="app-shell">
            <a href="#main-content" className="skip-link">
                Skip to content
            </a>

            <aside className="sidebar">
                <Link to="/flags" className="brand">
                    <img src="/favicon.svg" alt="" />
                    <span>Feature Flags</span>
                </Link>

                <nav className="sidebar-nav" aria-label="Primary">
                    {NAV_ITEMS.map((item) => (
                        <Link
                            key={item.to}
                            to={item.to}
                            className="sidebar-nav-item"
                            data-active={isActive(item.to)}
                            aria-current={isActive(item.to) ? "page" : undefined}
                        >
                            {item.label}
                        </Link>
                    ))}
                </nav>

                <EnvironmentSwitcher />

                <div className="sidebar-spacer" />

                <p className="live-status" data-status={liveStatus} role="status">
                    <span className="live-dot" aria-hidden="true" />
                    {LIVE_LABELS[liveStatus]}
                </p>

                <div className="sidebar-section">
                    <label className="sidebar-label" htmlFor="acting-as">
                        Acting as
                    </label>
                    <input
                        id="acting-as"
                        className="acting-as-input"
                        value={actingAs}
                        onChange={(event) => setActingAs(event.target.value)}
                        placeholder="you@example.com"
                    />
                </div>
            </aside>

            <main id="main-content" className="main-content" tabIndex={-1}>
                {children}
            </main>
        </div>
    );
}
