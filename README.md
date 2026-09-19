<h1 align="center">Feature Flag</h1>

<p align="center">
  A feature-flag and rollout management app for teams to create, target, roll out, and measure flags.
</p>

## What It Does

Teams define flags with variations, configure each flag separately for development, staging, and production, decide who sees which variation, release gradually, and read experiment results. Client applications evaluate flags against a local copy of the flag definitions and receive changes over a live stream.

| Feature | What you can do | Status | Entry points |
|---|---|---|---|
| Flag management | Create boolean and multivariate flags with an owner and prerequisites, edit details and variations (weights, payloads), archive and restore (singly, or in bulk after a dependency check) | UI + API | `flags/` · `/api/v1/flags` |
| Environment configuration | Switch between development, staging, and production; toggle a flag, set its off variation, and edit its strategies per environment | UI + API | `/api/v1/flags/{key}/environments/{environment}` |
| Targeting rules and segments | Serve variations by individual key, attribute rules with drag-and-drop priority, reusable segments with members and rules, and mobile app-version rules | UI + API | `flags/targeting/` · `/api/v1/flags/{key}/environments/{environment}/rules` · `/api/v1/segments/{environment}` · `/api/v1/mobile-applications` · `/api/v1/eval/{environment}/segments` |
| Progressive rollouts | Move a targeting rule's audience from one variation to another on an even or custom staged schedule that ends at 100%, or stop one early | UI + API | `flags/rollout/` · `/api/v1/flags/{key}/environments/{environment}/progressive-rollouts` |
| Experimentation and metrics | Split traffic across variations, record conversions, compare variations side by side, reallocate with a bandit, promote a winner | UI + API | `flags/experiment/` · `flags/metrics/` · `/api/v1/flags/{key}/environments/{environment}/experiment` · `/api/v1/metrics/conversions` |
| Change history | See who changed a flag, when, and what changed | UI + API | `flags/audit/` · `/api/v1/flags/{key}/audit` |
| Evaluation and live updates | Evaluate one flag or every flag for a sample context in the UI, inspect the SDK bootstrap and feeds, and watch live change events; server-sent event streams keep clients and open pages current | UI + API | `/api/v1/eval/{environment}/{key}` · `/api/v1/stream/{environment}` |

"UI + API" means a screen calls the endpoint. "API only" means the endpoint is implemented and tested but no screen uses it yet. The flag page's "Default strategies" (percentage rollout, experiment split) are legacy per-environment strategies, separate from targeting rules and scheduled progressive rollouts. The Playground page evaluates every flag for a sample context without recording impressions, and shows the SDK bootstrap and segment feeds.

### How evaluation works

Evaluating a flag is a pure, in-memory function: environment on/off, then individual targets, then targeting rules in order, then legacy strategies, then the off variation. It never queries MongoDB per request. Every write refreshes the in-memory cache and publishes an event on `/api/v1/stream/{environment}`, so connected clients update within seconds. Users are bucketed by a stable hash, so a user keeps the same variation as a rollout percentage rises.

## Built With

- [React 19](https://react.dev/) and [Vite 8](https://vite.dev/) for the frontend
- [Bun](https://bun.sh/) for JavaScript workspace installation
- [JDK 21](https://openjdk.org/projects/jdk/21/) and [Spring Boot 3.4](https://spring.io/projects/spring-boot) for the HTTP API
- The committed [Gradle 9.7](https://gradle.org/) wrapper for backend builds and execution
- [MongoDB](https://www.mongodb.com/) and [Spring Data MongoDB](https://spring.io/projects/spring-data-mongodb) for persistence
- Shared exception handling and request validation utilities; no authentication (see [Seeded Access](#seeded-access))

## Project Structure

```text
.
├── CLAUDE.md                    # Agent context: commands, repo-wide rules, documentation upkeep
├── backend/                     # Spring Boot API, business logic, and MongoDB persistence
│   ├── CLAUDE.md                # Backend rules: API patterns, database access, errors, tests
│   ├── src/main/java/com/featureflag/
│   │   ├── flags/               # Flags, environments, evaluation, cache, event stream, seed data
│   │   │   ├── targeting/       # Rules, individual targets, segments, mobile applications
│   │   │   ├── rollout/         # Progressive rollouts and their scheduler
│   │   │   ├── experiment/      # Experiment weights, bandit allocation, promotion
│   │   │   ├── metrics/         # Impressions, conversions, per-variation results
│   │   │   └── audit/           # Change history
│   │   └── shared/              # Error handling, validation, Mongo, CORS, clock
│   ├── src/main/resources/      # Application configuration and local media
│   ├── src/test/                # Unit tests (*Test) and integration tests (*IT)
│   └── gradle/wrapper/          # Pinned Gradle wrapper runtime
├── frontend/
│   ├── CLAUDE.md                # Frontend rules: UI structure, API client, state, styling
│   ├── src/features/            # Product views (flags, segments, applications, playground)
│   ├── src/layout/              # App shell and navigation
│   ├── src/shared/              # API client, app state, reusable controls, and utilities
│   └── public/                  # Local static media
├── scripts/                     # Documentation drift check and git hook installer
├── .githooks/                   # Versioned git hooks (pre-commit runs the drift check)
├── .github/workflows/           # CI: documentation drift check
├── .vscode/launch.json          # Spring Boot debugger configuration
├── hackerrank.yml               # HackerRank install and run configuration
└── setup.sh                     # Backend, MongoDB, and seed setup
```

## Getting Started

### Prerequisites

- Bun 1.3 or later
- JDK 21
- MongoDB 8.0 or later on `127.0.0.1:27017`

Gradle is provided through the committed wrapper.

### Development Setup

1. Clone the repository.

   ```bash
   git clone https://github.com/sarthakuwar/feature-flag-hackerank.git
   ```

2. Open the project directory.

   ```bash
   cd feature-flag-hackerank
   ```

3. Install the pinned JavaScript workspace. This also enables the repository's git hooks.

   ```bash
   bun install
   ```

4. Start the complete application.

   ```bash
   bun start
   ```

   Startup builds the backend when needed, checks MongoDB, and launches the frontend and backend.

5. Open [http://localhost:3000](http://localhost:3000).

The frontend runs on port `3000`, the API runs on port `8000`, and health is available at [http://localhost:8000/api/v1/health](http://localhost:8000/api/v1/health) (`/api/v1/health`).

### MongoDB Behavior

- The API connects to `MONGODB_URI`, which defaults to `mongodb://localhost:27017/featureflag_db`. `setup.sh` starts a local `mongod` when nothing answers on port `27017`.
- On every normal backend start, `FlagSeeder` clears the flag, audit, impression, conversion, segment, and mobile-application collections and inserts a fresh baseline. Local changes do not survive a restart.
- Integration tests use a separate database, `featureflag_test_db`, and skip the seeder.

### Seeded Access

There is no login. The sidebar's "Acting as" field (default `you@local`) is only the name recorded in the change history; it is kept in the browser's local storage.

The baseline contains six flags: `new-checkout-flow`, `checkout-analytics-dashboard`, `pricing-page-redesign`, `beta-dashboard-access`, `dark-mode-default`, and `experimental-ai-summaries`. It also contains sample segments, mobile applications, targeting rules, and experiment data.

### Commands

| Command | Purpose |
|---|---|
| `bun start` | Prepares the environment and starts Spring Boot and Vite together. |
| `bun run dev:backend` | Starts only the Spring Boot API on port `8000`. |
| `bun run dev:frontend` | Starts only Vite on port `3000`. |
| `bun run docs:check` | Checks this README and every `CLAUDE.md` against the code (see [Documentation](#documentation)). |
| `cd backend && ./gradlew test` | Runs the backend unit and integration tests; needs MongoDB running. |
| `cd frontend && bun run build` | Builds the frontend into `frontend/dist`. |

HackerRank installs the application with `bun install && bash setup.sh --seed` and runs it with `bun start`.

## Documentation

The documentation is versioned with the code and checked against it.

- `README.md` (this file) is the product overview, feature status, and run guide.
- `CLAUDE.md` holds the commands, repo-wide rules, and documentation upkeep rules for coding agents. `backend/CLAUDE.md` and `frontend/CLAUDE.md` add area-specific rules and load only when working in that folder. Each is small on purpose: 13 KB for a file plus its parents.
- `AGENTS.md` defines the mandatory AI transcript log. `GUIDELINES.md`, `INSTRUCTIONS.md`, and `REQUIREMENTS.md` are the assignment contract.
- `bun run docs:check` fails when a documented file, class, route, script, or version no longer exists or matches, when a package or controller has no doc entry, or when a feature's "UI + API" status disagrees with what the frontend calls. It runs in the `.githooks/pre-commit` hook and in CI.

## Status

Flag management, environment configuration, experimentation and metrics, change history, and evaluation with live updates are available through the UI and the API. Targeting rules, segments, mobile targeting, and progressive rollouts are implemented and tested in the API; their screens are not built yet.

Not built: authentication, approval workflows, and restoring an earlier flag configuration (archive and restore of a whole flag exists).
