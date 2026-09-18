<h1 align="center">Feature Flag</h1>

<p align="center">
  A feature-flag and rollout management app for teams to create, target, roll out, and measure flags.
</p>

## Built With

- [React 19](https://react.dev/) and [Vite 8](https://vite.dev/) for the frontend
- [Bun](https://bun.sh/) for JavaScript workspace installation
- [JDK 21](https://openjdk.org/projects/jdk/21/) and [Spring Boot 3.4](https://spring.io/projects/spring-boot) for the HTTP API
- The committed [Gradle 9.7](https://gradle.org/) wrapper for backend builds and execution
- [MongoDB](https://www.mongodb.com/) and [Spring Data MongoDB](https://spring.io/projects/spring-data-mongodb) for persistence
- Shared exception handling and request validation utilities

## Project Structure

```text
.
├── backend/                     # Spring Boot API, business logic, and MongoDB persistence
│   ├── src/main/java/           # Product domains and application code
│   ├── src/main/resources/      # Application configuration and local media
│   └── gradle/wrapper/          # Pinned Gradle wrapper runtime
├── frontend/
│   ├── src/features/            # Product views and interactions (added per feature)
│   ├── src/shared/               # API client, reusable controls, and utilities
│   └── public/                  # Local static media
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

3. Install the pinned JavaScript workspace.

   ```bash
   bun install
   ```

4. Start the complete application.

   ```bash
   bun start
   ```

   Startup builds the backend when needed, checks MongoDB, and launches the frontend and backend.

5. Open [http://localhost:3000](http://localhost:3000).

The frontend runs on port `3000`, the API runs on port `8000`, and health is available at [http://localhost:8000/api/v1/health](http://localhost:8000/api/v1/health).

### Commands

| Command | Purpose |
|---|---|
| `bun start` | Prepares the environment and starts Spring Boot and Vite together. |
| `bun run dev:backend` | Starts only the Spring Boot API on port `8000`. |
| `bun run dev:frontend` | Starts only Vite on port `3000`. |

HackerRank installs the application with `bun install && bash setup.sh --seed` and runs it with `bun start`.

## Status

This repository currently contains the application boilerplate (framework wiring, health check, and build/run tooling) with no product features implemented yet. Features, MongoDB-backed domain models, and seeded data are added incrementally; this section and the sections above will be updated to reflect MongoDB behavior and seeded access once the first data-backed feature lands.
