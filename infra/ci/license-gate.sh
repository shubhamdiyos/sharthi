#!/bin/sh
# Fail a build whose dependency tree carries a licence this project has not approved.
#
# This step exists because `actions/dependency-review-action` cannot run here at all.
# Its first attempt on a pull request failed with "Dependency review is not supported
# on this repository. Please ensure that Dependency graph is enabled along with GitHub
# Advanced Security" — on a private repository under the free plan that is a paid
# licence, so the mandate's two rules (zero unexpected cost, no billable feature
# without approval) both say the same thing: do not buy the feature, write the check.
#
# The data the action would have read is already in this repository's own CycloneDX
# SBOM: 126 components, every one of them carrying licence metadata. So this is a jq
# filter, not a GitHub feature, and it adds about a second to a job that already built
# the SBOM for the osv-scanner step above.
#
# Dual licensing is why this is not a blacklist. `jakarta.annotation-api` offers
# EPL-2.0 *or* GPL-2.0-with-classpath-exception, `HdrHistogram` offers CC0-1.0 *or*
# BSD-2-Clause, ArchUnit offers Apache-2.0 *or* BSD-4-Clause. The project takes the
# permissive option, so a component passes when *any* licence it offers is allowed.
# What has to fail is a dependency that offers only something outside the list, and a
# dependency with no licence metadata — an unknown licence is not an approved one, and
# a missing licence is what a repackaged artifact looks like.
#
# The list is deliberately closed and narrower than the tree needs: the whole current
# set passes through it today, which is the only reason it can be trusted to be red
# tomorrow. Adding an entry is a decision made in print, not a gate that widens itself
# to match whatever arrived.
#
# It has been widened once, and that addition is the pattern: `Bouncy Castle Licence`,
# the KDF that §12.2's Argon2id passwords run on, pulled in by spring-security-crypto.
# Its pom names the licence and links https://www.bouncycastle.org/licence.html, whose
# text is the MIT permission notice under a different copyright holder — the page says
# so itself ("should be read in the same way as the MIT license"). SPDX has no
# identifier for it (checked against the 740-entry licence list), so CycloneDX can only
# report `.license.name`, and the only way to approve it is to approve that string.
# The alternative was not "a permitted licence": it was dropping Argon2id or hand-porting
# the KDF, both of which §12.2 forbids.
#
# One licence per line, because the name above has spaces in it and a
# space-separated list would have matched "Bouncy", "Castle" and "Licence" as three
# entries — and a gate that splits its own policy is a gate that approves anything.
#
# Usage: infra/ci/license-gate.sh <cyclonedx bom.json>
set -eu

bom=${1:?usage: infra/ci/license-gate.sh <cyclonedx bom.json>}
[ -s "$bom" ] || { echo "no SBOM at $bom: the CycloneDX step produced nothing"; exit 1; }

allowed='Apache-2.0
BSD-2-Clause
BSD-3-Clause
MIT
EPL-2.0
LGPL-2.1-only
CDDL-1.1
Bouncy Castle Licence'

components=$(jq '.components | length' "$bom")
if [ "${components:-0}" -eq 0 ]; then
    echo "the SBOM lists 0 components: nothing was resolved, so nothing was checked"
    exit 1
fi

offenders=$(
    jq -r --arg allowed "$allowed" '
        ($allowed | split("\n") | map(select(length > 0))) as $ok
        | .components[]
        | ((.licenses // []) | map(.license.id // .expression // .license.name // "?")) as $offered
        | select(($offered | map(select(. as $l | $ok | index($l)))) | length == 0)
        | "\(.group + ":" + .name)@\(.version)\t\(if ($offered | length) == 0 then "<none in metadata>" else ($offered | join(" or ")) end)"
    ' "$bom" | sort
)

# Counted from the list above rather than written twice, so the log line cannot drift
# from the policy it is reporting.
approved=$(printf '%s\n' "$allowed" | grep -c .)
echo "licence gate: $components component(s) read against $approved approved licence(s)"

# The distribution is printed even when it passes. A reviewer asking "what licences do
# we actually ship?" should get an answer from the log, not from a second run.
# `.license.id` only, so a pom-named licence is absent from this histogram but still
# checked above — the histogram answers "what do we ship", the gate answers "is it ok".
jq -r '
    [ .components[] | (.licenses // [])[] | .license.id // .license.name // empty ]
    | group_by(.)
    | map({ id: .[0], count: length })
    | sort_by(-.count)
    | .[]
    | "  \(.count) × \(.id)"
' "$bom"

if [ -n "$offenders" ]; then
    count=$(printf '%s\n' "$offenders" | grep -c .)
    printf '%s\n' "$offenders" | sed 's/^/  UNAPPROVED  /'
    echo "::error::licence gate blocked $count dependency(ies) outside the approved licence list — see infra/ci/license-gate.sh"
    exit 1
fi
