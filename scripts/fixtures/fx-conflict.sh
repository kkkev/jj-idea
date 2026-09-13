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
# Usage: scripts/fixtures/fx-conflict.sh [target-dir] [marker-style] [wc-position]
#   marker-style: git (default), snapshot, or diff — see `jj help config` for
#   ui.conflict-marker-style. Rerun with a different style against the same repo to
#   regenerate markers in that format (the script always applies the requested style
#   before creating the conflict).
#   wc-position: conflicted (default) leaves @ on the conflicted commit itself (change-a);
#   sibling leaves @ on change-b instead — a clean commit unrelated to the conflict, which is
#   the position GitHub #119 / jj-idea-ct7e reproduces from (browsing the log to a conflicted
#   commit that isn't @ or one of its descendants).

set -euo pipefail

target="${1:-/tmp/jj-conflict-test}"
marker_style="${2:-git}"
wc_position="${3:-conflicted}"

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

case "$wc_position" in
  conflicted) jj edit change-a ;;
  sibling) jj edit change-b ;;
  *)
    echo "Unknown wc-position '$wc_position' (expected 'conflicted' or 'sibling')" >&2
    exit 1
    ;;
esac

if ! jj resolve --list -r change-a | grep -qx 'file.txt.*2-sided conflict$'; then
  echo "WARNING: file.txt is not a plain 2-sided content conflict on this jj version —" >&2
  echo "inspect 'jj resolve --list -r change-a' output below and adjust the sequence above." >&2
  jj resolve --list -r change-a >&2
  exit 1
fi

echo "Conflict on file.txt created at $target (marker style: $marker_style)"
echo "'change A' (bookmark change-a) is rebased onto 'change B' (bookmark change-b) and conflicted"
if [ "$wc_position" = sibling ]; then
  echo "Working copy is 'change B' (bookmark change-b) — a clean sibling of the conflicted commit"
else
  echo "Working copy is 'change A' (bookmark change-a) — the conflicted commit itself"
fi
echo "To test another marker style: jj config set --repo ui.conflict-marker-style <style> && jj rebase (or jj restore) to regenerate"
