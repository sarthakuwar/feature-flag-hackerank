// Detects drift between the documentation (README.md and every CLAUDE.md) and the code it describes.
//
//   bun scripts/docs-check.mjs            full check of the working tree (CI, `bun run docs:check`)
//   bun scripts/docs-check.mjs --staged   same, plus "code changed, docs did not" nudges (pre-commit hook)
//   bun scripts/docs-check.mjs --root <dir>   check another checkout, for example a test fixture
//
// Errors fail the run (exit 1). Warnings are printed and never fail it. Uses only node: built-ins so it
// runs under Bun or Node. The rules are documented in CLAUDE.md, section "Drift guardrails".
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const rootFlag = args.indexOf("--root");
const ROOT = path.resolve(rootFlag >= 0 ? args[rootFlag + 1] : fileURLToPath(new URL("..", import.meta.url)));
const STAGED = args.includes("--staged");

const CONTEXT_BUDGET_BYTES = 13 * 1024; // one CLAUDE.md, and a CLAUDE.md plus all of its ancestors
const BUDGET_WARN_RATIO = 0.85;
const STALE_AFTER_COMMITS = 15; // commits touching a doc's scope since the doc last changed
const API_PREFIX = "/api/v1"; // frontend/src/shared/api/client.js prepends this to every request path
const BACKEND_ROOT = "backend/src/main/java/com/featureflag";
const SKIP_DIRS = new Set([".git", "node_modules", "build", "dist", ".gradle", ".mongodb", ".gstack", ".claude", "transcripts"]);
const IGNORE_MARKER = "docs-check-ignore";

const findings = [];
const report = (level, rule, file, line, message) => findings.push({ level, rule, file, line, message });
const error = (rule, file, line, message) => report("error", rule, file, line, message);
const warn = (rule, file, line, message) => report("warning", rule, file, line, message);

// ---------- repository index ----------

const files = [];
const dirs = new Set();

function walk(dir) {
    for (const entry of fs.readdirSync(path.join(ROOT, dir), { withFileTypes: true })) {
        if (SKIP_DIRS.has(entry.name)) continue;
        const rel = dir ? `${dir}/${entry.name}` : entry.name;
        if (entry.isDirectory()) {
            dirs.add(rel);
            walk(rel);
        } else {
            files.push(rel);
        }
    }
}
walk("");

const allPaths = [...files, ...dirs];
const pathSet = new Set(allPaths);
const topLevel = new Set(allPaths.filter((p) => !p.includes("/")));
const cache = new Map();

function read(rel) {
    if (!cache.has(rel)) cache.set(rel, fs.existsSync(path.join(ROOT, rel)) ? fs.readFileSync(path.join(ROOT, rel), "utf8") : null);
    return cache.get(rel);
}

function git(...gitArgs) {
    try {
        return execFileSync("git", gitArgs, { cwd: ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
    } catch {
        return null;
    }
}

const claudeFiles = files.filter((f) => path.posix.basename(f) === "CLAUDE.md").sort();
const docFiles = [...(pathSet.has("README.md") ? ["README.md"] : []), ...claudeFiles];

// ---------- doc parsing ----------

// Splits a doc into lines, marking fenced code, and pulls out `inline code` tokens with their line numbers.
function parseDoc(rel) {
    let inFence = false;
    return read(rel)
        .split("\n")
        .map((text, index) => {
            if (/^\s*```/.test(text)) {
                inFence = !inFence;
                return { no: index + 1, text, fenced: true, tokens: [] };
            }
            const tokens = inFence ? [] : [...text.matchAll(/`([^`\n]+)`/g)].map((m) => m[1].trim());
            return { no: index + 1, text, fenced: inFence, tokens };
        })
        .filter((line) => !line.text.includes(IGNORE_MARKER));
}

const parsed = new Map(docFiles.map((f) => [f, parseDoc(f)]));

// ---------- 1. context budget ----------

function checkBudget() {
    const bytes = (f) => Buffer.byteLength(read(f));
    const kb = (n) => `${(n / 1024).toFixed(1)} KB`;

    for (const file of claudeFiles) {
        // The file plus every CLAUDE.md above it: what an agent has loaded when it works inside that folder.
        const chain = [file];
        for (let dir = path.posix.dirname(file); dir !== "."; ) {
            dir = path.posix.dirname(dir);
            const ancestor = dir === "." ? "CLAUDE.md" : `${dir}/CLAUDE.md`;
            if (pathSet.has(ancestor)) chain.push(ancestor);
        }

        const total = chain.reduce((sum, f) => sum + bytes(f), 0);
        const budget = kb(CONTEXT_BUDGET_BYTES);

        if (total > CONTEXT_BUDGET_BYTES) {
            error(
                "budget",
                file,
                1,
                `${chain.join(" + ")} load together as ${kb(total)}, over the ${budget} budget. Cut rules the code already makes obvious, or move detail into a code comment.`,
            );
        } else if (total > CONTEXT_BUDGET_BYTES * BUDGET_WARN_RATIO) {
            warn("budget", file, 1, `${chain.join(" + ")} load together as ${kb(total)}, above 85% of the ${budget} budget. Prune before adding.`);
        }
    }
}

// ---------- 2. portability: no machine-specific paths ----------

const MACHINE_PATHS = [
    { re: /(?<![A-Za-z0-9])[A-Za-z]:[\\/]/, what: "a Windows drive path" },
    { re: /\\Users\\|\/Users\/[^/\s]|\/home\/[^/\s]|\/root\/|\/mnt\/[a-z]\//, what: "a user-home path" },
    { re: /(^|[\s`(])~\//, what: "a home-relative path" },
    { re: /%(USERPROFILE|APPDATA|HOMEPATH)%/i, what: "a Windows profile variable" },
];

function checkPortable() {
    for (const file of docFiles) {
        for (const line of parsed.get(file)) {
            for (const { re, what } of MACHINE_PATHS) {
                if (re.test(line.text)) {
                    error(
                        "portable",
                        file,
                        line.no,
                        `Contains ${what}. It works on one machine and breaks on every other clone and in CI. Write a repo-relative path (backend/build.gradle) or a command.`,
                    );
                }
            }
        }
    }
}

// ---------- 3. vague instructions and placeholders (CLAUDE.md only) ----------

const VAGUE = [
    /\betc\b\.?/i,
    /\band so on\b/i,
    /\bas (needed|necessary|appropriate)\b/i,
    /\bif (needed|necessary|possible)\b/i,
    /\bwhere (appropriate|possible)\b/i,
    /\bappropriate(ly)?\b/i,
    /\bproper(ly)?\b/i,
    /\bbest practices?\b/i,
    /\bclean code\b/i,
    /\btry to\b/i,
    /\bshould probably\b/i,
    /\bvarious\b/i,
    /\breasonabl[ey]\b/i,
    /\bnice to have\b/i,
    /\b(TBD|TODO|FIXME|XXX)\b/,
];

function checkVague() {
    for (const file of claudeFiles) {
        for (const line of parsed.get(file)) {
            const hit = VAGUE.map((re) => re.exec(line.text)).find(Boolean);
            if (hit) {
                error(
                    "vague",
                    file,
                    line.no,
                    `"${hit[0]}" is not testable. Name the file, class, value, or command the rule applies to, or delete the line.`,
                );
            }
        }
    }
}

// ---------- 4. references: paths, symbols, members, routes, commands ----------

const KNOWN_EXT = /\.(md|java|jsx?|mjs|json|ya?ml|sh|gradle|properties|css|html|svg|example|xml)$/;
const PATH_SHAPE = /^(?:\.{1,2}\/)*[\w.-]+(?:\/[\w.-]+)*\/?$/;
const SYMBOL =
    /^[A-Z][A-Za-z0-9]*(?:Service|Controller|Repository|Exception|Config|Seeder|Initializer|Scheduler|Handler|Errors|Context|Page|Dialog|Panel|Tab|Form|Switcher|Shell|Matcher|Validator|Lookup|Allocator|Fixtures|Clock)$/;
const MEMBER = /^([A-Z][A-Za-z0-9]*)\.([a-z][A-Za-z0-9]*)\(\)$/;

// Generated output (dist/, build/, node_modules/) is absent from a fresh clone, so docs may name it without it being checked.
const isGeneratedPath = (tok) => tok.split("/").some((part) => SKIP_DIRS.has(part));

const isPathToken = (tok) =>
    PATH_SHAPE.test(tok) &&
    !isGeneratedPath(tok) &&
    (tok.endsWith("/") || KNOWN_EXT.test(tok) || (tok.includes("/") && topLevel.has(tok.split("/")[0])));

// Short tails resolve too (shared/api/client.js, flags/targeting/) so docs stay readable; a rename or delete still breaks them.
function pathExists(tok, docFile) {
    const clean = tok.replace(/\/$/, "");
    const direct = [path.posix.normalize(clean), path.posix.normalize(path.posix.join(path.posix.dirname(docFile), clean))];
    if (direct.some((p) => pathSet.has(p))) return true;
    return !clean.startsWith("..") && allPaths.some((p) => p.endsWith(`/${clean}`));
}

const symbolFile = (name) => files.find((f) => /\.(java|jsx?|mjs)$/.test(f) && path.posix.basename(f).replace(/\.[^.]+$/, "") === name);

// Types the backend imports from a framework or the JDK (MongoRepository, HttpServletRequest) have no source file here.
const frameworkTypes = new Set(
    files
        .filter((f) => f.endsWith(".java"))
        .flatMap((f) => [...read(f).matchAll(/^import (?:static )?(?:org|jakarta|javax|java|com\.fasterxml)\.[\w.]*\.(\w+);/gm)].map((m) => m[1])),
);

const normRoute = (route) => {
    const cleaned = route.replace(/\?.*$/, "").replace(/\{[^}]*\}/g, "{}").replace(/\/{2,}/g, "/");
    return cleaned.length > 1 ? cleaned.replace(/\/$/, "") : cleaned;
};
const segments = (route) => route.split("/").filter(Boolean);

function loadControllers() {
    return files
        .filter((f) => f.startsWith(`${BACKEND_ROOT}/`) && f.endsWith("Controller.java"))
        .map((file) => {
            const text = read(file);
            const head = text.slice(0, Math.max(0, text.search(/\bpublic\s+(?:final\s+)?class\s+\w+/)));
            const base = normRoute(/@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"/.exec(head)?.[1] ?? "");
            const mappings = [...text.matchAll(/@(?:Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?/g)];
            const routes = mappings.map((m) => normRoute(base + (/"([^"]*)"/.exec(m[1] ?? "")?.[1] ?? "")));
            return { file, base, routes: [...new Set([...(base ? [base] : []), ...routes])] };
        });
}

function loadFrontendCalls() {
    const calls = [];
    for (const file of files.filter((f) => f.startsWith("frontend/src/") && /\.jsx?$/.test(f))) {
        for (const m of read(file).matchAll(/\brequest\(\s*([`"'])([^`"']+)\1/g)) {
            const template = m[2].replace(/\$\{[^}]*\}/g, "{}").replace(/([^/])\{\}$/, "$1");
            calls.push(segments(normRoute(API_PREFIX + template)));
        }
    }
    return calls;
}

const controllers = loadControllers();
const backendRoutes = controllers.flatMap((c) => c.routes).map(segments);
const frontendCalls = loadFrontendCalls();

const routeIsImplemented = (docSegs) =>
    backendRoutes.some((real) => docSegs.length <= real.length && docSegs.every((d, i) => d === "{}" || real[i] === "{}" || d === real[i]));

// A UI call uses a documented route when it reaches at least as deep and every literal segment matches.
const routeIsUsedByFrontend = (docSegs) =>
    frontendCalls.some((call) => docSegs.length <= call.length && docSegs.every((d, i) => d === "{}" || d === call[i]));

const packageScripts = new Set(
    ["package.json", "frontend/package.json"].flatMap((f) => (read(f) ? Object.keys(JSON.parse(read(f)).scripts ?? {}) : [])),
);

const documentedRoutes = []; // { file, line, route }

function checkReferences() {
    for (const file of docFiles) {
        for (const line of parsed.get(file)) {
            for (const tok of line.tokens) {
                if (tok.startsWith("/api/") || tok === API_PREFIX) {
                    if (/[*<>\s]|\.\.\./.test(tok)) continue; // a placeholder or pattern, not a concrete route
                    const route = normRoute(tok);
                    documentedRoutes.push({ file, line: line.no, route });
                    if (!routeIsImplemented(segments(route))) {
                        error("route", file, line.no, `\`${tok}\` is not served by any controller. Rename it or delete the mention.`);
                    }
                } else if (isPathToken(tok)) {
                    if (!pathExists(tok, file)) error("path", file, line.no, `\`${tok}\` does not exist. It was moved, renamed, or deleted.`);
                } else if (MEMBER.test(tok)) {
                    const [, cls, method] = MEMBER.exec(tok);
                    const source = symbolFile(cls);
                    if (source && !read(source).includes(`${method}(`)) {
                        error("symbol", file, line.no, `\`${tok}\`: ${source} has no ${method}(...). It was renamed or removed.`);
                    } else if (!source && SYMBOL.test(cls) && !frameworkTypes.has(cls)) {
                        error("symbol", file, line.no, `\`${tok}\`: no source file defines ${cls}.`);
                    }
                } else if (SYMBOL.test(tok) && !symbolFile(tok) && !frameworkTypes.has(tok)) {
                    error("symbol", file, line.no, `\`${tok}\`: no source file defines it. It was renamed or removed.`);
                }
            }

            const commandText = line.fenced || line.tokens.length ? [line.text] : [];
            for (const text of commandText) {
                for (const m of text.matchAll(/\bbun run ([\w:.-]+)|\bbun (start)\b/g)) {
                    const script = m[1] ?? m[2];
                    if (!packageScripts.has(script)) {
                        error("command", file, line.no, `"${m[0]}" names a script that is in neither package.json nor frontend/package.json.`);
                    }
                }
            }
        }
    }
}

// ---------- 5. version and command facts in the docs match the manifests ----------

function checkFacts() {
    const gradle = read("backend/build.gradle") ?? "";
    const wrapper = read("backend/gradle/wrapper/gradle-wrapper.properties") ?? "";
    const frontend = read("frontend/package.json") ? JSON.parse(read("frontend/package.json")) : {};
    const deps = { ...frontend.dependencies, ...frontend.devDependencies };

    const facts = [
        { name: "Spring Boot", re: /Spring Boot (\d+(?:\.\d+)*)/g, actual: /org\.springframework\.boot'\s+version\s+'([\d.]+)'/.exec(gradle)?.[1], source: "backend/build.gradle" },
        { name: "JDK", re: /(?:JDK|Java) (\d+)/g, actual: /VERSION_(\d+)/.exec(gradle)?.[1], source: "backend/build.gradle" },
        { name: "Gradle", re: /Gradle (\d+(?:\.\d+)*)/g, actual: /gradle-([\d.]+)-bin/.exec(wrapper)?.[1], source: "backend/gradle/wrapper/gradle-wrapper.properties" },
        { name: "React", re: /React (\d+(?:\.\d+)*)/g, actual: deps.react, source: "frontend/package.json" },
        { name: "Vite", re: /Vite (\d+(?:\.\d+)*)/g, actual: deps.vite, source: "frontend/package.json" },
    ];

    const readme = read("README.md") ?? "";
    for (const { name, re, actual, source } of facts) {
        if (!actual) continue;
        if (!new RegExp(re.source).test(readme)) error("facts", "README.md", 1, `README never states the ${name} version. ${source} pins ${actual}.`);
        for (const file of docFiles) {
            for (const line of parsed.get(file)) {
                for (const m of line.text.matchAll(re)) {
                    if (actual !== m[1] && !actual.startsWith(`${m[1]}.`)) {
                        error("facts", file, line.no, `Says ${name} ${m[1]}, but ${source} pins ${actual}.`);
                    }
                }
            }
        }
    }

    const rootScripts = read("package.json") ? Object.keys(JSON.parse(read("package.json")).scripts ?? {}) : [];
    const isLifecycle = (n) => n === "prepare" || (/^(pre|post)/.test(n) && rootScripts.includes(n.replace(/^(pre|post)/, "")));
    for (const script of rootScripts.filter((n) => !isLifecycle(n))) {
        if (!readme.includes(`bun run ${script}`) && !(script === "start" && readme.includes("bun start"))) {
            error("commands", "README.md", 1, `package.json defines "${script}" but README's command reference never mentions it.`);
        }
    }

    const yml = read("hackerrank.yml") ?? "";
    for (const key of ["install", "run"]) {
        const cmd = new RegExp(`^\\s*${key}:\\s*(.+)$`, "m").exec(yml)?.[1]?.trim();
        if (cmd && !readme.includes(cmd)) error("commands", "README.md", 1, `hackerrank.yml ${key} is "${cmd}" but README does not state it.`);
    }
}

// ---------- 6. structure: every feature folder, controller, and doc is covered ----------

const listDirs = (parent) => [...dirs].filter((d) => path.posix.dirname(d) === parent).map((d) => path.posix.basename(d));

function mentionsDir(file, name) {
    return (parsed.get(file) ?? []).some((line) => line.tokens.some((t) => t.split("/").includes(name) && (t.endsWith("/") || t.includes("/"))));
}

function checkStructure() {
    const areas = [
        {
            doc: "backend/CLAUDE.md",
            names: [...listDirs(BACKEND_ROOT), ...listDirs(`${BACKEND_ROOT}/flags`).filter((n) => n !== "dto")],
            hint: "Add it to the package map",
        },
        { doc: "frontend/CLAUDE.md", names: [...listDirs("frontend/src"), ...listDirs("frontend/src/features")], hint: "Add it to the layout list" },
    ];
    for (const { doc, names, hint } of areas) {
        if (!pathSet.has(doc)) {
            error("structure", doc, 1, "Missing. Every top-level app folder needs its own CLAUDE.md.");
            continue;
        }
        for (const name of names) {
            if (!mentionsDir(doc, name)) error("structure", doc, 1, `\`${name}/\` exists in the code but ${doc} never mentions it. ${hint}.`);
        }
    }

    const readmeLines = parsed.get("README.md") ?? [];
    for (const name of listDirs(`${BACKEND_ROOT}/flags`).filter((n) => n !== "dto")) {
        if (!readmeLines.some((l) => l.tokens.some((t) => t.includes(`flags/${name}/`)))) {
            error("structure", "README.md", 1, `Backend feature package \`flags/${name}/\` is missing from the README feature overview.`);
        }
    }

    // Every controller needs at least one documented route (its base path or one of its full routes).
    const documented = new Set(documentedRoutes.map((d) => d.route));
    for (const c of controllers) {
        if (![...documented].some((route) => c.routes.includes(route))) {
            error("structure", "README.md", 1, `${path.posix.basename(c.file)} serves ${c.routes[0] ?? "no route"} but no doc mentions one of its routes.`);
        }
    }

    // Hierarchy wiring: the root lists every child, every child points back to its parent.
    for (const file of claudeFiles.filter((f) => f !== "CLAUDE.md")) {
        if (pathSet.has("CLAUDE.md") && !(parsed.get("CLAUDE.md") ?? []).some((l) => l.tokens.includes(file))) {
            error("structure", "CLAUDE.md", 1, `Root CLAUDE.md does not list \`${file}\` in its context hierarchy.`);
        }
        const back = path.posix.relative(path.posix.dirname(file), "CLAUDE.md");
        if (!parsed.get(file).some((l) => l.tokens.includes(back))) {
            error("structure", file, 1, `Does not point back to the root file as \`${back}\`.`);
        }
    }
}

// ---------- 7. README feature table: "UI + API" / "API only" matches what the frontend calls ----------

function checkFeatureTable() {
    for (const line of parsed.get("README.md") ?? []) {
        const status = /\|\s*(UI \+ API|API only)\s*\|/.exec(line.text)?.[1];
        if (!status) continue;
        const routes = line.tokens.filter((t) => t.startsWith("/api/")).map((t) => segments(normRoute(t)));
        if (!routes.length) {
            error("feature-table", "README.md", line.no, "Feature row has a status but no `/api/...` route to verify it against.");
            continue;
        }
        const used = routes.some(routeIsUsedByFrontend);
        if (status === "API only" && used) {
            error("feature-table", "README.md", line.no, 'Row says "API only" but the frontend calls its route. Change the status to "UI + API".');
        } else if (status === "UI + API" && !used) {
            error("feature-table", "README.md", line.no, 'Row says "UI + API" but no frontend request reaches its route. Change the status to "API only".');
        }
    }
}

// ---------- 8. freshness and hook installation (warnings) ----------

const STALE_SCOPES = {
    "README.md": ["backend/src", "frontend/src", "package.json", "setup.sh", "hackerrank.yml"],
    "CLAUDE.md": ["package.json", "setup.sh", "hackerrank.yml", "scripts", ".githooks"],
    "backend/CLAUDE.md": ["backend/src", "backend/build.gradle"],
    "frontend/CLAUDE.md": ["frontend/src", "frontend/package.json"],
};

function checkFreshness() {
    for (const file of docFiles) {
        const scope = STALE_SCOPES[file];
        if (!scope || git("status", "--porcelain", "--", file)) continue;
        const lastDocCommit = git("log", "-1", "--format=%H", "--", file);
        if (!lastDocCommit) continue;
        const since = Number(git("rev-list", "--count", `${lastDocCommit}..HEAD`, "--", ...scope) ?? 0);
        if (since >= STALE_AFTER_COMMITS) {
            warn("stale", file, 1, `${since} commits touched ${scope.join(", ")} since this file last changed. Re-read it against the code.`);
        }
    }

    if (!process.env.CI && fs.existsSync(path.join(ROOT, ".git")) && git("config", "core.hooksPath") !== ".githooks") {
        warn("hooks", ".githooks/pre-commit", 1, "Git hooks are not installed in this clone, so drift is only caught in CI. Run: bun run prepare");
    }
}

// ---------- 9. staged-change nudges (--staged only, warnings) ----------

const COUPLING = [
    {
        name: "backend conventions or configuration",
        touches: (p, status) =>
            /^backend\/(build\.gradle|\.env\.example|src\/main\/resources\/)/.test(p) ||
            p.startsWith(`${BACKEND_ROOT}/shared/`) ||
            (/[ADR]/.test(status) && p.endsWith("Controller.java")),
        docs: ["backend/CLAUDE.md", "README.md"],
    },
    {
        name: "frontend conventions or configuration",
        touches: (p) => /^frontend\/(package\.json|vite\.config\.js|\.env\.example|src\/shared\/)/.test(p),
        docs: ["frontend/CLAUDE.md", "README.md"],
    },
    {
        name: "repo commands, setup, or tooling",
        touches: (p) => ["package.json", "setup.sh", "hackerrank.yml"].includes(p) || p.startsWith("scripts/") || p.startsWith(".githooks/"),
        docs: ["CLAUDE.md", "README.md"],
    },
];

function checkStagedCoupling() {
    const raw = git("diff", "--cached", "--name-status", "-M");
    if (!raw) return;
    const changes = raw.split("\n").map((row) => {
        const [status, ...paths] = row.split("\t");
        return { status: status[0], paths };
    });
    const staged = new Set(changes.flatMap((c) => c.paths));

    for (const rule of COUPLING) {
        const hits = changes.flatMap((c) => c.paths.filter((p) => rule.touches(p, c.status)));
        if (hits.length && !rule.docs.some((d) => staged.has(d))) {
            warn("coupling", rule.docs[0], 1, `This commit changes ${rule.name} (${[...new Set(hits)].slice(0, 3).join(", ")}) but stages none of ${rule.docs.join(", ")}. Update them, or confirm nothing they say is now wrong.`);
        }
    }
}

// ---------- run ----------

checkBudget();
checkPortable();
checkVague();
checkReferences();
checkFacts();
checkStructure();
checkFeatureTable();
checkFreshness();
if (STAGED) checkStagedCoupling();

const errors = findings.filter((f) => f.level === "error");
const warnings = findings.filter((f) => f.level === "warning");
for (const f of [...errors, ...warnings]) {
    console.log(`${f.level === "error" ? "ERROR  " : "WARNING"} [${f.rule}] ${f.file}:${f.line}  ${f.message}`);
}

console.log(`docs:check ${errors.length ? "FAILED" : "passed"}: ${errors.length} error(s), ${warnings.length} warning(s), ${docFiles.length} doc(s) checked.`);
if (errors.length) {
    console.log("Fix the docs (or the code) so they agree, then commit again. The docs are versioned with the code and must not lag it.");
    process.exit(1);
}
