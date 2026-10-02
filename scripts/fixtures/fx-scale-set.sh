#!/usr/bin/env bash
# FX-SCALE-SET — a ladder of repo sizes for tuning large-repo log behaviour (jj-idea-2570.x,
# GitHub #69): paged loading, the idle trickle, first-page size, prefetch distance. See
# docs/manual-tests.md § Fixtures.
#
# Builds (each skipped if it already has a .jj, so re-running only fills gaps):
#
#   s1k-synthetic    ~1,000 commits   fx-stress.sh SCALE=1            many short branches, no real code
#   s6k-synthetic    ~6,000 commits   fx-stress.sh SCALE=6 +remote    the shape/size #69's reporter described
#   r2k-openNDS      ~2,000 commits   real C project (openNDS)
#   r6k-longhorn     ~6,500 commits   real Go project (longhorn-manager) - #69-sized, real code
#   r36k-keycloak    ~36,000 commits  real Java/Maven project - IDE does Maven import/indexing on open
#   r80k-git         ~80,000 commits  real C project (git/git) - well past the 10,000-row trickle cap
#
# Real projects are cloned with `jj git clone --colocate` from a local checkout under
# $LOCAL_SRC_BASE (default ~/workspace) when one exists - far faster than the network - else from
# GitHub. The local checkout is only read, never modified.
#
# Usage: scripts/fixtures/fx-scale-set.sh [target-dir]      (default ~/workspace/jj-scale)
# Env:   ONLY="r6k-longhorn r36k-keycloak"   build just these
#        LOCAL_SRC_BASE=/path                where to look for local checkouts
# Takes a while (the synthetic 7k repo is one `jj new` per commit; r80k-git is a big clone).
#
# To reproduce #69's reporter (cYDN48): open a fixture in the sandbox IDE with Settings -> Version
# Control -> Jujutsu -> Log -> "Changes to show" = 10000 and the paged-log preview enabled.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
target="${1:-$HOME/workspace/jj-scale}"
only="${ONLY:-}"
local_base="${LOCAL_SRC_BASE:-$HOME/workspace}"

mkdir -p "$target"

want() { [[ -z "$only" || " $only " == *" $1 "* ]]; }
built() { [[ -d "$target/$1/.jj" ]]; }

synthetic() {
  local name="$1"
  shift
  want "$name" || return 0
  if built "$name"; then echo "== $name: already built, skipping"; return 0; fi
  echo "== $name: building synthetic fixture ($*)"
  env "$@" "$here/fx-stress.sh" "$target/$name"
}

real() {
  local name="$1" url="$2" local_dir="$3"
  want "$name" || return 0
  if built "$name"; then echo "== $name: already built, skipping"; return 0; fi
  local src="$url"
  if [[ -n "$local_dir" && ( -d "$local_base/$local_dir/.git" || -d "$local_base/$local_dir/.jj" ) ]]; then
    src="$local_base/$local_dir"
  fi
  echo "== $name: cloning from $src"
  rm -rf "${target:?}/$name"
  jj git clone --colocate "$src" "$target/$name"
}

synthetic s1k-synthetic SCALE=1
synthetic s6k-synthetic SCALE=6 WITH_REMOTE=1
real r2k-openNDS https://github.com/openNDS/openNDS.git openNDS
real r6k-longhorn https://github.com/longhorn/longhorn-manager.git longhorn-manager
real r36k-keycloak https://github.com/keycloak/keycloak.git keycloak
real r80k-git https://github.com/git/git.git ""

echo
echo "== summary ($target)"
for d in "$target"/*/; do
  name="$(basename "$d")"
  [[ -d "$d/.jj" ]] || continue
  commits="$(cd "$d" && jj log -r 'all()' --no-graph --ignore-working-copy -T '"x\n"' 2>/dev/null | wc -l | tr -d ' ')"
  heads="$(cd "$d" && jj log -r 'heads(all())' --no-graph --ignore-working-copy -T '"x\n"' 2>/dev/null | wc -l | tr -d ' ')"
  printf '%-18s commits=%-7s heads=%s\n' "$name" "$commits" "$heads"
done
