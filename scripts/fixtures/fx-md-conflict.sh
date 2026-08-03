#!/usr/bin/env bash
# FX-MD-CONFLICT — a modify/delete conflict on a.txt: one side deleted the file entirely, so
# "accept" on that side must remove it from disk, not leave an empty file. A different case
# from FX-CONFLICT's content conflict, which resolves to a merged text file either way.
# See docs/manual-tests.md § Fixtures.
#
# The doc's own inline recipe used `jj new @ @-- -m "merge"`, but `@--` resolves to the root
# commit at that point (not the intended "add a.txt"), which the Git backend refuses as a
# merge parent. It also never repositioned the working copy onto the conflicted commit after
# rebasing. Both fixed below (verified): `@-` is the correct reference, and a `jj edit` lands
# the working copy on "side A: modify" once it's conflicted.
#
# Usage: scripts/fixtures/fx-md-conflict.sh [target-dir]
#
# If the exact revset below doesn't land a clean modify/delete conflict on a.txt on your jj
# version, any sequence that rebases a modify onto a delete of the same file works — the goal
# is a working copy where `jj resolve --list` reports a.txt as a "2-sided conflict including 1
# deletion". The script checks this at the end and fails loudly if it didn't land.

set -euo pipefail

target="${1:-/tmp/jj-md-conflict-test}"

rm -rf "$target"
mkdir -p "$target" && cd "$target"
jj git init
echo v1 >a.txt && jj describe -m "add a.txt"
jj new -m "side A: modify"
echo v2-A >a.txt
jj bookmark create side-a -r @
jj new -r @- -m "side B: delete"
rm a.txt
jj new @ @- -m "merge" # merges "side B: delete" with the parent of "side A: modify" (add a.txt)
jj rebase -r side-a -d @
jj edit side-a

if ! jj resolve --list | grep -q "2-sided conflict including 1 deletion"; then
  echo "WARNING: a.txt does not show as a 2-sided conflict including 1 deletion on this jj" >&2
  echo "version — inspect 'jj resolve --list' output and adjust the revset above." >&2
  jj resolve --list >&2
  exit 1
fi

echo "Modify/delete conflict on a.txt created at $target"
