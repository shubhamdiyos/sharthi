# CI/CD

Everything in this file describes what is installed in this repository, not what is
planned. Anything that is deliberately **not** enabled is named as such in
[Not enabled yet](#not-enabled-yet), with the reason.

## What runs, and what it proves

| Workflow | Triggers | Jobs | The claim it backs |
| --- | --- | --- | --- |
| `ci.yml` | push to `main`, PR into `main`, manual | `unit`, `integration`, `web` | The code compiles, the architecture rules hold, the schema builds, the wire contract is the documented one, the Spring context boots on the properties file that Compose mounts, and the storefront lints, type-checks, passes its unit tests and builds the standalone output the web image copies. |
| `database-migrations.yml` | push to `main` / PR into `main`, **only when it touches a migration, `infra/ci/`, or the workflow itself** | `migrations` | The PostgreSQL server itself refuses what the design says it refuses, an edited migration is caught by checksum, and re-applying a full set changes nothing. |
| `security.yml` | push to `main`, PR into `main`, manual | `dependencies`, `secrets` | The exact resolved dependency set carries no unapproved CRITICAL/HIGH advisory and no unapproved licence, and no credential exists anywhere in the history or the working tree. |
| `.github/dependabot.yml` | Mondays 06:00 IST, GitHub-side | — | Version updates: grouped and throttled to five open pull requests — and **measured working**, not configured-and-hoped: a `Dependabot Updates` run went green two minutes after this file reached `main`, and PR #4 (`archunit-junit5` 1.4.1 → 1.5.1) arrived from `app/dependabot` with its own CI. Security alerts: **not available here**, `GET …/dependabot/alerts` answers 403 "Dependabot alerts are disabled for this repository". Dependency risk is carried by `security.yml`'s OSV scan, which needs no GitHub feature at all. |

`pull-request.yml` is not a fifth file, and that is a decision rather than an
omission: §1 requires the pipelines to exist *and* says to combine responsibilities
where that improves maintainability. A pull request into `main` is validated by the
same three jobs against the same commit as the branch push, so a separate file would
be a copy. The `pull_request` event is what a reviewer sees as the required checks.

The `web` job arrived in the pull request that created `apps/web`, not before it. A
job that passes because it found nothing to check is the false green already closed
once in Failsafe, so there was never a run of a frontend job with no frontend.

### Where each of §2's quality gates lives

| Gate | Enforcement |
| --- | --- |
| Compilation failure | `unit` job: `mvn -B -ntp test` cannot start without a compile. |
| Test failure | `unit` (100 tests) and `integration` (39 tests), both with `failIfNoTests=true`; `web` runs the Vitest suite, which fails on an empty run too. |
| Type-check failure | Java: the compiler *is* the type check. TypeScript: `tsc --noEmit` in `web`, before the build, so the message names the file rather than a bundler stage. |
| Migration failure | `integration` migrates a fresh database through the application's own Flyway; `database-migrations` re-applies the same set through the Flyway CLI and asserts the server-side constraints. |
| Unapproved critical vulnerability | `security.yml → dependencies`: a CycloneDX SBOM of the resolved graph, scanned against OSV, gated by `infra/ci/osv-gate.sh` on CRITICAL and HIGH. |
| Unapproved dependency licence | same job, `infra/ci/license-gate.sh` over the same SBOM: every licence a component *offers* is checked against the approved list at the top of that script — SPDX ids, plus one pom-declared licence name (`Bouncy Castle Licence`, which has no SPDX id; the reasoning is in `SECURITY.md`) — and a component with no licence metadata fails. |
| Production build failure | `mvn -B -ntp verify` in `integration` packages the jar that the api image copies; `next build` in `web` produces the `.next/standalone` tree the web image copies. |

"Never bypass failing checks to complete a merge" is **not** machine-enforced on this
repository, and pretending otherwise would be the most useful lie in this file.
Required status checks are a branch-protection setting, and on a private repository
under GitHub Free the API refuses it: *403 "Upgrade to GitHub Pro or make this
repository public to enable this feature."* So today the rule is held by the merge
path — `gh pr checks <n> --watch` is read before any merge, and `gh pr merge` is
never given `--admin` — rather than by GitHub refusing. That is a process, not a
control, and it is on the list in [Not enabled yet](#not-enabled-yet) because closing
it properly costs money.

## The cycle, as actually executed

```
git fetch origin main
git switch -c feature/<module> origin/main
…implement, and run the local checks below until they are green…
git commit            # author: shubhamdiyos <shubham@diyosinfotech.com>
git push -u origin feature/<module>
        → Actions runs ci.yml (+ security.yml, + database-migrations.yml if it touched SQL)
gh pr create --base main --head feature/<module>
gh pr checks <n> --watch          # wait for the real verdict, do not assume it
gh pr merge <n> --admin=false --delete-branch   # merge only once every check passes
git fetch origin main:main        # update the non-checked-out local main
git switch main && git pull --ff-only
git rev-parse HEAD && git rev-parse origin/main   # must print the same SHA
        → only now start the next module on a new branch
```

`main` is the default branch. Nothing is ever pushed to it directly: the last two
`git push` calls in this repository's history were both feature branches, and both
reached `main` through a merged pull request. `--ff-only` on the pull is the guard
that a stray local commit on `main` would trip immediately.

## Reproducing every check locally

Same commands, same images, no CI required. From the repository root, with Colima
running (`colima start`) and the Compose database up:

```bash
cp .env.example .env                     # never committed (.env is gitignored)
docker compose up -d postgres

cd services/api
mvn -B -ntp test                         # unit + ArchUnit: no daemon, no database
mvn -B -ntp verify                       # + integration tests against PostgreSQL 16
```

The migration and security jobs are backed by scripts in `infra/ci/`, so they are
runnable rather than only readable:

```bash
export PATH="$PWD/infra/ci/bin:$PATH"
set -a; . ./.env; set +a
export SQL_DIR=services/api/src/main/resources/db/migration
export PG_USER="$POSTGRES_USER" PG_PASSWORD="$POSTGRES_PASSWORD" PG_PORT=5432

psql -d postgres -c 'CREATE DATABASE saathi_migration'
flyway "-url=jdbc:postgresql://127.0.0.1:5432/saathi_migration" migrate
flyway "-url=jdbc:postgresql://127.0.0.1:5432/saathi_migration" validate

# SBOM, then the OSV gate that CI blocks on
cd services/api && mvn -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DincludeTestScope=true && cd ../..
docker run --rm -v "$PWD:/workspace" -v "$PWD/services/api/target:/sbom" \
  ghcr.io/google/osv-scanner:v2.0.2 scan source -S /sbom/bom.json \
  --all-packages --format json --output /sbom/osv.json
sh infra/ci/osv-gate.sh services/api/target/osv.json
sh infra/ci/license-gate.sh services/api/target/bom.json

# Secrets, working tree
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.21.2 \
  detect --no-git --source=/repo --redact
```

Two honest caveats about running these at home:

* The local `--no-git` secret scan reads `.env`, because `.env` is real and it is
  in the directory. In CI the workspace is a checkout, so the same command sees
  only tracked files. A local hit on `.env` is correct behaviour, not a bug.
* `psql` and `flyway` here are the repository's wrappers, and they shell out to
  `docker run`. They expect a server reachable on `127.0.0.1:5432` — the port
  Compose publishes.

## Cost policy, and what a run actually costs

The mandate is zero unexpected spending, so the numbers are stated before the run,
then measured, then left in the file even when the estimate was wrong.

Runner policy: **`ubuntu-latest` only**, in every workflow, in every job. No
larger-runner family, no macOS, no Windows — those bill at multiples of the Linux
rate. GitHub Free gives a private repository **2,000 Linux minutes a month**,
**500 MB** of artifact + package storage, and a **separate 10 GB** of cache storage
per repository. The default spending limit is $0, which means the allowance is a
hard stop rather than a meter — an exceeded quota fails runs, it does not invoice.

**A branch commit is validated once, because only `main` triggers on push.** The first
version of this paragraph reasoned about it and got it backwards, and the correction was
measured rather than argued: PR #3 was opened ~10 minutes after its push, so the push runs
had finished and the `pull_request` event paid for a whole second set (its jobs ran 70 s,
64 s, 56 s). The next commit, pushed while the pull request was open, started both events
within 3 seconds of each other; the concurrency group cancelled the `push` runs and the
`pull_request` runs went green, and the cancelled twins were still billed — **302 s = 5.03
Linux minutes** for that commit, 192 s of completed work and 110 s of runs stopped
mid-job. `cancel-in-progress` stops *further* billing; it does not refund what was already
spent. So `push: branches: ['**']` was wrong on cost, not on safety: the pull request of a
branch is the check for that branch, and a branch with no pull request has no artefact
anyone deploys. Restricting the push trigger to `main` removes the duplicate set and the
race that produced the extra cache entries below.

| | Estimated before the first run | Measured |
| --- | --- | --- |
| `ci.yml`, one branch push | ~8 min (2 min unit + 6 min integration) | **110 s** with two jobs (`unit` 38 s, `integration` 72 s, cold cache), then **104 s** with the `web` job added on `7bfd75e`: `unit` 19 s, `integration` 56 s (warm Maven), `web` 29 s (cold npm cache, install 10 s) |
| `security.yml`, one branch push | ~5 min | **51 s** on its first run (`dependencies` 44 s, `secrets` 7 s) and **51 s** again after the licence gate was added (`dependencies` 39 s, `secrets` 12 s) — the new step is 26 ms of `jq` over a file the job already produced. Measured again on `7bfd75e`: **39 s** (`dependencies` 29 s, `secrets` 10 s) |
| `database-migrations.yml`, one SQL-touching push | ~3 min | **50 s**, and it went red on run 1: the assertion expected the UTC day where the server produced the Indian one. The estimate of its duration was fine; the check was the point. |
| One commit, `ci` + `security`, both events | — | **298 s = 4.97 Linux min** on `7bfd75e`: 143 s of completed `pull_request` work (104 + 39) and **155 s billed by the five `push` jobs that the concurrency group cancelled** (29 + 23 + 64 + 31 + 8). The earlier three-workflow commit measured 302 s; five jobs racing makes the cancelled half more expensive than four did, which is why the commit total did not fall when the completed half did. **This row is the behaviour before `push` was narrowed to `main`, and it is kept because it is what the fix was priced against** — the duplicate set it describes no longer starts. Its replacement is measured on the first module merged under the narrowed triggers: PR #12, four pushes, `ci` + `security` + `database-migrations` each starting exactly once per push and **no job cancelled at all**, then one set on `main` for the merge commit. **1,364 s = 22.7 Linux min for the whole module** — the per-push breakdown is the table below this one. Per push that is ~4.5 min against the ~5 min a single commit cost when both events fired, but the comparison that matters is what the money bought: two of those four sets went red on gates no local run can reproduce (an OSV advisory against a jar the pom pinned, then the licence gate behind it), and none of it was spent on a cancelled twin. |
| Maven cache | one shared entry, ~250 MB | **ten entries under five keys, 684 MB** at the end of 31 October — `GET …/actions/caches` lists them, and two effects are visible in that list rather than one. Keys hold more than one entry (`…c5110fbd…` holds three identical copies, `…0fe1054…` two): GitHub keeps older versions of a key it is asked to save again, so "one entry per key" was never the storage model. And the key itself is a hash of `services/api/pom.xml`, so each dependency edit in that file mints another one — the `bcprov-jdk18on` 1.82 → 1.85 → 1.86 sequence inside PR #12 and #13 added two keys in one day. What a new key actually costs is one download, not a rebuild: `unit` was 54 s on the first run after the pom change (prefix restore, fetch the new jar, save) and 23 s on the next run of the same pom. |
| npm cache | one entry, ~120 MB | **three entries, 345 MB** at the end of 30 October, all under the key for `apps/web/package-lock.json`. Each commit whose `push` and `pull_request` web jobs start together on an uncached key can add one: both finish `npm ci` before the concurrency group cancels the `push` run, and both save. `node_modules` for this app is 121 MB compressed, so the npm key is the fastest-growing part of the store — one entry per racing commit, until entries age out at 7 days. Narrowing `push` to `main`
closes that source: one `web` job per commit means one save per key. Measured total for everything after PR #15: **16 entries, 1,379 MB** (13.5 % of the separate 10 GB cache allowance), up from 14 entries and 1,147 MB at the end of PR #12. |
| Artifacts per successful run | 0 bytes | **0** — confirmed through the API, not by reading the YAML: `GET /repos/shubhamdiyos/sharthi/actions/runs/41056239592/artifacts` returns `total_count: 0`. Uploads happen only on failure. |

So the honest figure is **~3-4 Linux minutes per set of runs that finishes**, and the
spread is the suite's own growth rather than a measurement error: the 193 s set the three
rows above add up to was taken when the integration suite was smaller, and PR #12's green
set cost **247 s** —
`ci` 139 s (unit 23, integration 81, web 35) + `security` 42 s + `database-migrations` 66 s
— with 93 integration tests against PostgreSQL. Both are well under what this file
estimated before it had run once. A module merge is **two** sets, and saying
only one would be the kind of arithmetic that gets budgets wrong: the `pull_request` set
that gates the merge, and the `main` set that validates the merge commit, because the
merge commit is the artefact that ships. PR #12 is the first module measured end to end
under the narrowed triggers — 15 runs, 30 jobs, nothing cancelled — so the budget comes
from it rather than from a per-job sum:

| | Measured on PR #12 |
| --- | --- |
| Push 1 `73ca02a` | 295 s — went red on five OSV advisories against `bcprov-jdk18on` 1.82 |
| Push 2 `92c8921` | 283 s — OSV clean, went red on the licence gate behind it |
| Push 3 `a5445d8` | 247 s — green |
| Push 4 `2d8277a` | 249 s — green, this file's own corrections |
| Merge `1195121` on `main` | **290 s** — unit 48, integration 86, web 37, `dependencies` 49, `secrets` 13, Flyway 57 |
| **The whole module** | **1,364 s = 22.7 Linux min**, 1.1 % of the monthly allowance |

The shape of that number is `pushes × ~4 min + one main set`, so a three-push module is
~17 min and a four-push module is the 22.7 above. Two of the four sets were spent going
red on a gate, which is the expensive part of *this* module and the part that shrinks once
a module stops adding dependencies: two green pushes and a merge would have been 14 min.

Day 1B (PR #15) is the second module measured the same way, and it is the cheaper shape:
two pushes and a merge, **880 s = 14.7 Linux min**, 0.7 % of the allowance.

| | Measured on PR #15 |
| --- | --- |
| Push 1 `5b62821` | 332 s — green except `Leaked credentials` (7 s): a test passphrase the generic-api-key rule read as a key, which the new `.gitleaks.toml` allowlist now scopes to that literal shape. `dependencies` took 102 s here against 36 s and 31 s on the two later runs — the OSV database was cold. |
| Push 2 `f81d361` | 276 s — green: `ci` 154 (unit 38, integration 79, web 37), `security` 46, Flyway 76 |
| Merge `d780a55` on `main` | 272 s — unit 35, integration 83, web 41, `dependencies` 31, `secrets` 11, Flyway 71 |
| **The whole module** | **880 s = 14.7 Linux min**, 0.7 % of the monthly allowance |
| Cache after the module | **16 entries, 1,379 MB** (13.5 % of the 10 GB allowance); artifacts **0** |

The estimate before the first push was the ~13 min the paragraph above predicts for this
shape; the difference is the cold OSV scan, paid once and not again.

Day 1C (PR #18) is the cheapest shape so far: one push and a merge, **571 s = 9.5 Linux
min**, 0.5 % of the allowance, and no set went red.

| | Measured on PR #18 |
| --- | --- |
| Push 1 `e3bae7d` | 287 s — `ci` 150 (unit 28, integration 96, web 26), `security` 41 (`dependencies` 31, `secrets` 10), Flyway 96 |
| Merge `e37eb6d` on `main` | 284 s — unit 38, web 35, integration 75, `dependencies` 40, `secrets` 11, Flyway 85 |
| **The whole module** | **571 s = 9.5 Linux min**, 0.5 % of the monthly allowance |
| Cache after the module | **16 entries, 1,379 MB** — unchanged in size from the Day 1B snapshot: the pom and the lockfile did not move, so every job restored and re-saved the keys it already had |
| Artifacts | **0 bytes** — `GET …/actions/artifacts` still reports none |

The integration job is the row to watch as modules land: 96 s here against 75–83 s on the
earlier modules, because the cart/quote/order suite adds a Flyway-recreated database and
eleven HTTP tests. The estimate before the push was ~4.5 min per set, the same shape as
Day 1B; the measured 4.8 min per set is that growth, not a new cost source.

Day 2A (PR #20) is the same one-push shape and the same nothing-red total: **652 s = 10.9
Linux min**, 0.54 % of the allowance.

| | Measured on PR #20 |
| --- | --- |
| Push 1 `e2b122a` | 306 s — `ci` 176 (unit 26, integration 94, web 56), `security` 39 (`dependencies` 27, `secrets` 12), Flyway 91 |
| Merge `08559ca` on `main` | 346 s — `ci` 210 (unit 26, integration 94, web 90), `security` 40 (`dependencies` 32, `secrets` 8), Flyway 96 |
| **The whole module** | **652 s = 10.9 Linux min**, 0.54 % of the monthly allowance |
| Cache after the module | **16 entries, 1,379 MB** — the newest entry is still 30 October 20:23 UTC, eight and a half hours before this module's first run, because the pom and the lockfile did not move and every job re-saved a key it already had |
| Artifacts | **0 bytes** — `GET …/actions/artifacts` still reports none |

This is the first module whose whole increment is suite rather than plumbing, so the two
rows that moved are the two that should have: **144 integration tests against 126** on the
previous `main` run, and the job that runs them 94 s against 86 s. Eighteen new HTTP tests
for 8 s, on a job whose fixed cost is a Flyway-recreated database, is the cheapest growth
available here. The `web` job's 56 s and 90 s are the same tree built twice, and the whole
34 s of that difference sits inside `npm ci` — 39 s on the PR against 65 s on `main` — while
lint, type-check, the unit tests and the build move by a second or two apiece. Neither run
created a cache entry, so both restored the same key: what varied is how fast a runner
unpacks `node_modules`, not the work and not the cache. Averaged, the pair is 73 s, and this
file's ~4.5 min per set stands rather than being revised for one slow step. The estimate
before the push was Day 1C's 9.5 min because the trigger shape had not changed; the measured
10.9 min is 1.4 min of suite growth on a module that added no dependency and minted no new
cache key.

At one module a working day with three pushes in it, that is **~360 minutes a month
against the 2,000 free** — under a fifth of the allowance, and the figure to re-measure
rather than defend.
For calibration, the day these workflows were built is measurable rather than projected:
**28 runs, 48 jobs, 1,662 seconds = 27.7 Linux minutes**, 1.4 % of the monthly allowance
spent in one day — and that was the expensive case, with three workflows being written,
re-validated and debugged.

Two numbers this file deliberately does not claim: total account storage and bandwidth.
Neither is readable through the API available to the repository, both live on the
account's billing page, and the first figure GitHub publishes is a 500 MB pooled
allowance for artifacts plus packages that this pipeline currently uses 0 bytes of.
They belong in the founder's monthly check, listed in
[SECURITY.md → What a reviewer should check monthly](SECURITY.md).

Controls that make those numbers stay small:

* **One trigger set per commit.** `push` fires on `main` only; a branch is validated by
  the `pull_request` run of the PR opened from it, which is the workflow's own rule — one
  branch per module, and the branch is not finished until it has a pull request. Two
  events on one branch commit used to start within seconds and the concurrency group
  picked the winner, but the loser had already billed: 110 of 302 seconds, then 155 of
  298. `cancel-in-progress` stops *further* billing for an aborted run; cancelling is a
  saving, not a refund, so the fix was to stop starting the run. The gap this accepts is a
  branch pushed with no pull request — nothing runs for it, and nothing ships from it.
  `concurrency` stays keyed on the branch name, which now does the other job: two pushes
  to one branch in quick succession collapse to the newest commit.
* **`timeout-minutes` on every job** (10–20), so a hung build cannot drift toward
  the 360-minute platform default.
* **One Maven cache key, shared.** `cache-dependency-path` is scoped to
  `services/api/pom.xml`, so the key is identical in all three Java jobs and a job that
  starts after another has saved restores it instead of re-downloading the graph. It also
  means an unrelated pom change elsewhere cannot invalidate the cached Spring Boot. What
  it has *not* achieved is a single stored entry — see the measured row above; five
  entries exist, and the allowance absorbs them.
* **One npm cache key, `apps/web` only.** `setup-node` with
  `cache-dependency-path: apps/web/package-lock.json`, so the storefront's install is
  restored by the run after the one that saved it, and an unrelated change elsewhere in
  the repository cannot bust it. This is the largest and fastest-growing entry: ~121 MB,
  with a new copy per commit whose two web jobs race on a cold key. The watch threshold
  is therefore a *count* of npm entries, not total bytes: if the npm key accumulates
  more than roughly a dozen entries before 7-day expiry catches up, caching it is
  costing more storage than my not caching would cost in minutes.
* **No Docker build in CI at all.** Nothing here builds an image: GHCR is not
  approved, so an image would be bytes that no environment runs. `verify` packages
  the jar, which is the same artifact the api image later copies.
* **Artifacts only on failure.** `if: failure()` on the only upload step, 7-day
  retention, and the failsafe XML reports only (~30 KB per class).
* **Path filter on the migration job.** Server-side constraint assertions and the
  tamper proof only change when a `.sql` file does; running them on a commit that
  touched no SQL would re-spend free minutes to re-assert yesterday's answer. Migrations
  still apply on *every* push, because `integration` boots the application against a
  freshly migrated database.
* **Pinned tool images and a pinned plugin coordinate** (`osv-scanner:v2.0.2`,
  `gitleaks:v8.21.2`, `cyclonedx-maven-plugin:2.9.1`, `flyway/flyway:12.4.0-alpine`,
  `postgres:16`). A moving tag is a cache-buster and an unreproducible verdict.
* **Dependabot's own throttle.** `open-pull-requests-limit: 5`, weekly, grouped by
  ecosystem — because every PR it opens (from the first Monday after it reaches `main`)
  costs the minutes in the table above.

Nothing in this pipeline can reach a paid feature without a human changing a setting,
and the backstop if a human is wrong is the account's $0 spending limit: an exceeded
allowance fails runs, it does not invoice.

## Not enabled yet

Each of these is a decision the founder has not approved, so none of them is
switched on "so that it is ready". They are listed in the order §13 sets out, except
the first row: merge protection belongs to §3's cycle, and it is here because it is
the only one of these that a script cannot work around.

| Step | What it needs | Cost position |
| --- | --- | --- |
| 3 — Merge protection | Branch protection on `main`: the four check names as required contexts, and "require branches to be up to date before merging" so a PR that fell behind `main` is re-validated rather than merged on an old verdict. Attempted through the API; it returned **403 "Upgrade to GitHub Pro or make this repository public to enable this feature."** | **GitHub Pro for the account, or making the repository public.** Public visibility would enable it for free but publishes the source, the merchant onboarding logic and the pilot data model, so it is a business decision rather than a setting. Neither is taken without approval. |
| 5 — GHCR images | `release.yml`: build api + web once per `v*` tag, SHA-tag them, scan them with Trivy, attach the SBOM, then push. A retention rule to delete old versions. | GitHub Packages gives 500 MB free, **shared with the artifact allowance above**; container storage beyond that is billed per GB-month. Needs approval, and needs a retention policy decided before the first push. |
| 6 — Staging | A cloud account, a managed Postgres, DNS, TLS. Deployment workflows with a `staging` environment. | Any real cloud resource is paid, and it is the reason the "cloud-portable" parts of the design (12-factor config, one image per service, no host paths, no local sockets) exist. **Nothing is provisioned without explicit approval.** |
| 7 — Production | The same as staging plus a protected environment with a required-approver gate, and OIDC to the cloud provider instead of a stored access key. | Same. OIDC itself is free and is the design's preference precisely so that no long-lived cloud credential ever exists in this repository. |
| 8 — More security tooling | CodeQL and the native gitleaks action, secret scanning push protection, Dependabot *alerts* and security *updates*, and Dependabot on branches other than the default. | CodeQL, gitleaks-action and Dependabot alerts are GitHub Advanced Security features on a private repository: **paid per committer**. The free equivalents already run in `security.yml`, which is why they are not here — the OSV scan over the resolved SBOM is the dependency gate, and it does not need Dependabot alerts to exist. |
| 9 — Backup/restore + rollback drills | `docs/devops/DISASTER-RECOVERY.md` and `ROLLBACK.md`, and a rehearsal that restores a `pg_dump` into a scratch container and proves the row counts. | Free: `pg_dump` inside the existing Compose stack, on the laptop. |

`docs/devops/DEPLOYMENT.md`, `ROLLBACK.md` and `DISASTER-RECOVERY.md` arrive with
the steps they document, because a deployment runbook that describes an
environment that does not exist yet is documentation that will be wrong before it
is read. `DOCKER.md` arrives with `apps/web`, whose Dockerfile is the second half
of it.

## Reading a failure

* `unit` red, `integration` green → a kernel rule changed; the ArchUnit message names
  the package and the rule.
* `integration` red on `ApplicationContextIT` → a property in
  `application.yml`/`application-prod.yml` cannot be bound. This job is the only
  place the real configuration is read; the unit suite never starts a context.
* `database-migrations` red on "validate accepted an edited migration" → someone
  amended a shipped `.sql` file. That file must be reverted and replaced by a new
  `V<n>__*.sql`; §8's backward-compatibility rule applies.
* `security.yml → dependencies` red with `BLOCKING` rows → an unapproved CRITICAL/HIGH
  advisory in a resolved dependency. Upgrade through a PR, or, if the advisory does
  not apply, say so in `docs/devops/SECURITY.md` and add a pinned ignore — not in
  the workflow, which is where a silent exception would hide.
* `security.yml → dependencies` red with `UNAPPROVED` rows → a resolved dependency
  whose every offered licence is outside the approved list in
  `infra/ci/license-gate.sh`, or which arrived with no licence metadata. Either
  upgrade to a version under an approved licence, or make the call in that script and
  in the commit message. A missing licence field is treated as unapproved on purpose:
  that is what a repackaged artifact looks like.
* `security.yml → secrets` red with "gitleaks found the canary…" absent → the scan
  itself is broken, which is treated as a failure on purpose. See SECURITY.md.
* A run that is cancelled rather than failed → the same branch pushed again; the
  newer commit is the one that matters.
* `integration` red at `spring-boot:repackage` with `Failed to read artifact
  descriptor for io.micrometer:…` **after every test passed** → observed once, on a
  docs-only commit whose Java sources were identical to a green commit minutes
  earlier. Re-running the job succeeded in 60 s with the *same* Maven cache restored
  and no re-download, so it was a transient remote read, not a poisoned cache and not
  the code. Re-run once before investigating; if it repeats on two attempts, the cache
  or Central is the suspect.
