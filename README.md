<h1 align="center">Feature Flag</h1>

<p align="center">
  A feature-flag and rollout management app for teams to create, target, roll out, and measure flags.
</p>

## What It Does

Teams define flags with variations, configure each flag separately for development, staging, and production, decide who sees which variation, release gradually, and read experiment results. Client applications evaluate flags against a local copy of the flag definitions and receive changes over a live stream.

| Feature | What you can do | Status | Entry points |
|---|---|---|---|
| Flag management | Create boolean and multivariate flags with an owner and prerequisites, edit details and variations (weights, payloads), archive and restore (singly, or in bulk after a dependency check) | UI + API | `flags/` · `/api/v1/flags` · `/api/v1/flags/bulk-archive` |
| Environment configuration | Switch between development, staging, and production; toggle a flag, set its off variation, and edit its strategies per environment | UI + API | `/api/v1/flags/{key}/environments/{environment}` |
| Targeting rules and segments | Serve variations by individual key, attribute rules with drag-and-drop priority, reusable segments with members and rules (with a detail view each), and mobile applications with app-version rules (list and detail views) | UI + API | `flags/targeting/` · `/api/v1/flags/{key}/environments/{environment}/rules` · `/api/v1/segments/{environment}` · `/api/v1/mobile-applications` · `/api/v1/mobile-applications/{key}` · `/api/v1/eval/{environment}/segments` · `/api/v1/eval/applications` |
| Progressive rollouts | Move a targeting rule's audience from one variation to another on an even or custom staged schedule that ends at 100%, follow one on its own detail view, or stop one early | UI + API | `flags/rollout/` · `/api/v1/flags/{key}/environments/{environment}/progressive-rollouts` · `/api/v1/flags/{key}/environments/{environment}/progressive-rollouts/{rolloutId}` |
| Experimentation and metrics | Split traffic across variations, record conversions, compare variations side by side, reallocate with a bandit, promote a winner | UI + API | `flags/experiment/` · `flags/metrics/` · `/api/v1/flags/{key}/environments/{environment}/experiment/weights` · `/api/v1/flags/{key}/environments/{environment}/experiment/bandit` · `/api/v1/flags/{key}/environments/{environment}/experiment/reallocate` · `/api/v1/flags/{key}/environments/{environment}/experiment/promote` · `/api/v1/metrics/conversions` · `/api/v1/metrics/{flagKey}/{environment}` |
| Change history | See who changed a flag, when, and what changed | UI + API | `flags/audit/` · `/api/v1/flags/{key}/audit` |
| Evaluation and live updates | Evaluate one flag or every flag for a sample context in the UI, inspect the SDK bootstrap and feeds, and watch live change events; server-sent event streams keep clients and open pages current | UI + API | `/api/v1/eval/{environment}/{key}` · `/api/v1/eval/{environment}` · `/api/v1/eval/{environment}/bootstrap` · `/api/v1/stream/{environment}` |
| Health probes | Report that the process is alive (`live`, no I/O) and that MongoDB and the in-memory caches are ready for traffic (`ready`); the plain route reports MongoDB state | API only | `/api/v1/health` · `/api/v1/health/live` · `/api/v1/health/ready` |

"UI + API" means a screen calls the endpoint. "API only" means the endpoint is implemented and tested but no screen uses it yet. The flag page's "Default strategies" (percentage rollout, experiment split) are legacy per-environment strategies, separate from targeting rules and scheduled progressive rollouts. The Playground page evaluates every flag for a sample context without recording impressions, and shows the SDK bootstrap and segment feeds.

### API conventions

- Success bodies are `{"data": ...}`; errors are `{"error": {"code", "message", "details"?}}`. Field problems are 400 `VALIDATION_ERROR` with `formErrors` and `fieldErrors`. A body that is not valid JSON, or has a wrong-typed field or unknown enum value, is 400 `MALFORMED_REQUEST`; a variation `payloadValue` of type JSON with trailing content is a field error.
- Three lists are paged: `/api/v1/flags` (also `q`, a case-insensitive substring of key, name, or owner, and `lifecycle`), `/api/v1/flags/{key}/audit`, and `/api/v1/mobile-applications`. They take `page` (1-based, default 1) and `limit` (default 50, at most 200) and answer `{"data": [...], "meta": {"page", "limit", "total"}}`. A bad value is 400 `VALIDATION_ERROR` with a field error; a page past the end is empty. Flags are ordered oldest first, audit events newest first, applications by name then key. Segment lists are not paged.
- Other codes: `CONFLICT` (409, someone else saved the same flag, segment, or application first; reload and retry), `DUPLICATE_KEY` (409), `ROLLOUT_IN_PROGRESS` (409, the change would break an active progressive rollout, such as disabling its rule), `STREAM_CAPACITY` (503, the environment's stream is full), `PAYLOAD_TOO_LARGE` (413, body over 1 MB), and `INTERNAL_ERROR` (500, generic; details go to the server log). A bad enum in a path or query parameter is 400 `VALIDATION_ERROR` naming the parameter.
- Required fields are enforced: `enabled` on an environment update and on a rule enable or disable, `percentage` on a percentage rollout, `percentage` and `offsetMinutes` on rollout stages, and `changedBy` on every write. Bulk archive, restore, and validate take at most 100 keys. Creating a flag with a key that exists returns the existing flag, even when two creates race.
- Every flag write records `changedBy` in the change history and publishes a `flag-changed` event: flag-wide writes (create, edit, variations, archive, restore) to every environment, environment writes to one. Segment and application writes store `changedBy` as `updatedBy` and are not in the change history. Removing a variation is blocked while an environment, target, rule, active rollout, or experiment split still uses it.

### How evaluation works

Evaluating a flag is a pure, in-memory function of the flag, the cached segments and applications, the cached prerequisite flags, and the clock: environment on/off, then prerequisites, then individual targets, then targeting rules in order, then legacy strategies, then the off variation. A prerequisite (`dependsOn`) is met only when its flag exists, is active, and is on in that environment, transitively; otherwise the off variation is served with reason `PREREQUISITE_FAILED` and the key in `failedPrerequisiteKey`. Evaluation reads memory only and does no MongoDB read. Evaluating one flag enqueues an impression on a bounded in-memory queue (a full queue drops it) and a background worker writes the queue to MongoDB in batches; conversions and metrics reads flush the queue first. Every write refreshes the in-memory cache and publishes an event on `/api/v1/stream/{environment}`, so connected clients update within seconds. Users are bucketed by a stable hash of the flag key and `userId` (else the first context key, else `anonymous`), so a user keeps the same variation as a rollout percentage rises. Only randomly assigned traffic counts toward experiment results.

The evaluation routes bound their input: keys up to 256 characters, 100 attributes per context, attribute names up to 128 and values up to 1,024 characters, and 20 contexts. The bootstrap response is trimmed for SDKs: no audit fields, only active rollouts, and a disabled default when an environment is missing. A `MATCHES` clause runs under a deterministic step budget, and a regular expression that can backtrack catastrophically is rejected when the rule is saved. The stream ends after a timeout, sends a heartbeat comment every 15 seconds, caps subscribers per environment, and closes on shutdown; browsers reconnect on their own.

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
│   ├── .env.example             # Backend environment variables and their defaults
│   ├── src/test/                # Unit tests (*Test) and integration tests (*IT)
│   └── gradle/wrapper/          # Pinned Gradle wrapper runtime
├── frontend/
│   ├── CLAUDE.md                # Frontend rules: UI structure, API client, state, styling
│   ├── src/features/            # Product views (flags, segments, applications, playground)
│   ├── src/layout/              # App shell and navigation
│   ├── src/shared/              # API client, app state, reusable controls, and utilities
│   └── public/                  # Local static media
├── scripts/                     # Backend dev launcher, documentation drift check, git hook installer
├── .githooks/                   # Versioned git hooks (pre-commit runs the drift check)
├── .github/workflows/           # CI: backend tests and frontend build (ci.yml), documentation drift check
├── .vscode/launch.json          # Spring Boot debugger configuration
├── Dockerfile                   # Multi-stage image: web UI built into the Spring Boot jar
├── hackerrank.yml               # HackerRank install and run configuration
└── setup.sh                     # Creates .env files, checks local MongoDB, compiles the backend
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

   Startup creates the `.env` files, checks MongoDB, compiles the backend, and launches the frontend and backend. The backend seeds its database when it starts.

5. Open [http://localhost:3000](http://localhost:3000).

The frontend runs on port `3000`, the API runs on port `8000`, and health is available at [http://localhost:8000/api/v1/health](http://localhost:8000/api/v1/health) (`/api/v1/health`); `/api/v1/health/live` and `/api/v1/health/ready` are the liveness and readiness probes.

### MongoDB Behavior

- The API connects to `MONGODB_URI`, which defaults to `mongodb://localhost:27017/featureflag_db`. `setup.sh` starts a local `mongod` when the URI is local and nothing answers on port `27017`.
- On every normal backend start against a MongoDB on this machine, `FlagSeeder` clears the flag, audit, impression, conversion, segment, and mobile-application collections and inserts a fresh baseline. Local changes do not survive a restart. Against any other host it seeds nothing unless `FEATUREFLAG_SEED=true`, which deletes the data there.
- Impressions and conversions expire after `TELEMETRY_RETENTION_DAYS` days through a TTL index; the backend creates the telemetry indexes on start.
- Integration tests use a separate database, `featureflag_test_db`, and skip the seeder.

### Seeded Access

There is no login. The sidebar's "Acting as" field (default `you@local`) is only the name recorded in the change history; it is kept in the browser's local storage.

The baseline contains six flags: `new-checkout-flow`, `checkout-analytics-dashboard`, `pricing-page-redesign`, `beta-dashboard-access`, `dark-mode-default`, and `experimental-ai-summaries`. It also contains sample segments, mobile applications, targeting rules, and experiment data.

### Commands

| Command | Purpose |
|---|---|
| `bun start` | Prepares the environment and starts Spring Boot and Vite together. |
| `bun run dev:backend` | Starts only the Spring Boot API on port `8000`, after loading the backend's `.env` file (variables already set in the shell win). |
| `bun run dev:frontend` | Starts only Vite on port `3000`. |
| `bun run docs:check` | Checks this README and every `CLAUDE.md` against the code (see [Documentation](#documentation)). |
| `cd backend && ./gradlew test` | Runs the backend unit and integration tests; needs MongoDB running. |
| `cd frontend && bun run build` | Builds the frontend into `frontend/dist`. |
| `docker build -t featureflag .` | Builds one image with the web UI inside the API. Run it with `-p 8000:8000 -e MONGODB_URI=...`. |

HackerRank installs the application with `bun install && bash setup.sh --seed` and runs it with `bun start`. `bash setup.sh --seed` creates the `.env` files, checks MongoDB, and compiles the backend; the seed data itself loads when the backend starts (there is no Gradle seed task).

The image runs as a non-root user and its health check calls `/api/v1/health/live`. Known limitation: the image has no single-page-app fallback, so opening a deep link such as `/flags/new-checkout-flow` directly returns a 404 instead of the app; start from `/`. CI (`.github/workflows/ci.yml`) runs the backend tests against a `mongo:7` service and builds the frontend.

### Configuration

The backend reads these from the environment (`backend/.env.example` lists them). A `featureflag.*` property also maps to an environment variable, for example `featureflag.stream.timeout-ms` to `FEATUREFLAG_STREAM_TIMEOUT_MS`.

| Variable or property | Default | Purpose |
|---|---|---|
| `PORT` | `8000` | API port. |
| `MONGODB_URI` | `mongodb://localhost:27017/featureflag_db` | MongoDB connection string. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | Comma-separated browser origins allowed by CORS. |
| `FEATUREFLAG_SEED` | `false` | Seed a MongoDB that is not on this machine; deletes the data there. |
| `TELEMETRY_RETENTION_DAYS` | `90` | Days before impressions and conversions expire; `0` disables expiry. |
| `IMPRESSION_QUEUE_CAPACITY` | `10000` | Impressions held in memory before new ones are dropped. |
| `featureflag.stream.timeout-ms` | `1800000` | Lifetime of one event stream connection. |
| `featureflag.stream.max-subscribers-per-environment` | `1000` | Open streams per environment; more get 503 `STREAM_CAPACITY`. |
| `featureflag.stream.heartbeat-interval-ms` | `15000` | Heartbeat comment interval on open streams. |
| `featureflag.http.max-body-bytes` | `1048576` | Largest request body; more gets 413 `PAYLOAD_TOO_LARGE`. |
| `featureflag.mongo.server-selection-timeout-ms` | `5000` | How long a request waits for a reachable MongoDB. |
| `featureflag.mongo.connect-timeout-ms` | `5000` | MongoDB connect timeout. |
| `featureflag.mongo.socket-timeout-ms` | `30000` | MongoDB socket read timeout. |

The frontend reads `VITE_API_URL` (default `/api/v1`). Responses carry `X-Content-Type-Options` and `X-Frame-Options`, and the server shuts down gracefully, letting in-flight requests finish for up to 20 seconds.

## Documentation

The documentation is versioned with the code and checked against it.

- `README.md` (this file) is the product overview, feature status, and run guide.
- `CLAUDE.md` holds the commands, repo-wide rules, and documentation upkeep rules for coding agents. `backend/CLAUDE.md` and `frontend/CLAUDE.md` add area-specific rules and load only when working in that folder. Each is small on purpose: 13 KB for a file plus its parents.
- `AGENTS.md` defines the mandatory AI transcript log. `GUIDELINES.md`, `INSTRUCTIONS.md`, and `REQUIREMENTS.md` are the assignment contract.
- `bun run docs:check` fails when a documented file, class, route, script, or version no longer exists or matches, when a package or controller has no doc entry, or when a feature's "UI + API" status disagrees with what the frontend calls. It runs in the `.githooks/pre-commit` hook and in CI.

## Status

Flag management, environment configuration, targeting rules and segments, mobile applications, progressive rollouts, experimentation and metrics, change history, and evaluation with live updates are available through the UI and the API. The health probes are API only.

Not built: authentication, approval workflows, and restoring an earlier flag configuration (archive and restore of a whole flag exists).
