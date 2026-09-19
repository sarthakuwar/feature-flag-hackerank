# backend/CLAUDE.md

Spring Boot API. Repo-wide rules are in `../CLAUDE.md`; this file adds backend rules only.

Add no dependency beyond those in `build.gradle` without a written reason (no Lombok). The MongoDB URI is `MONGODB_URI`; `application.properties` is read-only.

## Layout

Request flow: controller -> service -> repository -> MongoDB, each layer calling only the next.

| Package (`com/featureflag/`) | Owns |
|---|---|
| `flags/` | Flag, variations, environments, evaluation, cache, SSE stream, seeder |
| `flags/targeting/` | Rules, individual targets, segments, mobile applications, clause matching |
| `flags/rollout/` | Progressive rollouts and their scheduler |
| `flags/experiment/` | Experiment weights, bandit allocation, promotion |
| `flags/metrics/` | Impressions, conversions, per-variation results |
| `flags/audit/` | Change history |
| `shared/` | Error types, exception handler, Mongo, CORS, and clock configuration |

Each feature package keeps its request and response records in its own `dto/` folder. Base path for every route is `/api/v1`.

## API patterns

- Admin writes live in `FlagAdminController`. Evaluation reads live in `FlagEvalController`. Keep them separate.
- Controllers bind input, call one service method, and wrap the result. No business rules and no repository calls in a controller.
- Success body: `{"data": ...}` via `Map.of("data", value)`. Create returns 201. Return response records such as `FlagResponse.from()`, never a `@Document` entity.
- Every write request carries `changedBy`; pass it to `AuditService.record()`.
- A new endpoint needs a controller method, a service method, request/response records, and an integration test covering success and a 400 or 404. Add a new controller's route, in backticks, to `README.md`.

## Error handling

- Throw `ApiException(status, "UPPER_SNAKE_CODE", "Sentence.")` for a domain error, for example `FLAG_NOT_FOUND` with 404. Controllers never build error responses.
- Collect every field problem in `FieldErrors`, then call `FieldErrors.throwIfAny()`. It yields 400 `VALIDATION_ERROR` with `formErrors` and `fieldErrors`. Validate before the first write.
- `GlobalExceptionHandler` is the only place that shapes errors: `{"error": {"code", "message", "details"?}}` from `GlobalExceptionHandler.envelope()`. Never return a stack trace or exception class name.
- A retry of an operation that already succeeded is a no-op, not a conflict. `FlagService.create()` returns the existing flag for a repeated key.

## Database access

- Only `*Repository` interfaces (Spring Data `MongoRepository`) touch MongoDB. Services call repositories; nothing else does.
- Entities use `@Document`, a no-arg constructor, getters and setters. Business keys use `@Indexed(unique = true)`. Compound indexes go in `FlagIndexInitializer` or `TargetingIndexInitializer`.
- `MongoConfig` removes `_class` type hints, so polymorphic embedded types do not deserialize.
- `EnvironmentConfig` is embedded in the flag and holds rules, individual targets, strategies, and progressive rollouts. Each has its own endpoint. `FlagService.updateEnvironment()` must copy every field it does not own, and `FlagService.removeVariation()` must check every field that references a variation. When you add an embedded field, update both and add a regression test.
- Inject `Clock` (`ClockConfig`); do not call `Instant.now()` in evaluation, rollout, or metrics logic. Tests move time with `MutableClock`.
- Add demo data for each feature to `FlagSeeder`. It clears the collections and reseeds on every non-test start, and it goes through services.

## Write path (every change to flag state)

1. Validate. 2. `repository.save`. 3. `FlagCacheService.put()`. 4. `AuditService.record()`. 5. `FlagStreamService.publishFlagChanged()`.

Skipping step 3 or 5 leaves evaluation and connected SDKs stale. Segment and application changes use `TargetingCacheService` and the `publishSegmentChanged` / `publishApplicationChanged` events instead of steps 3-5.

## Evaluation invariants

- `FlagEvaluationService.evaluate()` is a pure function of the flag, the `TargetingLookup`, and the `Clock`. No repository, HTTP, or database call, no randomness, no writes. Users are bucketed by `FlagEvaluationService.bucketFor()` (stable hash), never by a random number.
- Order: environment enabled -> individual targets -> targeting rules in list order (disabled skipped) -> strategies by priority -> off variation.
- Changes reach SDKs by SSE from `FlagStreamController`, never by per-evaluation lookups or a second cache.
- One deliberate exception: `FlagEvalController.evaluateOne()` writes the impression synchronously through `MetricsService.recordImpression()` after `evaluate()` returns, and swallows failures. Add no other I/O to the evaluation path.
- Targeting: segments are per environment; membership precedence is included > excluded > rules; a missing attribute makes a clause false and `negate` does not flip it. Size caps live in `TargetingLimits`.
- Progressive rollouts: a rollout targets a rule XOR a strategy; only an enabled rule serving one fixed variation can be ramped; ramp traffic is excluded from experiments. `ProgressiveRolloutScheduler` finalizes completed rollouts and is off in tests.

## Style

- Tabs for indentation, constructor injection, records for DTOs, braces on every block, a blank line between fields. Comment only the reason for a non-obvious constraint.

## Tests

- `*Test` is a unit test with no web server (Mockito available). `*IT` starts the full app (`@SpringBootTest` on a random port, `@ActiveProfiles("test")`) and calls it through `TestRestTemplate`.
- Integration tests use real MongoDB at `127.0.0.1:27017`, database `featureflag_test_db` (`application-test.properties`). Each one deletes the collections it touches before and after, then calls `FlagCacheService.load()`.
- Never run two test runs at the same time against one MongoDB, including from parallel git worktrees: they share that database.
- Run all: `./gradlew test`. Run one class: `./gradlew test --tests "*FlagAdminControllerIT"`.
