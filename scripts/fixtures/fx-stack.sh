#!/usr/bin/env bash
# FX-STACK — a linear stack of four changes, for manual squash testing.
# See docs/manual-tests.md § Fixtures.
#
# Usage: scripts/fixtures/fx-stack.sh [target-dir]

set -euo pipefail

target="${1:-/tmp/jj-squash-test}"

rm -rf "$target"
mkdir -p "$target" && cd "$target"
jj git init
echo "base" >base.txt && jj describe -m "base"
jj new -m "change A" && echo "A content" >a.txt
jj new -m "change B" && echo "B content" >b.txt
jj new -m "change C" && echo "C content" >c.txt

echo "Linear stack base -> A -> B -> C (@ = C) created at $target"
