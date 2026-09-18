import { useEffect, useState } from "react";
import { request } from "./shared/api/client.js";

export default function App() {
    const [status, setStatus] = useState({ state: "checking", database: "Checking" });

    useEffect(() => {
        let active = true;
        request("/health")
            .then((data) => {
                if (!active) return;
                setStatus({ state: data.database === "connected" ? "ok" : "down", database: data.database === "connected" ? "Connected" : "Not connected" });
            })
            .catch(() => {
                if (active) setStatus({ state: "down", database: "Unknown" });
            });
        return () => {
            active = false;
        };
    }, []);

    return (
        <main className="app-shell">
            <div className="brand">
                <img src="/favicon.svg" alt="" />
                <span>Feature Flag</span>
            </div>
            <div className="card">
                <div className="status" data-state={status.state}>
                    <span className="status-dot" />
                    <span>{status.state === "checking" ? "Checking the server" : status.state === "ok" ? "Server running" : "Server degraded"}</span>
                </div>
                <dl>
                    <dt>API</dt>
                    <dd>/api/v1</dd>
                    <dt>Database</dt>
                    <dd>{status.database}</dd>
                </dl>
            </div>
        </main>
    );
}
