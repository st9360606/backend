# AGENTS.md

This file defines the working rules for AI coding agents editing this repository.
It applies to the entire backend project unless a more specific `AGENTS.md` exists in a subdirectory.

## Project Snapshot

- Product: BiteCal / Calai backend.
- Runtime: Java 21.
- Framework: Spring Boot 3.5.x, Spring MVC, Spring Security, Spring Data JPA, Validation, Actuator.
- Build tool: Maven Wrapper (`mvnw`, `mvnw.cmd`).
- Database: MySQL in dev/prod style configs; H2 MySQL-mode for many tests.
- Cache / distributed guard: Redis via Spring Data Redis.
- External integrations:
    - Google Sign-In ID token verification.
    - Google Play Billing / Android Publisher API for subscription verification, acknowledge retry, RTDN, and subscription deferral.
    - Gemini provider for food recognition / nutrition-label parsing.
    - OpenFoodFacts for barcode lookup.
    - SMTP mail for login code / notifications.
- Main package root: `com.calai.backend`.

## High-Level Modules

Keep changes inside the owning bounded context whenever possible.

- `auth`: Google sign-in, email OTP login, access/refresh tokens, `AuthContext`, security filters.
- `accountdelete`: account deletion request flow, purge DAO, scheduled worker.
- `entitlement`: Google Play purchase sync, membership entitlement records, RTDN, expiry/reverify/acknowledge workers, purchase token crypto, product config.
- `referral`: referral code, referral claim lifecycle, reward qualification, membership summary, reward ledger, notifications, CS/internal referral APIs.
- `foodlog`: photo / album / label / barcode food-log creation, AI provider routing, quota, rate/in-flight guard, image storage, cleanup/retention, effective nutrition JSON, overrides, recent/history/progress APIs.
- `users`: user profile, profile plan, daily activity, auto-generated goals, current user APIs.
- `water`: daily water logging and weekly progress.
- `weight`: weight baseline/history/summary and photo cleanup.
- `workout`: workout presets, text parsing, estimation, logging, daily/weekly workout progress.
- `fasting`: fasting plan APIs and next-trigger calculation.
- `common` and `config`: cross-cutting web, security, storage, crypto, Swagger, actuator, scheduling config.

## Repository Hygiene

Do not edit or commit generated/local artifacts unless explicitly requested.

- Do not modify `target/`.
- Do not modify `.git/`.
- Do not modify `.idea/`, `*.iml`, or editor-local files.
- Do not modify local runtime image/blob data under `data/` unless the task is specifically about local storage samples.
- Avoid changing `.fastRequest/` unless the user explicitly asks to update request collections.
- Do not add secrets, API keys, service-account JSON, purchase tokens, SMTP passwords, or real user data.
- Keep environment-specific values in config placeholders or environment variables.

## Git Operation Rules

AI coding agents must not commit or push code changes unless the user explicitly asks for Git operations.

- Do not run or instruct automatic `git commit`, `git push`, `git tag`, branch creation, force-push, rebase, or history-rewrite commands unless explicitly requested.
- It is acceptable to mention that files are ready to be reviewed in `git status`, but do not turn that into a commit/push workflow by default.
- When the user asks for implementation steps, focus on file changes, build commands, tests, and verification. Leave commit and push decisions to the user.
- If the user specifically asks for Git commands, provide safe commands only, avoid force operations by default, and clearly separate review/build/test steps from commit/push steps.

## Build and Test Commands

Use the Maven Wrapper from the backend root.

### Windows PowerShell

```powershell
.\mvnw.cmd test
.\mvnw.cmd -q test
.\mvnw.cmd -Dtest=ClassNameTest test
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

### macOS / Linux / Git Bash

```bash
./mvnw test
./mvnw -q test
./mvnw -Dtest=ClassNameTest test
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### Test Expectations

- Run the most relevant focused test first.
- For backend-wide changes, run `mvn test` before declaring the task complete.
- Existing CI uses JDK 21, Redis service, and `mvn -q test`.
- Tests often rely on `src/test/resources/application-test.yml` where scheduling is disabled, H2 runs in MySQL mode, Gemini is disabled by default, and `app.foodlog.provider=STUB`.
- Integration tests may use Testcontainers MySQL. Do not remove Testcontainers wiring just to make local tests easier.

## Dependency Management

- Prefer dependencies and versions managed by Spring Boot's dependency management.
- Do not specify Maven dependency versions manually unless the artifact is not managed by Spring Boot or the version is required for a documented compatibility/security reason.
- Before adding a new library, check whether the same capability already exists in the project or can be implemented with Spring Boot / Spring Framework / Jackson / Micrometer / Resilience4j-style existing dependencies.
- Avoid adding large framework-level dependencies for small utilities.
- If adding or upgrading dependencies, run focused tests and watch for transitive conflicts, duplicate logging bindings, Jackson/Kotlin serialization mismatch, and test/runtime classpath divergence.

## Coding Style

- Use Java 21 language features only when they improve clarity and remain compatible with the current build.
- Follow existing Spring style: constructor injection through Lombok `@RequiredArgsConstructor` is preferred.
- Keep controllers thin. Put business rules in services.
- Keep JPA repositories focused on persistence queries; avoid business decisions in repositories.
- Prefer explicit DTOs / records for request and response contracts.
- Use `Instant` for persisted UTC timestamps.
- Use `LocalDate` only for user-local date semantics.
- Keep all persisted timestamp naming consistent with existing columns such as `created_at_utc`, `updated_at_utc`, `valid_to_utc`, `captured_at_utc`.
- Do not enable `spring.jpa.open-in-view`.
- Do not introduce N+1 queries; use repository queries with explicit filters/page limits where needed.
- Do not log PII, raw tokens, OTPs, full purchase tokens, image content, or sensitive nutrition/health payloads.
- When logging purchase tokens, only use hashes or redacted values.


### IntelliJ / Static Analysis Clean-Code Gate

AI-generated code must be written as if it will be pasted directly into IntelliJ IDEA and should be clean under the IDE's Java compiler, Spring inspections, Maven import, and common static-analysis warnings.

- Do not leave unused imports, unused variables, unused private methods, dead branches, duplicate code blocks, or redundant initializers.
- Do not introduce deprecated APIs unless the surrounding code already requires them and the reason is documented in code or the final response.
- Avoid raw types, unchecked casts, unchecked generic operations, nullable contract ambiguity, and Optional misuse that would trigger IntelliJ warnings.
- Keep method signatures, constructor parameters, Lombok annotations, bean names, repository method names, and DTO fields consistent with actual usages.
- Prefer immutable local variables and `final`-style design where it improves clarity, but do not add noisy `final` everywhere if it conflicts with the existing style.
- Do not suppress warnings with `@SuppressWarnings` unless there is no clean alternative. If suppression is necessary, keep its scope as narrow as possible and explain why.
- Make imports deterministic and minimal. Do not use wildcard imports.
- Keep package names, class names, file names, and Spring component stereotypes aligned so IntelliJ does not show unresolved symbols or bean-wiring warnings.
- When changing tests, avoid flaky sleeps, timing-sensitive assertions, order-dependent collections without explicit sorting, and Mockito stubbing that is never used.
- Before presenting code as complete, mentally verify Maven compile, IntelliJ inspections, annotation processing/Lombok, and test compilation. If a warning cannot be avoided, call it out explicitly.

## Concurrency and Thread-Safety Rules

Spring services/components are singleton by default. Treat mutable state inside services, scheduled workers, clients, and caches as shared state.

- Do not store request/user-specific mutable data in singleton service fields. Keep it in method scope, database state, Redis, or explicit immutable value objects.
- Scheduled workers must be idempotent and safe to run concurrently or after a crash/retry.
- When using Redis locks or database status transitions, use TTLs, ownership tokens, compare-and-set style updates, or guarded repository queries where appropriate.
- Avoid race conditions around entitlement sync, trial grant, referral claim binding, referral reward processing, food-log in-flight guards, quota counters, and cleanup workers.
- Prefer database uniqueness constraints plus transaction boundaries for correctness instead of only in-memory checks.
- When processing batches, design for partial failure and repeat execution without double-granting rewards, double-sending notifications, or double-consuming quota.

## API Contract Rules

- Public app APIs should live under `/api/v1/...` unless the existing module uses a legacy route.
- Internal or admin-style APIs should stay under `/internal/...` and must be protected by the existing internal-auth pattern.
- Dev/debug APIs must remain disabled in prod through config flags.
- Every new mutating endpoint must define:
    - request DTO;
    - response DTO;
    - validation rules;
    - auth requirement;
    - idempotency or duplicate-submit behavior when relevant;
    - error mapping.
- Preserve existing Android client contracts unless the user explicitly asks for a breaking change.
- When changing a response shape used by the Android app, add backwards-compatible fields where possible and document migration impact.

## Security and Privacy Rules

This project handles health-adjacent food, nutrition, weight, activity, subscription, and account data. Treat these as sensitive.

- Never hardcode production credentials.
- Never store raw Google Play purchase tokens in plain text.
- Preserve `PurchaseTokenCrypto` usage for encrypted raw purchase tokens.
- Use token hashes for lookup and uniqueness.
- Keep account deletion and data-purge flows conservative and auditable.
- Do not weaken authentication filters, actuator security, Swagger prod blocking, or dev endpoint guards.
- When adding user data, also consider privacy docs, account deletion purge coverage, and retention rules.
- AI nutrition output is not medical advice. Preserve user-editable / fallback behavior in API semantics.

## Database and Migration Rules

- Main SQL files are under `src/main/resources/sql/`.
- Do not rely on `ddl-auto` for production schema changes.
- When adding columns or indexes, provide SQL migration/backfill scripts and keep cross-environment compatibility in mind.
- Existing schema style uses snake_case column names and many UTC suffixes.
- For MySQL JSON columns, keep Java-side `JsonNode` mapping consistent with existing `@JdbcTypeCode(SqlTypes.JSON)` usage.
- When adding enum-like string values, update:
    - entity comments or domain constants;
    - repository queries;
    - service branch logic;
    - tests;
    - manual SQL test scenarios if applicable.
- Avoid destructive data cleanup jobs unless there is an explicit retention requirement and tests.

## Entitlement / Membership Rules

Subscription correctness is commercial-critical.

- `TRIAL` and paid states must be separated from UI labels such as `FREE`, `TRIAL`, `PREMIUM`.
- Premium access should be based on active entitlement windows and Google Play state, not only on client-provided state.
- Treat active paid Google Play subscriptions, canceled-but-not-expired subscriptions, and grace-period subscriptions as entitled when existing service rules do so.
- Payment issue / grace-period states may still be entitled, but the summary should expose enough state for UI warning.
- Do not allow expired, revoked, refunded, or invalid subscriptions to unlock premium features.
- Do not allow duplicate trial grants. Preserve trial eligibility semantics.
- Do not trust the client to decide entitlement. The backend must verify or reconcile.
- Preserve scheduled workers for expiry, Google Play reverify, and acknowledge retry.
- Acknowledge retry requires decryptable raw purchase token. If ciphertext is missing, do not pretend the retry path is fully testable.

## Google Play Referral Reward Rules

Referral reward behavior is especially sensitive.

- If the inviter has an active paid Google Play subscription, the 30-day reward must use real Google Play subscription deferral where possible.
- Do not silently fall back to backend-only reward for an active paid Google Play subscription when Google Play deferral fails.
- For Google Play deferral attempts, preserve reward ledger records including channel, grant status, request/response trace, retryable/final failure, and timestamps.
- Backend-only referral rewards are acceptable only for cases not backed by an active paid Google Play subscription, following existing business rules.
- Referral pending/cooldown/reward-processing states must be idempotent and recoverable.
- Keep privacy boundaries: inviter-facing messages must not expose invitee payment/refund details.
- Internal CS APIs may expose more diagnostic state, but keep them protected.

## Food Log / AI Rules

- Supported creation flows include photo, album, nutrition label, and barcode.
- Preserve request headers such as `X-Client-Timezone`, `X-Device-Id`, `X-App-Lang`, and `Accept-Language` where existing APIs use them.
- Food-log processing must preserve these states and fallback concepts:
    - pending / draft / saved / deleted / failed-like outcomes as represented by existing enums and services;
    - model refusal mapping;
    - provider error mapping;
    - quota and abuse guard;
    - user rate limiting and in-flight limiting;
    - retry behavior;
    - local image cleanup and retention.
- AI provider output must remain structured and sanitized before becoming `effective` nutrition JSON.
- Keep nutrition sanity checks and post-processing. Do not display or persist clearly invalid kcal/macro values without validation.
- Low-confidence or failed recognition should degrade to manual edit/search/barcode-style fallback rather than pretending certainty.
- For barcode/OpenFoodFacts flows, preserve cache TTLs, negative cache behavior, Redis locking/rate limiting, and locale-aware lookup.

## Referral / Notification / Email Rules

- Referral claim lifecycle must remain auditable and idempotent.
- Cooldown / pending reward jobs must avoid stuck `PROCESSING` states.
- Notification and email side effects should be deduped and retryable.
- Do not expose private invitee-level details in regular user-facing responses.
- When changing reward behavior, update membership summary and reward history tests.

## Account Deletion and Retention Rules

- Account deletion must purge or anonymize all owned user data covered by the current DAO/service design.
- If a new user-owned table is added, update account deletion purge coverage.
- If a new local file/blob path is added, update cleanup/retention logic.
- Food images, weight photos, temp blobs, and generated previews must follow explicit retention policies.

## Testing Guidelines by Change Type

Use or add tests near the changed module.

- Auth changes: `auth` controller/service tests and security behavior.
- Entitlement changes: entitlement service, repository scenario, contract, acknowledge/reverify worker tests.
- Referral changes: referral service scenario tests, membership reward tests, membership summary tests.
- Food-log API changes: web contract tests, service tests, provider/error mapper tests, quota/guard tests.
- Food-log AI/provider changes: Gemini parsing/support tests and WireMock integration tests where applicable.
- Barcode changes: normalizer, OpenFoodFacts mapper/effective-builder/cache tests.
- Storage/retention changes: local disk storage, blob orphan cleaner, retention worker tests.
- Profile/activity/water/weight/workout changes: module service/API integration tests.
- Schema-sensitive changes: repository scenario tests and SQL test fixtures.

## Error Handling Rules

- Use existing module-specific exception/advice patterns.
- For food-log errors, preserve `FoodLogAppException`, `FoodLogErrorCode`, and API error response mapping.
- Use validation annotations on request DTOs where possible.
- Avoid returning raw external-provider error bodies to the Android app.
- Keep request IDs in logs/error flow where existing code uses `RequestIdFilter`.

## Observability Rules

- Use structured, low-PII logs for important commercial and operational state changes.
- Preserve and propagate request IDs from `RequestIdFilter`; include them in logs and error diagnostics where available.
- Use MDC for request-scoped values such as request ID and, when safe, internal user ID. Always clear MDC after request/background work to avoid leaking context across threads.
- Never put emails, OTPs, raw purchase tokens, encrypted token ciphertext, image content, full AI payloads, or sensitive health/nutrition details into MDC or logs.
- For external calls such as Gemini, OpenFoodFacts, Google Play Android Publisher API, and SMTP, log outcome, sanitized reason, latency, retry/fallback path, and provider identifier.
- Prefer Micrometer/Actuator-friendly metrics or tags for provider latency, success/failure count, retry count, quota rejection, payment sync outcomes, and referral reward outcomes when adding observable business flows.
- For scheduled workers, log start/end summary, scanned count, changed count, skipped count, failed count, and elapsed time. Do not log every row unless debugging is explicitly requested.

## Configuration Rules

- Dev config may enable fake tokens and debug endpoints.
- Prod config must keep debug endpoints and fake tokens disabled.
- Environment variables should be used for:
    - `GEMINI_API_KEY`;
    - Google web client ID;
    - Google Play service account path;
    - Google Play purchase-token encryption key;
    - SMTP username/password;
    - actuator credentials.
- Do not change prod config to point at local-only resources unless the user explicitly asks for local deployment setup.
- Redis prefix should remain environment-specific.

## Documentation Expectations

When a change affects behavior, update the closest useful documentation or add a short note in the final response.

- API behavior: mention endpoint, request, response, auth, and error behavior.
- DB behavior: include migration/backfill/rollback notes.
- Commercial behavior: include Google Play / trial / premium / referral impact.
- Privacy behavior: include retention, deletion, or data-safety impact.

## Completion Checklist for Agents

Before finishing a task, verify:

1. The changed files are inside the correct bounded context.
2. No generated files, local images, secrets, or IDE files were modified.
3. Public API compatibility was preserved or the breaking change is explicitly documented.
4. Entitlement/referral/payment changes are covered by scenario tests.
5. Food-log/AI changes preserve fallback, quota, error mapping, and manual correction semantics.
6. New user data is covered by account deletion and retention rules.
7. Relevant tests were run or the reason they were not run is clearly stated.
8. The final answer lists exact files changed and tests run.
