#!/usr/bin/env bash
# The test of ./check's own fetch of ktlint: a download whose sha256 is not the pinned one stops
# the script with an error and leaves nothing behind. Run by ./check ktlint; needs no network.
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

printf '#!/bin/sh\necho tampered\n' >"$scratch/tampered"
status=0
out=$(KTLINT_URL="file://$scratch/tampered" KTLINT_DIR="$scratch/cache" "$root/check" --fetch-ktlint 2>&1) || status=$?

fail() {
    echo "check.test.sh: $1"
    echo "$out" | sed 's/^/  /'
    exit 1
}
[ "$status" -ne 0 ] || fail "a tampered ktlint was accepted"
grep -q "FAILED: ktlint: .* not the pinned" <<<"$out" || fail "the refusal does not say why"
[ -z "$(ls -A "$scratch/cache")" ] || fail "the tampered download was left in the cache: $(ls -A "$scratch/cache")"
echo "check.test.sh: a tampered ktlint is refused and nothing is kept"
