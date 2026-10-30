# Security

This file records what is actually protecting the repository today, what each
check refuses to do, and what is deliberately absent. It is written for the person
who has to decide whether to trust a green run.

## What runs

| Check | Tool, pinned | Where | What it can catch |
| --- | --- | --- | --- |
| Dependency vulnerabilities | `ghcr.io/google/osv-scanner:v2.0.2` over a CycloneDX SBOM produced by `org.cyclonedx:cyclonedx-maven-plugin:2.9.1` | `.github/workflows/security.yml → dependencies` | Any advisory in the OSV database against the **124 components the build actually resolved**, test scope included. |
| Dependency licences | `infra/ci/license-gate.sh` over the same SBOM, with `jq` | same job, every event | A resolved dependency whose every offered licence is outside the approved list — or which carries no licence metadata at all. Replaces `actions/dependency-review-action`, which cannot run on this repository (see [Not enabled](#not-enabled-and-on-what-grounds)). |
| Secrets in history | `ghcr.io/gitleaks/gitleaks:v8.21.2`, `detect --source=/repo --redact` | `security.yml → secrets` | A credential committed in any commit reachable from the checked-out branch, including ones later deleted. |
| Secrets in the working tree | the same image with `--no-git` | same job | A secret that reached the tree through a merge and never appeared as an added line in a single commit — the gap the history scan leaves. |
| Scanner self-test | a synthetic GitHub PAT in `RUNNER_TEMP` | same job, first step | The scan silently reading nothing. See [Why the canary exists](#why-the-canary-exists). |
| Architecture rules | ArchUnit, version declared in `services/api/pom.xml` (`MoneyArchitectureTest`, `ModuleBoundaryTest`) | `ci.yml → unit` | A module reaching into another's internals, `double`/`float` money, an unparseable cursor, a boundary crossed before it is drawn. |
| Configuration validity | Spring Boot's own binder, in `ApplicationContextIT` | `ci.yml → integration` | A property that cannot be bound, a bean that cannot start, a readiness group naming a health indicator that does not exist. |
| Database-enforced guarantees | the Flyway CLI pinned in `database-migrations.yml`, + `psql` on `postgres:16` | `database-migrations.yml` | A `UPDATE`/`DELETE`/`TRUNCATE` on `audit_event` that the server allowed, an unclosed `job_key` list, an idempotency key not scoped by tenant, a `business_day` that is not generated. |
| Version updates | Dependabot (GitHub-side) | `.github/dependabot.yml` | A dependency falling behind. Measured working: once the config reached the default branch, GitHub ran `Dependabot Updates` two minutes later and PR #4 (`archunit-junit5` 1.4.1 → 1.5.1) arrived with the full CI attached — which is how a bump gets the OSV and licence gates run against it. **Dependabot *alerts*** are a different feature and do not work here — observed, not assumed: the API answers 403 "Dependabot alerts are disabled for this repository". The toggle lives in Settings → Code security and analysis, which is a page this repository's token cannot read or write, and on a private GitHub Free repository the feature behind it is a paid one. Vulnerability detection is done by the OSV row above instead, which needs no GitHub feature. |

## Gates

`osv-scanner` has no severity threshold, so the threshold is in
`infra/ci/osv-gate.sh`, and it is deliberately not "fail if the scanner says
anything":

* **CRITICAL or HIGH → the build fails.** §2's gate list says *unapproved critical
  vulnerability*, and a gate that blocks on every MODERATE advisory gets muted by
  the second week it is inconvenient.
* MODERATE, LOW → printed as a warning annotation and the merge proceeds.
* **UNKNOWN → printed, never counted as clean.** An advisory GitHub has not rated is
  reported as an unrated finding rather than folded into "no issues".
* A report naming **0 packages fails the job.** `--all-packages` is passed to the
  scanner precisely so that "0 packages" can only mean "the lockfile was never
  parsed", which is a broken scan, not a clean one.
* gitleaks exits non-zero on any finding. The commit scan additionally requires
  **at least one commit scanned**, so "no leaks found" cannot be reported by a
  traversal that read nothing.
* **Licences: a component passes when any licence it *offers* is approved.** Every
  current dependency does — `logback` is EPL-2.0 *or* LGPL-2.1-only, `HdrHistogram`
  is CC0-1.0 *or* BSD-2-Clause, ArchUnit is Apache-2.0 *or* BSD-4-Clause, the Jakarta
  APIs are EPL-2.0 *or* GPL-2.0-with-classpath-exception — and the allow-list is
  narrower than the tree needs, which is what lets it be trusted to be red. An
  `AGPL-3.0-only` dependency, or one with no licence metadata, fails the build by
  name. The list lives in `infra/ci/license-gate.sh` so that widening it is a commit
  with a message, not a settings change nobody sees.
* **The licence gate covers Maven only.** `package-lock.json` carries no licence
  field, so the frontend's dependencies get the OSV advisory scan and not a licence
  verdict. Saying otherwise would be the false green this file keeps closing.

## Permissions, and what CI cannot reach

Every workflow declares `permissions: contents: read` at the top level, and no job
overrides it. Concretely, a CI run cannot push a commit, cannot label a pull
request, cannot publish a package, cannot create a release, and cannot approve a
deployment — because it was never granted the token to do any of those.

**No workflow references `secrets.*` at all.** That is checkable in one line:

```bash
grep -rn "secrets\." .github/workflows/ ; echo "exit=$? (1 = no match, which is the point)"
```

The values CI needs — a database password, a session secret, an IP-hash key — are
committed literals named `ci-ephemeral-not-a-secret`. They are not credentials,
because nothing they unlock survives the job: the PostgreSQL instance is a service
container created and destroyed inside one run on a machine nobody else sees. Making
CI read a real secret from repository secrets would put a production key in the
environment of a workflow that runs on every pull request, including one opened from a
fork's branch, where the token is read-only but the logs are not. The rule this
repository follows is the inverse: **CI gets non-values; the real ones stay
somewhere a deployment workflow can only reach them with an approved environment.**

## Where real secrets live

| | |
| --- | --- |
| Local development | `.env` at the repository root, gitignored (`.env`, `.env.*`), created from `.env.example`. It is never read by a workflow file. |
| Container runtime | Compose passes `SAATHI_*` and `POSTGRES_*` into the api container as environment variables. The application reads them through Spring's property source, and `application.yml` supplies a `config.import` of `.env` only for the test profile. |
| Staging and production | Not built yet. When they are: GitHub Actions **environments**, each holding encrypted secrets, each reachable only by a workflow run the founder approved. A long-lived cloud access key is not part of the plan — OIDC is, so that no cloud credential exists to leak. |
| Never | Source code, Docker images, workflow `env:`, test fixtures, logs, artifacts, this file. |

Two specific things `ApiAdvice` guarantees about the last row: an unexpected
exception is logged with its type and the request id but **not its message**,
because the message of a JDBC failure is a JDBC URL with a password in it — the
probe test throws exactly such a message and asserts that `hunter2`, `jdbc`,
`postgresql` and `postgres` all fail to appear in the response body. Error responses
carry `request_id` and nothing else about the server's internals.

## Known exposure, outstanding

A third-party design-tool API key (Stitch) was exposed in a local agent
configuration outside this repository. It was never committed here, and it is not
reproduced in this file. **Rotation is the founder's action and is still owed**;
until it happens, that key should be treated as public by anyone who has read this
line. It is listed rather than quietly handled because an unrotated credential is
the one finding a scanner cannot fix.

## Why the canary exists

"no leaks found" is the same sentence, on the same terminal, whether gitleaks read
every commit or failed to start. The first step of the secrets job therefore writes
a synthetic GitHub token into a throwaway repository under `RUNNER_TEMP`, scans it,
and **fails the job if gitleaks calls it clean** — and fails it a second way if
gitleaks produced no leak report at all, which is how a missing Docker daemon or a
blocked image pull would otherwise look like a pass.

It is a test of the *toolchain*, not of this repository, which is why it comes
first: the value of every later "no leaks found" is exactly the value of that step.

Two facts learned while building it, both of which would have made a weaker version
of this step lie:

* AWS's own documentation key, `AKIAIOSFODNN7EXAMPLE`, is **not** detected by
  gitleaks' default rules. A canary built on it would have failed permanently, and
  the tempting fix would have been to weaken the step rather than the value.
* Comparing "commits scanned" against `git rev-list --count HEAD` does not work:
  gitleaks walks commits that carry a patch, so on this branch's history it reported
  4 of 8. That is correct behaviour, and an equality assertion would have been a
  false failure — hence a canary plus a non-zero floor instead of arithmetic.

## Not enabled, and on what grounds

| Absent | Why |
| --- | --- |
| **CodeQL / `gitleaks/gitleaks-action`** | On a private repository both are GitHub Advanced Security features, and GHAS is licensed per committer. The mandate is zero unexpected cost, so the equivalents are the tools themselves, run from pinned images. If GHAS is bought, these replace the two jobs above. |
| **`actions/dependency-review-action`** | Tried first, on the assumption that a native action is cheaper than a script. On a pull request it failed with *"Dependency review is not supported on this repository. Please ensure that Dependency graph is enabled along with GitHub Advanced Security"* — the same paywall, in the same sentence. Its licence half is now `infra/ci/license-gate.sh`; its vulnerability half is subsumed, because osv-scanner reads the whole resolved tree rather than only the PR's diff, and runs on pushes as well as pull requests. |
| **Required status checks on `main`** | Attempted through the API with the four real check names; GitHub Free returns **403 "Upgrade to GitHub Pro or make this repository public to enable this feature."** So nothing currently *prevents* a merge with red checks — see [CI-CD.md → The cycle](CI-CD.md#the-cycle-as-actually-executed) for what enforces it instead, and why the answer is a plan decision rather than a script. |
| **Container image scanning (Trivy)** | There is no image. GHCR publishing is not approved, and building an image in CI only to scan it spends minutes and storage on an artifact nothing runs. Trivy belongs in `release.yml`, scanning the image that is about to be pushed. |
| **SpotBugs / Checkstyle / PMD** | Not declared in `services/api/pom.xml`, so this file does not claim them. "Static analysis" here means the compiler, ArchUnit, and Spring's binder. SpotBugs costs roughly a minute per run and free cache, so the reason it is absent is not money: on a kernel this size it reports mostly `EI_EXPOSE_REP` on value types, and an exclusion policy is a decision about which findings the team intends to read. Decide that, then add it in one place. |
| **Secret-scanning push protection** | A repository-settings change; it is on GitHub's side, not in a file here, and turning on account-level security features is exactly the class of change this project asks permission for first. |
| **Snyk / SonaType / cloud WAF / DAST** | Paid third-party services. Not enabled, not planned without approval. |
| **Dependabot on branches other than `main`** | Available; not configured, because every extra PR is CI minutes and feature branches are short-lived here. |

## What a reviewer should check monthly

GitHub does not expose these numbers through the repository API, and this file
declines to invent them:

1. **Settings → Billing and plans → GitHub Actions** — minutes used this month, and
   the spending limit. The limit is $0 on this account; if it has changed, that is a
   decision to revisit, not a setting to leave.
2. **Storage used by artifacts and packages**, against the 500 MB pooled allowance.
   This pipeline uploaded 0 artifacts across its first run, and no packages exist.
3. **`gh api /repos/shubhamdiyos/sharthi/actions/caches`** — measured at the end of
   30 October: **8 entries, 673 MB** — five under the `setup-java-Linux-x64-maven-…`
   keys the Java jobs share (323 MB), and three of ~115 MB under the
   `node-cache-Linux-x64-npm-…` key for `apps/web` (345 MB). The npm copies accumulate
   one per commit whose `push` and `pull_request` web jobs race on a cold key; that is
   the number to watch, not the total. The ceiling is a separate 10 GB per repository,
   and entries expire after 7 days of non-use, so a growing pile is bounded — but the
   npm key reaching *dozens* means caching it costs more storage than it saves minutes,
   and that is worth checking again rather than assuming fixed.
4. **Dependabot** — is the feature switched on in Settings → Code security and analysis
   at all, and if so are any alerts older than a release cycle? Today the API says the
   alerts are disabled, so this month's honest check is the other way round: confirm
   that `security.yml`'s OSV gate is green, because it is the only dependency-risk check
   that actually runs.
5. **Secrets that exist at all** — a short list, each with an owner. Anything on the
   list nobody can name is a finding.
6. **The two Flyway pins agree.** `services/api/pom.xml` resolves Flyway from the Spring
   Boot BOM; `.github/workflows/database-migrations.yml` pins `FLYWAY_IMAGE` by hand to
   match. A Dependabot bump of the Boot line moves the first and not the second, and the
   migration job would then be validating the schema against rules the deployed
   application does not run. Compare them whenever a `spring-boot` group PR lands.

## Reporting a finding

Open an issue with the workflow run URL, the constraint or advisory id, and the
command from [CI-CD.md → Reproducing every check locally](CI-CD.md#reproducing-every-check-locally)
that reproduces it. Do not silence a check by editing a workflow's assertion to
match what the system now does — `database-migrations.yml` failing on run 1 is the
example: the server was right, the assertion was wrong, and the assertion moved.
