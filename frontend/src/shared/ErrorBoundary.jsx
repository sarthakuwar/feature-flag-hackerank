import { Component } from "react";

// React only supports error boundaries as class components.
export default class ErrorBoundary extends Component {
    state = { failed: false };

    static getDerivedStateFromError() {
        return { failed: true };
    }

    render() {
        if (!this.state.failed) return this.props.children;
        return (
            <div className="page fatal-error" role="alert">
                <h1>Something went wrong</h1>
                <p className="page-subtitle">
                    The app hit an unexpected error while drawing this page. Changes already saved are safe. Reload to try again.
                </p>
                <div>
                    <button type="button" className="button button-primary" onClick={() => window.location.reload()}>
                        Reload page
                    </button>
                </div>
            </div>
        );
    }
}
