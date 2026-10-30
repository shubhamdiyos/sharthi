# Security

This file records what is actually protecting the repository today, what each
check refuses to do, and what is deliberately absent. It is written for the person
who has to decide whether to trust a green run.

## What runs

| Check | Tool, pinned | Where | What it can catch |
| --- | --- | --- | --- |
| Dependency vulnerabilities | `ghcr.io/google/osv-scanner:v2.0.2` over a CycloneDX SBOM produced by `org.cyclonedx:cyclonedx-maven-plugin:2.9.1` | `.github/workflows/security.yml → dependencies` | Any advisory in the OSV database against the **124 components the build actually resolved**, test scope included. |
| Dependency diff on a pull request | `actions/dependency-review-action@v4` | same job, PR events only | A dependency introduced or upgraded by *this* pull request that GitHub already knows to be vulnerable, or whose licence is not on the allow-list. |
| Secrets in history | `ghcr.io/gitleaks/gitleaks:v8.21.2`, `detect --source=/repo --redact` | `security.yml → secrets` | A credential committed in any commit reachable from the checked-out branch, including ones later deleted. |
| Secrets in the working tree | the same image with `--no-git` | same job | A secret that reached the tree through a merge and never appeared as an added line in a single commit — the gap the history scan leaves. |
| Scanner self-test | a synthetic GitHub PAT in `RUNNER_TEMP` | same job, first step | The scan silently reading nothing. See [Why the canary exists](#why-the-canary-exists). |
| Architecture rules | ArchUnit 1.4.1 (`MoneyArchitectureTest`, `ModuleBoundaryTest`) | `ci.yml → unit` | A module reaching into another's internals, `double`/`float` money, an unparseable cursor, a boundary crossed before it is drawn. |
| Configuration validity | Spring Boot's own binder, in `ApplicationContextIT` | `ci.yml → integration` | A property that cannot be bound, a bean that cannot start, a readiness group naming a health indicator that does not exist. |
| Database-enforced guarantees | Flyway 12.4.0 CLI + `psql` on `postgres:16` | `database-migrations.yml` | A `UPDATE`/`DELETE`/`TRUNCATE` on `audit_event` that the server allowed, an unclosed `job_key` list, an idempotency key not scoped by tenant, a `business_day` that is not generated. |
| Version updates | Dependabot (GitHub-side) | `.github/dependabot.yml` | Known-vulnerable dependency versions, without spending an Actions minute to look for them. |

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
environment of a workflow that runs on every push, including pushes from a fork's
pull request, where the token is read-only but the logs are not. The rule this
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
3. **`gh api /repos/shubhamdiyos/sharthi/actions/caches`** — should read one
   `setup-java-Linux-x64-maven-…` entry of tens of megabytes, not a growing pile per
   branch. Cache entries expire after 7 days of non-use, which is what keeps this
   self-cleaning.
4. **Dependabot alerts** — open, and if any has waited more than a release cycle,
   the reason belongs in this file, not in the alert.
5. **Secrets that exist at all** — a short list, each with an owner. Anything on the
   list nobody can name is a finding.

## Reporting a finding

Open an issue with the workflow run URL, the constraint or advisory id, and the
command from [CI-CD.md → Reproducing every check locally](CI-CD.md#reproducing-every-check-locally)
that reproduces it. Do not silence a check by editing a workflow's assertion to
match what the system now does — `database-migrations.yml` failing on run 1 is the
example: the server was right, the assertion was wrong, and the assertion moved.
