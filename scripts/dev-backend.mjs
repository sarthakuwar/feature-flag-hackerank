// Starts the Spring Boot API for `bun run dev:backend`. Loads backend/.env first (when present) so the
// variables documented in backend/.env.example, such as PORT, MONGODB_URI and CORS_ALLOWED_ORIGINS, take
// effect. A variable already set in the calling environment wins over the file. Written in JavaScript
// instead of shell so it behaves the same under `bun run` on Windows, macOS and Linux.
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const backendDir = path.join(fileURLToPath(new URL("..", import.meta.url)), "backend");

function parseEnvFile(text) {
    const values = {};
    for (const rawLine of text.split(/\r?\n/)) {
        const line = rawLine.trim();
        if (!line || line.startsWith("#")) {
            continue;
        }
        const match = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
        if (!match) {
            continue;
        }
        let value = match[2].trim();
        const quote = value[0];
        if ((quote === '"' || quote === "'") && value.endsWith(quote) && value.length >= 2) {
            value = value.slice(1, -1);
        }
        values[match[1]] = value;
    }
    return values;
}

const envFile = path.join(backendDir, ".env");
const fromFile = fs.existsSync(envFile) ? parseEnvFile(fs.readFileSync(envFile, "utf8")) : {};
const env = { ...fromFile, ...process.env };

const isWindows = process.platform === "win32";
// cmd.exe needs an explicit, quoted path to run a batch file from the working directory.
const child = spawn(
    isWindows ? `"${path.join(backendDir, "gradlew.bat")}"` : "./gradlew",
    ["bootRun", "--args=--server.max-http-request-header-size=2MB", "--quiet", "--console=plain"],
    { cwd: backendDir, env, stdio: "inherit", shell: isWindows },
);

child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
for (const signal of ["SIGINT", "SIGTERM"]) {
    process.on(signal, () => child.kill(signal));
}
