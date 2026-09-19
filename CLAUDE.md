# CLAUDE.md

Feature-flag platform (a LaunchDarkly-style app): React 19 + Vite frontend, Spring Boot 3.4 API on JDK 21, MongoDB. Product overview and feature status: `README.md`.

## Context hierarchy

Read the closest file to the code you touch. Files load top-down; a child adds rules and never relaxes a parent rule.

| Working in | Read | Owns |
|---|---|---|
| any path | this file | commands, repo-wide rules, documentation upkeep |
| `backend/` | `backend/CLAUDE.md` | API patterns, database access, error handling, tests |
| `frontend/` | `frontend/CLAUDE.md` | UI structure, API client, state, styling |

`AGENTS.md` is separate and global: it defines the mandatory per-turn transcript log. Follow it every session.

## Repo-wide rules

1. Never edit `AGENTS.md`, `INSTRUCTIONS.md`, `REQUIREMENTS.md`, or `GUIDELINES.md` unless the user asks for that file by name.
2. Keep the declared stack. Do not add a UI component library, ESLint, Husky, or lint-staged. A new dependency needs a written reason in the commit message.
3. Do not edit the read-only paths in `hackerrank.yml`: `backend/src/main/resources/application.properties` and `frontend/vite.config.js`.
4. Read secrets and connection strings from environment variables. Commit only `.env.example` files, never `.env`.
5. Use American English. Leave no dead code, unused imports, debug output, or external media.
6. Flag evaluation must stay in-process and free of I/O. The invariants are in `backend/CLAUDE.md`.
7. Write every path in code comments and docs relative to the repo root. Never write a drive letter, a home directory, a username, or a worktree location.

## Commands (repo root unless noted)

| Command | Use |
|---|---|
| `bun install` | Install the JS workspace and enable git hooks |
| `bash setup.sh --seed` | Create `.env` files, ensure a local MongoDB is running, compile the backend (seeding happens when the backend starts) |
| `bun start` | Run API (port 8000) and UI (port 3000) together |
| `bun run dev:backend` | API only, through `scripts/dev-backend.mjs`, which loads the backend `.env` file (variables already set in the shell win) |
| `bun run dev:frontend` | UI only |
| `bun run docs:check` | Drift check for README and every CLAUDE.md |

Backend tests need MongoDB on `127.0.0.1:27017`: `cd backend && ./gradlew test`. Frontend build check: `cd frontend && bun run build`. Submission check: follow `skills/validate/SKILL.md`.

## Definition of done

1. The change works through UI, API, and MongoDB.
2. The test or build command above passes for each folder you changed.
3. `bun run docs:check` passes, and any doc the change made false is fixed in the same commit.

## Living documents

Docs are versioned with the code and change in the same commit as the code they describe.

| You changed | Update in the same commit |
|---|---|
| A route, request field, or error code | `README.md` feature table (Entry points column); `backend/CLAUDE.md` if a pattern changed |
| A backend package or frontend feature folder | `README.md` feature table and the area's package map |
| A `package.json` script, `setup.sh`, `Dockerfile`, `hackerrank.yml`, an env var | `README.md` commands and configuration; this file's command table |
| A dependency or runtime version | `README.md` "Built With" |
| A convention you corrected or repeated to an agent | The nearest CLAUDE.md, as one imperative line |

Writing rules for every CLAUDE.md:

- One rule per bullet, stated as an action with a checkable result. Name the file, class, or command it applies to.
- Put a rule in the lowest file where it is true. Never repeat a parent rule in a child.
- Refer to code by class or file name, for example `GlobalExceptionHandler`, never by line number.
- Record no volatile facts: test counts, dates, commit hashes, machine paths, branch or worktree names.
- Delete a rule a test already enforces; name the test instead.
- Budget: 13 KB for one file plus its parents. Adding a rule means removing or shortening another.

## Drift guardrails

`scripts/docs-check.mjs` compares the docs with the code. It runs in `.githooks/pre-commit` (enabled by `bun install` through `scripts/install-git-hooks.mjs`) and in CI (`.github/workflows/docs-check.yml`). Errors block the commit; warnings do not.

| Check | Fails when |
|---|---|
| budget | a CLAUDE.md plus its parents exceed 13 KB |
| portable | a doc contains a drive letter, home directory, or profile variable |
| vague | a CLAUDE.md uses a filler phrase or a placeholder marker instead of a checkable rule |
| path, symbol, route, command | a backticked file, class, `Class.method()`, API route, or `bun run` script does not exist |
| facts | a Spring Boot, JDK, Gradle, React, or Vite version in a doc differs from its manifest |
| structure | a backend package, frontend folder, or controller has no doc entry, or hierarchy links are broken |
| feature-table | a README row says "UI + API" or "API only" against what the frontend calls |

Warnings: a doc untouched while 15 or more commits changed its scope, hooks not installed, and code staged without its doc.

When a check fails, fix the doc or the code so they agree. Never weaken the checker to pass. Mark an intentional example by ending its line with an HTML comment containing `docs-check-ignore`.
