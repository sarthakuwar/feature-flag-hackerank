# backend/CLAUDE.md

Spring Boot API. Repo-wide rules are in `../CLAUDE.md`; this file adds backend rules only.

## Layout

Request flow: controller -> service -> repository -> MongoDB, each layer calling the next.

| Package (`com/featureflag/`) | Owns |
|---|---|
| `flags/` | Flag, variations, environments, evaluation, cache, SSE stream, seeder; `FlagService` delegates to `FlagAccess`, `FlagVariationService`, `FlagArchiveService` |
| `flags/targeting/` | Rules, individual targets, segments, mobile applications, clause matching |
| `flags/rollout/` | Progressive rollouts and their scheduler |
| `flags/experiment/` | Experiment weights, bandit allocation, promotion |
| `flags/metrics/` | Impression queue, conversions, results, telemetry indexes |
| `flags/audit/` | Change history |
| `shared/` | Errors, paging, validation, Mongo, CORS, request filters, health, clock |

Each feature package keeps its request and response records in a `dto/` folder (`flags/dto/` holds the audit response too). Base path for every route is `/api/v1`.

## API patterns

- Admin writes live in `FlagAdminController`. Evaluation reads live in `FlagEvalController`. Keep them separate.
- A controller binds input, calls one service method, and wraps the result: no business rules, no repository calls.
- Success body: `{"data": ...}` via `Map.of("data", value)`; create returns 201. Return records such as `FlagResponse.from()`, never a `@Document`.
- A list route that can grow is paged: bind `page` and `limit` as strings, call `PageParams.parse()`, run the query as a repository `Pageable`, and return `{"data": [...], "meta": PageMeta}`.
- Every write request carries `changedBy`; pass it to `AuditService.record()`. Segment and application writes store it as `updatedBy` and are not audited.
- A new endpoint needs a controller method, a service method, request/response records, and an integration test for success and a 400 or 404. Add a new controller's route, in backticks, to `README.md`.
- Read a new `featureflag.*` limit with `@Value` and a default; list it in `README.md` and `backend/.env.example`.

## Error handling

- Throw `ApiException(status, "UPPER_SNAKE_CODE", "Sentence.")` for a domain error, for example `FLAG_NOT_FOUND` (404).
- Collect every field problem in `FieldErrors`, then call `FieldErrors.throwIfAny()`. It yields 400 `VALIDATION_ERROR` with `formErrors` and `fieldErrors`. Validate before the first write. A missing required Boolean, number, or `changedBy` is a field error, never a default. Bind an enum path or query parameter as the enum; a bad value is the same 400, naming it.
- `GlobalExceptionHandler` is the only place that shapes errors: `{"error": {"code", "message", "details"?}}` from `GlobalExceptionHandler.envelope()`. Never return a stack trace or exception class name; an unexpected error is logged with method and path and returns `INTERNAL_ERROR`.
- Other codes: `CONFLICT` (409, lost optimistic lock), `DUPLICATE_KEY` (409, lost unique-index race), `ROLLOUT_IN_PROGRESS` (409, a change to what an active rollout ramps, including disabling its rule), `STREAM_CAPACITY` (503), `PAYLOAD_TOO_LARGE` (413).
- A retry of a succeeded operation is a no-op, not a conflict: `FlagService.create()` returns the existing flag for a repeated key, even after a lost race.

## Database access

- Only `*Repository` interfaces (Spring Data `MongoRepository`) touch MongoDB; aggregations live in fragments such as `ImpressionMetricsQueries`.
- Entities use `@Document`, a no-arg constructor, getters and setters. Business keys use `@Indexed(unique = true)`. Compound and telemetry indexes go in `FlagIndexInitializer`, `TargetingIndexInitializer`, or `TelemetryIndexInitializer`.
- Every mutable root entity carries `@Version` and implements `Persistable`; `ConcurrentWritesIT` enforces it. System writers (`ProgressiveRolloutService.completeDueRollouts()`, `ExperimentService.reallocateIfBanditEnabled()`) reload and retry on a lost lock, then skip.
- `MongoConfig` removes `_class` hints, so embedded types cannot be polymorphic.
- `EnvironmentConfig` is embedded in the flag and holds rules, individual targets, strategies, and progressive rollouts. `FlagService.updateEnvironment()` must copy every field it does not own, and `FlagVariationService.remove()` must check every field that references a variation, including an `EXPERIMENT_SPLIT` strategy's `variationWeights`. When you add an embedded field, update both and add a regression test.
- Inject `Clock` (`ClockConfig`); never call `Instant.now()` in evaluation, rollout, or metrics logic. Tests move time with `MutableClock`.
- Add demo data for each feature to `FlagSeeder`. On a non-test start it clears and reseeds through services, but only when `SeedGuard` permits (local host or `FEATUREFLAG_SEED=true`).

## Write path (every change to flag state)

1. Validate. 2. `repository.save`. 3. `FlagCacheService.put()`. 4. `AuditService.record()`. 5. `FlagStreamService.publishFlagChanged()`.

Skipping step 3 or 5 leaves evaluation or SDKs stale. A flag-wide write (create, details, variations, archive, restore) publishes to every environment through `FlagChangeBroadcast`; an environment-scoped write publishes to its own. Segment and application changes use `TargetingCacheService` and the `publishSegmentChanged` / `publishApplicationChanged` events instead of steps 3-5.

## Evaluation invariants

- `FlagEvaluationService.evaluate()` is a pure function of the flag, `TargetingLookup`, `FlagLookup`, and `Clock`. No repository, HTTP, or database call, no randomness, no writes.
- Order: environment enabled -> prerequisites -> individual targets -> targeting rules in list order -> strategies by priority -> off variation. `FlagEvaluationPrerequisiteTest` pins the `dependsOn` rules and the `PREREQUISITE_FAILED` result.
- `FlagEvaluationExperimentInclusionTest` pins which traffic counts toward experiments.
- Changes reach SDKs by SSE from `FlagStreamController`, never by per-evaluation lookups or a second cache. SSE tests wait on state through `SseTestClient`, not fixed sleeps.
- `FlagEvalController.evaluateOne()` only enqueues an impression (`MetricsService.recordImpression()` into `ImpressionWriter`: bounded, drops on overflow, never throws, batch-written by a worker). Add no other I/O to evaluation. An IT that asserts impressions calls `ImpressionWriter.flush()` first.
- `ConversionService.record()` saves the conversion, then reallocates bandit weights; a reallocation failure never fails the save.
- `EvaluationRequest.validate()` bounds every input with a 400.
- Targeting: segments are per environment; membership precedence is included > excluded > rules. `ClauseMatcher` runs `MATCHES` against a deterministic step budget and `RegexSafety` rejects catastrophic patterns at write time.
- Progressive rollouts: a rollout targets a rule XOR a strategy; only an enabled rule serving one fixed variation can be ramped.

## Style

- Tabs for indentation, constructor injection, records for DTOs, braces on every block, a blank line between fields, no Lombok. Comment only the reason for a non-obvious constraint.

## Tests

- `*Test` is a unit test with no web server. `*IT` starts the full app (`@SpringBootTest` on a random port, `@ActiveProfiles("test")`) and calls it through `TestRestTemplate`.
- Integration tests use real MongoDB at `127.0.0.1:27017`, database `featureflag_test_db` (`application-test.properties`). Each deletes the collections it touches before and after, then calls `FlagCacheService.load()`.
- Never run two test runs at once against one MongoDB, including from parallel worktrees: they share the database.
- Run one class: `./gradlew test --tests "*FlagAdminControllerIT"`.
