#!/usr/bin/env bash
# FX-CONFLICT — a content conflict on file.txt (change A rebased onto change B, working copy
# left on the conflicted change A), for manual conflict-resolution testing. See
# docs/manual-tests.md § Fixtures.
#
# The doc's own inline recipe for this fixture (a `jj rebase -r @- -d @` one-liner) was never
# actually run and turned out not to reproduce the documented topology: at that point in the
# sequence `@-` resolves to "initial", not "change A", which rebases "initial" itself onto
# "change B" and produces a modify/delete-style conflict — duplicating FX-MD-CONFLICT instead
# of testing a genuine 3-way content conflict. This script names both commits with bookmarks
# and rebases explicitly by name, then `jj edit`s onto the conflicted commit so the working
# copy actually lands there (verified: produces a clean "2-sided conflict", not
# "...including 1 deletion").
#
# Usage: scripts/fixtures/fx-conflict.sh [target-dir] [marker-style]
#   marker-style: git (default), snapshot, or diff — see `jj help config` for
#   ui.conflict-marker-style. Rerun with a different style against the same repo to
#   regenerate markers in that format (the script always applies the requested style
#   before creating the conflict).

set -euo pipefail

target="${1:-/tmp/jj-conflict-test}"
marker_style="${2:-git}"

rm -rf "$target"
mkdir -p "$target" && cd "$target"
jj git init
jj config set --repo ui.conflict-marker-style "$marker_style"

echo -e "line 1\nshared line\nline 3" >file.txt
jj describe -m "initial"
jj new -m "change A"
echo -e "line 1\nchanged by A\nline 3" >file.txt
jj bookmark create change-a -r @
jj new -r 'change-a-' -m "change B"
echo -e "line 1\nchanged by B\nline 3" >file.txt
jj bookmark create change-b -r @
jj rebase -r change-a -d change-b
jj edit change-a

if ! jj resolve --list | grep -qx 'file.txt.*2-sided conflict$'; then
  echo "WARNING: file.txt is not a plain 2-sided content conflict on this jj version —" >&2
  echo "inspect 'jj resolve --list' output below and adjust the sequence above." >&2
  jj resolve --list >&2
  exit 1
fi

echo "Conflict on file.txt created at $target (marker style: $marker_style)"
echo "Working copy is 'change A' (bookmark change-a), rebased onto 'change B' (bookmark change-b)"
echo "To test another marker style: jj config set --repo ui.conflict-marker-style <style> && jj rebase (or jj restore) to regenerate"
