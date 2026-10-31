#!/usr/bin/env bash
# FX-AUTOFETCH — a repo with two Git remotes (origin, upstream) plus a second clone to push
# from, for manual testing of automatic fetch and the last-fetched indicators.
# See docs/manual-tests.md § Fixtures and MT-GIT "Automatic fetch".
#
# Usage:
#   scripts/fixtures/fx-autofetch.sh [target-dir]                  build the fixture (default /tmp/jj-autofetch-test)
#   scripts/fixtures/fx-autofetch.sh push [target-dir] [remote]    push a new commit from the other clone
#                                                                  (remote: origin (default) or upstream)
#   scripts/fixtures/fx-autofetch.sh break [target-dir]            point origin at a missing path (fetch fails)
#   scripts/fixtures/fx-autofetch.sh fix [target-dir]              restore origin
#
# Open <target-dir> in the sandbox IDE. The other clone is <target-dir>-other, the bare remotes are
# <target-dir>-remotes/{origin,upstream}.git.

set -euo pipefail

cmd=build
case "${1:-}" in
    push | break | fix) cmd="$1"; shift ;;
esac
target="${1:-/tmp/jj-autofetch-test}"
remotes="$target-remotes"
other="$target-other"

case "$cmd" in
    build)
        rm -rf "$target" "$remotes" "$other"
        mkdir -p "$remotes" "$other"
        git init -q --bare "$remotes/origin.git"
        git init -q --bare "$remotes/upstream.git"

        cd "$other"
        jj git init
        jj git remote add origin "$remotes/origin.git"
        jj git remote add upstream "$remotes/upstream.git"
        echo "base" >f.txt && jj commit -m "base"
        jj bookmark create main -r @-
        jj bookmark create upstream-main -r @-
        jj git push --remote origin --bookmark main
        jj git push --remote upstream --bookmark upstream-main

        jj git clone "$remotes/origin.git" "$target"
        cd "$target"
        jj git remote add upstream "$remotes/upstream.git"
        jj git fetch --all-remotes
        echo "Built $target (remotes origin + upstream). Push more with: $0 push $target [origin|upstream]"
        ;;
    push)
        remote="${2:-origin}"
        bookmark=main
        [ "$remote" = upstream ] && bookmark=upstream-main
        cd "$other"
        n=$(date +%H%M%S)
        echo "$n" >"f-$remote.txt" && jj commit -m "push $n to $remote"
        jj bookmark set "$bookmark" -r @-
        jj git push --remote "$remote" --bookmark "$bookmark"
        ;;
    break)
        cd "$target" && jj git remote set-url origin "$remotes/missing.git"
        echo "origin now points at a missing path; fetches will fail"
        ;;
    fix)
        cd "$target" && jj git remote set-url origin "$remotes/origin.git"
        echo "origin restored"
        ;;
esac
