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
# SBOM: 124 components, every one of them carrying licence metadata. So this is a jq
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
# Usage: infra/ci/license-gate.sh <cyclonedx bom.json>
set -eu

bom=${1:?usage: infra/ci/license-gate.sh <cyclonedx bom.json>}
[ -s "$bom" ] || { echo "no SBOM at $bom: the CycloneDX step produced nothing"; exit 1; }

allowed='Apache-2.0 BSD-2-Clause BSD-3-Clause MIT EPL-2.0 LGPL-2.1-only CDDL-1.1'

components=$(jq '.components | length' "$bom")
if [ "${components:-0}" -eq 0 ]; then
    echo "the SBOM lists 0 components: nothing was resolved, so nothing was checked"
    exit 1
fi

offenders=$(
    jq -r --arg allowed "$allowed" '
        ($allowed | split(" ")) as $ok
        | .components[]
        | ((.licenses // []) | map(.license.id // .expression // .license.name // "?")) as $offered
        | select(($offered | map(select(. as $l | $ok | index($l)))) | length == 0)
        | "\(.group + ":" + .name)@\(.version)\t\(if ($offered | length) == 0 then "<none in metadata>" else ($offered | join(" or ")) end)"
    ' "$bom" | sort
)

# Counted from the list above rather than written twice, so the log line cannot drift
# from the policy it is reporting.
approved=$(printf '%s' "$allowed" | tr ' ' '\n' | grep -c .)
echo "licence gate: $components component(s) read against $approved approved licence(s)"

# The distribution is printed even when it passes. A reviewer asking "what licences do
# we actually ship?" should get an answer from the log, not from a second run.
jq -r '
    [ .components[] | (.licenses // [])[] | .license.id // empty ]
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
