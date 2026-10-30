# Saathi

A local-delivery platform for one city: customers order from shops near them,
merchants accept and fulfil, riders carry, and an operator sees the whole day.
This repository is the **foundation**, not the product — a running API, database
and storefront shell, with the engineering pipeline that keeps them honest.

Nothing here invents a catalogue, a price or a customer. Those depend on product
decisions that are not signed off yet, and a demo built on invented data is a
demo that lies. What exists instead is the part that must be right before any of
it can be: configuration that refuses to start on a missing secret, a schema the
database itself enforces, and a health check the storefront can read.

## What runs

| Service | Image | On your machine | What it is |
| --- | --- | --- | --- |
| `postgres` | `postgres:16-alpine` | `localhost:5432` | The database. The only stateful service. |
| `api` | Java 21 / Spring Boot 4.1 | `localhost:8080` | JSON API, Flyway migrations, Actuator health. |
| `web` | Node 24 / Next.js 16 | `localhost:3000` | Storefront shell. Its home page reads the API's readiness **from the server**, never from the browser. |

`minio` (object storage) exists behind a Compose profile and is off by default;
media upload is not built yet.

```
apps/web/              Next.js storefront
services/api/          Spring Boot API and the Flyway migrations in src/main/resources/db/migration
infra/docker/          Dockerfiles and the real Compose topology
infra/ci/              the gates CI runs as scripts (OSV severity, licences)
docs/devops/           how the pipeline works and what it costs
docker-compose.yml     the root entry point: `docker compose up` works from here
```

## Before you start

* **Docker** with Compose v2 or newer, plus the **buildx** CLI plugin. The
  Dockerfiles use BuildKit cache mounts, so without buildx the build stops at
  `the --mount option requires BuildKit`. Docker Desktop bundles buildx; on
  Colima it is a plugin file at `~/.docker/cli-plugins/docker-buildx`. Check it:

  ```sh
  docker buildx version    # prints a version, not "unknown command"
  ```

* **Only if you want to run tests outside containers:** Java 21 + Maven for the
  API, Node 24 (see `.nvmrc`) for the storefront. `docker compose` needs neither.

## Run it

```sh
cp .env.example .env          # first time only; .env is git-ignored
docker compose up -d --build
docker compose ps             # wait for (healthy) on all three
```

Open **http://localhost:3000**. The status card should say *API ready* and list
`db` and `readinessState`. Then:

```sh
curl -s localhost:8080/actuator/health/readiness
curl -s localhost:8080/actuator/health
```

The API also answers at `localhost:8080/api/v1/…`; a path that does not exist
returns a JSON error with a `request_id`, not an HTML page.

### Change the development secrets

`.env` ships with placeholders that are worthless outside your laptop. The two
required secrets are `SAATHI_SESSION_SECRET` and `SAATHI_AUDIT_IP_KEY`:

```sh
openssl rand -base64 32      # run twice, paste into .env
docker compose up -d         # restart picks them up
```

Compose refuses to start if either is missing — that is a feature, not a bug: an
app that boots on a default signing key is an app that signs sessions with a key
everybody knows.

## Day to day

```sh
docker compose logs -f api          # follow one service
docker compose restart api          # restart without rebuilding
docker compose up -d --build web    # rebuild one image
docker compose down                 # stop everything, KEEP the data
```

**Never run `docker compose down -v`.** The `-v` deletes `saathi_pgdata` and
`saathi_s3data`, which is the entire local database. Nothing in this repository
runs it, and no script should.

`apps/web` is bind-mounted into its container, so editing a file under
`apps/web` hot-reloads the page. Changes under `services/api` need
`docker compose up -d --build api`.

## Tests and checks

```sh
# Storefront — the same four commands CI runs
cd apps/web && npm ci && npm run lint && npm run typecheck && npm test && npm run build

# API — unit and architecture tests, no database needed
cd services/api && mvn -B -ntp test

# API — integration tests need a PostgreSQL 16 on localhost:5432
cd services/api && SAATHI_IT_RECREATE=true mvn -B -ntp verify
```

The integration suite creates its own database (`SAATHI_IT_DB`, default
`saathi_it`) and migrates it from scratch; it never touches the Compose database.
The same suites run in GitHub Actions on every push — see
[CI-CD.md](docs/devops/CI-CD.md) for which workflow proves what, and
[SECURITY.md](docs/devops/SECURITY.md) for the gates and the monthly checks that
are not automated.

## When something is red

| Symptom | Cause | What to do |
| --- | --- | --- |
| Build stops: `the --mount option requires BuildKit` | No buildx plugin, so Docker used the legacy builder | Install buildx, confirm with `docker buildx version`, rebuild |
| `Bind for 0.0.0.0:5432 failed: port is already allocated` | Something else owns the port (often another Postgres) | Change `POSTGRES_PORT` (or `API_PORT` / `WEB_PORT`) in `.env` |
| API container restarts immediately, log mentions `Could not resolve placeholder` | A required key is missing from `.env` | Compare with `.env.example`; copy the missing key |
| Page loads but says *API not ready* | API is down or still starting; the page is designed to render anyway | `docker compose ps`, then `docker compose logs api` |
| `web` healthy but page shows a compile error | Source bind mount; the error is the real one | Read it; the container log shows the same message |

## Status, honestly

Done and verified end to end: the three-service stack, readiness plumbing from
storefront to database, migrations under Flyway, CI for both languages, and the
security gates that run without a paid GitHub plan.

Not built: catalogue, cart, checkout, merchant and rider apps, payments, OTP
delivery, media upload. Those are design-first work, and their open decisions
are tracked outside this repository. Treat this README as a description of what
runs today, not of what is planned.
