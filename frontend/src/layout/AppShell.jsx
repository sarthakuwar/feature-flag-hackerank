import { useAppState } from "../shared/state/AppStateContext.jsx";
import { usePath, Link } from "../shared/router.jsx";
import EnvironmentSwitcher from "../shared/EnvironmentSwitcher.jsx";

export default function AppShell({ children }) {
    const path = usePath();
    const { actingAs, setActingAs } = useAppState();

    const flagsActive = path === "/" || path.startsWith("/flags");

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
                    <Link to="/flags" className="sidebar-nav-item" data-active={flagsActive} aria-current={flagsActive ? "page" : undefined}>
                        Flags
                    </Link>
                </nav>

                <EnvironmentSwitcher />

                <div className="sidebar-spacer" />

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
