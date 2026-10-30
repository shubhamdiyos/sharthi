# Docker

What `docker compose` runs on a laptop, what each file is responsible for, and what
has actually been executed rather than described. The quick start is in the root
[README](../../README.md); this page is the reference behind it.

## What the stack is, and is not

The Compose project is a **development topology**: it publishes the database port,
bind-mounts the storefront source for hot reload, and leaves secrets in a local
`.env`. It is not a deployment strategy — hosting, managed PostgreSQL, TLS and
multi-node are open decision OD-10 (System Design §2.2, §16). The
production-shaped override below hardens the two application services for a single
host; it does not decide where that host is.

```
docker-compose.yml                    root entry point (include + project name)
infra/docker/docker-compose.yml       the development services
infra/docker/docker-compose.prod.yml  single-host hardening override
infra/docker/api.Dockerfile           Maven build stage, JRE-only runtime, uid 10001
infra/docker/web.Dockerfile           Next.js dev stage and runtime stage
```

The root file exists so `docker compose up` works from the repository root. It sets
`name: saathi` and `include`s the real topology; relative paths inside the included
file resolve against `infra/docker/`, which is where the build contexts are written.
`include` needs Compose v2.20 or newer — `docker compose version` answers that before
anything is built.

## Services

| Service | Image / build | Container | Host port | Health | Starts with `up` |
| --- | --- | --- | --- | --- | --- |
| `postgres` | `postgres:16-alpine` | `saathi-postgres` | `${POSTGRES_PORT}` (5432) | `pg_isready` | yes |
| `api` | `api.Dockerfile`, target `runtime` | `saathi-api` | `${API_PORT}` (8080) | `/actuator/health/readiness` | yes, waits for `postgres` healthy |
| `web` | `web.Dockerfile`, target `dev` | `saathi-web` | `${WEB_PORT}` (3000) | `GET /` | yes, soft dependency on `api` |
| `minio` | `minio/minio:latest` | `saathi-minio` | 9000, 9001 | `mc ready local` | only with `--profile storage` |
| `flyway` | `flyway/flyway:11-alpine` | `saathi-flyway` | — | one-shot | only with `--profile tools` |

The `web` service is a soft dependency on purpose: the storefront renders and says
the API is unreachable rather than refusing to start, which is what the drawn error
states are for. The `api` dependency is hard and conditional on `postgres` being
healthy, so the API never opens a connection pool against a database that has not
finished first boot.

Both Dockerfiles use BuildKit cache mounts. Without the buildx plugin the build
stops at `the --mount option requires BuildKit`; confirm with `docker buildx
version` before blaming the Dockerfile.

## Configuration

`cp .env.example .env`, then generate the two secrets with `openssl rand -base64 32`.
`.env` is git-ignored; `.env.example` is the only committed list of keys.

Keys written as `${KEY:?...}` in the Compose file are **required**: an unset value
stops the project before a container is created. That is deliberate — an app that
boots on a placeholder session key is worse than one that refuses to boot.

Inside the network the API reaches the database as `postgres:5432`. The published
`POSTGRES_PORT` is for laptop tools and must never appear in
`SPRING_DATASOURCE_URL`.

`NEXT_PUBLIC_API_BASE_URL` is compiled into the browser bundle at build time. It is
the only `NEXT_PUBLIC_` key the project is allowed, and it is public by
construction: no secret, token or internal hostname belongs in it.

## Lifecycle

```sh
docker compose up -d --build      # build and start everything
docker compose ps                 # wait for "(healthy)" on all three
docker compose logs -f api        # one service's logs
docker compose up -d --build api  # rebuild after a change under services/api
docker compose restart api        # restart without rebuilding
docker compose down               # stop; volumes are kept
```

`apps/web` is bind-mounted, so edits there hot-reload. API sources are baked into
the image, so they need a rebuild.

**Never run `docker compose down -v`.** The `-v` deletes `saathi_pgdata` and
`saathi_s3data` — the entire local database. The volume names are pinned in the
Compose file so a renamed project directory can never look like an empty volume,
and no script, workflow or Dockerfile in this repository runs `down -v`.

## Schema and migrations

The API migrates on boot in the `dev` profile: Flyway reads
`classpath:db/migration` plus the repeatable `db/seed` pilot fixtures, with
`clean-disabled: true`. There is no separate "migrate the dev database" step.

For inspection or a deliberate manual run, the one-shot CLI is in the `tools`
profile and never starts with `up`:

```sh
docker compose --profile tools run --rm flyway info
docker compose --profile tools run --rm flyway migrate
```

Read-only inspection through the running server is also fine:

```sh
docker compose exec postgres psql -U saathi -d saathi -c 'select version, script, success from flyway_schema_history order by installed_rank'
```

## Integration tests

The API's `*IT` suites need a real PostgreSQL 16 on `localhost:5432` — the Compose
database is the expected one:

```sh
docker compose up -d postgres
cd services/api && SAATHI_IT_RECREATE=true mvn -B -ntp verify
```

They create their own database (`SAATHI_IT_DB`, default `saathi_it`) from the
maintenance database and migrate it from scratch, so they never touch `saathi`.
`SAATHI_IT_RECREATE=true` drops and recreates that IT database on every run, which
is how V1 is proven against an empty schema; it never applies to any other database.

The service is named `postgres`, not `db`: `docker compose up -d db` fails with
"no such service", which is the mistake to make once.

## Production-shaped run on one host

```sh
docker compose --env-file .env \
  -f infra/docker/docker-compose.yml \
  -f infra/docker/docker-compose.prod.yml up -d --build
```

`--env-file .env` is mandatory here. Passing Compose files by path moves the project
directory to `infra/docker/`, where the root `.env` is not read, and every
`${KEY:?...}` fails with "required variable … is missing a value". That failure was
observed, not predicted.

The override requires `SAATHI_RELEASE` (an immutable tag; `:latest` is refused),
`PROD_DB_URL` / `PROD_DB_USER` / `PROD_DB_PASSWORD`, and the build argument
`PUBLIC_API_BASE_URL` (the browser-reachable API origin, inlined at build time). It
then:

* drops the published database port and reassigns `postgres` to a profile nobody
  enables — production uses managed PostgreSQL, and `api`'s `depends_on` is
  overridden to `{}` because Compose refuses a dependency on a service that is no
  longer defined;
* removes the dev bind mounts and command from `web`, running `node server.js`
  from the image instead;
* sets `read_only` filesystems with `/tmp` tmpfs, `cap_drop: ALL`,
  `no-new-privileges`, and CPU/memory limits per service;
* switches Flyway to `validate` and leaves it in the `tools` profile, while
  `spring.flyway.enabled: false` in the prod profile keeps the API from migrating
  on boot. A rolling restart therefore never changes the schema; the operator's
  step is `docker compose ... --profile tools run --rm flyway`.

The network name is inherited deliberately. Renaming it in the override was tried
and measured to be a trap: Compose treats both files as one project, so a second
network name orphans `saathi_net` and `up -d` tries to remove a network that a
development container still holds.

## What has been verified

On macOS arm64 with Colima (`colima start --cpu 2 --memory 4 --disk 20`) and
Compose v2:

* `docker compose up -d --build` from the root built `saathi-api:dev` and
  `saathi-web:dev`, and `docker compose ps` reported `postgres`, `api` and `web`
  healthy. `curl -s localhost:8080/actuator/health/readiness` answered on the
  healthcheck path, and `GET http://localhost:3000` rendered the storefront with
  the *API ready* card.
* The production-shaped override was started with the command above against a
  throwaway database: the database port stayed unpublished, both application
  services ran read-only, `flyway validate` exited 0 against the migrated schema
  and 1 against an unmigrated one.
* On 2026-10-31 the Compose `postgres` container served the whole API suite:
  `SAATHI_IT_RECREATE=true mvn verify` created `saathi_it`, applied V1–V4 and the
  repeatable seed, and ran 148 unit plus 115 integration tests green without
  touching `saathi`. The development database receives V4 the next time the API
  container starts, because migrations run on boot in the `dev` profile.

Not verified, and deliberately not claimed here: image sizes, sustained memory use
under load, and any behaviour on a second host. Those belong to the deployment
decision (OD-10), not to this file.
