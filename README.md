# MIRIV backend

Spring Boot 4.1.1 / Java 25 REST API backed by PostgreSQL. The code is organized by business feature (`identity`, `catalog`, `cellar`, `laboratory`) with controller, service, repository and DTO boundaries. Flyway owns the schema; Hibernate only validates it.

## Local start

1. Install Java 25 and Maven.
2. Start PostgreSQL with `docker compose up -d` from this directory, or provide a compatible local PostgreSQL instance.
3. Run `mvn spring-boot:run` with Java 25 selected.
4. Open `http://localhost:8080/actuator/health` or `http://localhost:8080/docs`.

The `dev` profile uses `jdbc:postgresql://localhost:5432/miriv`, username/password `miriv` unless `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` override them. Flyway applies `V1`–`V15` on startup. For any non-development profile, database settings and `JWT_SECRET` are mandatory environment variables. The local seed account is `m.solana` with password `ChangeMe123!`; **rotate or disable it before any non-local deployment**. The current migrations seed this account in every new database, so the project is not ready for public deployment as-is. The `DefaultCredentialsGuard` ApplicationRunner blocks startup under any profile other than `dev` or `test` while the `ChangeMe123!` sample password still authenticates an active user — change those credentials (or deactivate the account) before booting under `prod`/`staging`.

## Implemented API

- `POST /api/auth/login`: JWT authentication.
- `/api/catalogs/*`: catalog administration and lookup.
- `/api/deposits`: scoped deposit list, detail, create and update.
- `/api/deposits/{code}/cleaning`: start cleaning and record an approved or failed release inspection.
- `/api/lots`: lot list, detail, create (optionally with an atomic initial entry), update, genealogy.
- `POST /api/movements`: exit, full/partial transfer and explicitly authorized mixture, with row locks, capacity checks, movement lines and content lineage.
- `/api/laboratory/samples`: historical sample identity, draft results, validation, correction versions and invalidation.
- `/api/contents/{code}`: content detail and enologist state review.
- `GET /api/work-home`: operational counters, incidents, own tasks and recent movement activity.
- `/api/plans/contents/{contentCode}`: create and read an elaboration plan; append immutable plan versions.
- `/api/tasks`: create, list, start, complete and cancel operational tasks, with content-location and sample checks.
- `/api/incidents`: read, acknowledge, assign, silence, resolve and discard incident episodes with an event trail.

All write endpoints require a bearer token. Example:

```sh
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"m.solana","password":"ChangeMe123!"}'
```

## Verification

Run `mvn test` with Java 25 and a local PostgreSQL database matching the `dev` connection settings. The integration test is transactional and rolls back its operational fixtures. It exercises initial entry, transfer, genealogy, historical sample resolution, result validation, correction and invalidation.

## Remaining scope

The functional analysis also covers plan templates and phase criteria, operations, automated rule evaluation and incident creation, reports, notifications and administration. Their tables exist, but their complete REST workflows are not yet implemented. The frontend has no mock mode left: it always talks to this API through a shared authenticated HTTP client, and the screens for the areas above (plans beyond a single content's elaboration plan, operations, rules, reports, administration, audit) still show static placeholder content because there is nothing on this backend for them to call yet.
