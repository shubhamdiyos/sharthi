#!/bin/sh
# Turn an osv-scanner JSON report into the merge gate §2 asks for.
#
# osv-scanner has no severity threshold, so the threshold lives here: a CRITICAL or
# HIGH advisory fails the build, anything else is reported and the merge proceeds.
# Three things this refuses to do is pretend about:
#   * an advisory GitHub has not rated is counted as UNKNOWN and printed, never
#     folded into "clean" — treating unrated as harmless is how a scanner becomes
#     theatre;
#   * a report that names no packages at all is a scan that read nothing, so it
#     fails rather than passing vacuously. The scan step therefore runs with
#     --all-packages, which is what makes "packages: 0" mean "nothing was parsed"
#     rather than "nothing looked wrong";
#   * a missing or empty report file is an error in the gate, not a green check.
#
# Usage: infra/ci/osv-gate.sh <osv-scanner json report>
set -eu

report=${1:?usage: infra/ci/osv-gate.sh <osv-scanner json report>}
[ -s "$report" ] || { echo "no scan report at $report: the scan step produced nothing"; exit 1; }

packages=$(jq '[.results[]?.packages[]?] | length' "$report")
if [ "$packages" -eq 0 ]; then
    echo "the report contains 0 packages: the lockfile was never parsed"
    exit 1
fi

findings=$(jq -r '
    [ .results[]?.packages[]? as $p
      | (($p.vulnerabilities // []))[]? as $v
      | { sev: ((($v.database_specific.severity // "UNKNOWN") | ascii_upcase)),
          id: $v.id,
          pkg: "\($p.package.name)@\($p.package.version)",
          summary: ($v.summary // "") } ]
    | .[]
    | "\(.sev)\t\(.id)\t\(.pkg)\t\(.summary)"
' "$report" | sort)

blocking=$(printf '%s\n' "$findings" | awk -F'\t' '$1 == "CRITICAL" || $1 == "HIGH"' | grep -c . || true)
notices=$(printf '%s\n' "$findings" | awk -F'\t' '$1 != "CRITICAL" && $1 != "HIGH"' | grep -c . || true)

echo "osv-scanner: $packages package(s) read, $((blocking + notices)) finding(s)"
[ -n "$findings" ] && printf '%s\n' "$findings" | sed 's/^/  /'

if [ "$blocking" -gt 0 ]; then
    echo "::error::osv-scanner blocked $blocking CRITICAL/HIGH advisory(ies) — §2's gate is an unapproved critical vulnerability"
    exit 1
fi
if [ "$notices" -gt 0 ]; then
    echo "::warning::osv-scanner reports $notices lower-severity advisory(ies); not blocking, but not nothing"
fi
