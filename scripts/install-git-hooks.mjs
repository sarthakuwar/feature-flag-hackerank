// Points this clone's git at the versioned hooks in .githooks/. Runs from the root `prepare` script, so
// `bun install` enables the docs drift check automatically. Safe to run anywhere: it does nothing when the
// folder is not a git checkout (for example the HackerRank archive) and never fails the install.
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const hooksDir = ".githooks";

if (!fs.existsSync(path.join(root, ".git"))) {
    console.log("[hooks] Not a git checkout; skipping git hook installation.");
    process.exit(0);
}

try {
    execFileSync("git", ["config", "core.hooksPath", hooksDir], { cwd: root, stdio: "ignore" });
    for (const hook of fs.readdirSync(path.join(root, hooksDir))) {
        fs.chmodSync(path.join(root, hooksDir, hook), 0o755);
    }
    console.log(`[hooks] Git hooks enabled from ${hooksDir}/ (pre-commit runs the docs drift check).`);
} catch (failure) {
    console.log(`[hooks] Could not enable git hooks (${failure.message}). Run "bun run prepare" from a git checkout.`);
}
