#!/usr/bin/env bash
# FX-STRESS — a repo with many concurrent branches, for log-graph/filter stress testing
# (jj-idea-1ojh, jj-idea-5i6i, jj-idea-7jkr, jj-idea-hlu3). See docs/manual-tests.md §
# Fixtures. Reused as `jj-stress-test` by MT-LOG-GRAPH's stress-test bullet, its "Graph
# layout under filtering" subsection, and MT-LOG-FILTER's Reference filter fixture.
#
# This repo was originally built by hand, not scripted, so the numbers below are a
# reconstruction of the documented shape (main trunk; feature/1..20; longbranch/1..5; a
# ~200-commit deep-branch off an early ancestor; an octopus-merge; a hotfix/* cluster) sized
# to land near the previously-recorded 1084 commits / ~26 concurrent heads — not a byte-exact
# replay of the original history. Tune the constants below if a test needs an exact count.
#
# Also creates release-1.0/release-2.0 bookmarks and a v1.0 tag deep in main's history (for
# MT-LOG-FILTER's Reference filter fixture, which needs a bookmark and a tag beyond the log
# limit) and configures main's ancestry as immutable (via revset-aliases."immutable_heads()",
# since a bare bookmark named "main" isn't trunk()/immutable by default without a git remote).
#
# Usage: scripts/fixtures/fx-stress.sh [target-dir]
# Takes a minute or so — this creates on the order of a thousand commits.

set -euo pipefail

target="${1:-/tmp/jj-stress-test}"

MAIN_LEN=638
FEATURE_COUNT=20
FEATURE_LEN=5
LONGBRANCH_COUNT=5
LONGBRANCH_LEN=27
DEEP_BRANCH_FORK_INDEX=50
DEEP_BRANCH_LEN=200
HOTFIX_COUNT=5
HOTFIX_LEN=2
OCTOPUS_PARENT_COUNT=6
RELEASE_1_INDEX=20
RELEASE_2_INDEX=40

change_id() {
  jj log -r @ --no-graph --color=never -T 'change_id.short(12)'
}

rm -rf "$target"
mkdir -p "$target" && cd "$target"
jj git init

declare -A fork_id

# Spread feature-branch and longbranch fork points evenly across main; keep the deep-branch
# fork point distinct so it doesn't collide with either.
feature_stride=$((MAIN_LEN / (FEATURE_COUNT + 1)))
longbranch_stride=$((MAIN_LEN / (LONGBRANCH_COUNT + 1)))

echo "Building main trunk ($MAIN_LEN commits)..."
for ((i = 1; i <= MAIN_LEN; i++)); do
  jj new -m "main $i" >/dev/null
  echo "line $i" >>trunk.txt
  if ((i == DEEP_BRANCH_FORK_INDEX)); then
    fork_id[deep]=$(change_id)
  fi
  for ((n = 1; n <= FEATURE_COUNT; n++)); do
    if ((i == feature_stride * n)); then fork_id[feature_$n]=$(change_id); fi
  done
  for ((n = 1; n <= LONGBRANCH_COUNT; n++)); do
    if ((i == longbranch_stride * n)); then fork_id[longbranch_$n]=$(change_id); fi
  done
  if ((i == MAIN_LEN - HOTFIX_COUNT)); then fork_id[hotfix_base]=$(change_id); fi
  if ((i == RELEASE_1_INDEX)); then fork_id[release_1]=$(change_id); fi
  if ((i == RELEASE_2_INDEX)); then fork_id[release_2]=$(change_id); fi
done
main_tip=$(change_id)
jj bookmark create main -r "$main_tip"
jj bookmark create release-1.0 -r "${fork_id[release_1]}"
jj bookmark create release-2.0 -r "${fork_id[release_2]}"
jj tag set v1.0 -r "${fork_id[release_1]}"
jj config set --repo 'revset-aliases."immutable_heads()"' 'builtin_immutable_heads() | main'

echo "Building $FEATURE_COUNT short feature branches ($FEATURE_LEN commits each)..."
feature_tips=()
for ((n = 1; n <= FEATURE_COUNT; n++)); do
  jj new "${fork_id[feature_$n]}" -m "feature/$n commit 1" >/dev/null
  for ((c = 2; c <= FEATURE_LEN; c++)); do
    jj new -m "feature/$n commit $c" >/dev/null
  done
  tip=$(change_id)
  jj bookmark create "feature/$n" -r "$tip"
  feature_tips+=("$tip")
done

echo "Building $LONGBRANCH_COUNT long branches ($LONGBRANCH_LEN commits each)..."
for ((n = 1; n <= LONGBRANCH_COUNT; n++)); do
  jj new "${fork_id[longbranch_$n]}" -m "longbranch/$n commit 1" >/dev/null
  for ((c = 2; c <= LONGBRANCH_LEN; c++)); do
    jj new -m "longbranch/$n commit $c" >/dev/null
  done
  jj bookmark create "longbranch/$n" -r "$(change_id)"
done

echo "Building deep-branch ($DEEP_BRANCH_LEN commits off main commit $DEEP_BRANCH_FORK_INDEX)..."
jj new "${fork_id[deep]}" -m "deep-branch commit 1" >/dev/null
for ((c = 2; c <= DEEP_BRANCH_LEN; c++)); do
  jj new -m "deep-branch commit $c" >/dev/null
done
jj bookmark create deep-branch -r "$(change_id)"

echo "Building octopus-merge (merging $OCTOPUS_PARENT_COUNT feature tips)..."
jj new "${feature_tips[@]:0:$OCTOPUS_PARENT_COUNT}" -m "octopus-merge" >/dev/null
jj bookmark create octopus-merge -r "$(change_id)"

echo "Building hotfix/* cluster ($HOTFIX_COUNT branches, $HOTFIX_LEN commits each)..."
for ((n = 1; n <= HOTFIX_COUNT; n++)); do
  jj new "${fork_id[hotfix_base]}" -m "hotfix/$n commit 1" >/dev/null
  for ((c = 2; c <= HOTFIX_LEN; c++)); do
    jj new -m "hotfix/$n commit $c" >/dev/null
  done
  jj bookmark create "hotfix/$n" -r "$(change_id)"
done

jj new "$main_tip" >/dev/null

total_commits=$(jj log -r 'all()' --no-graph -T 'commit_id ++ "\n"' | wc -l | tr -d ' ')
total_heads=$(jj log -r 'heads(all())' --no-graph -T 'commit_id ++ "\n"' | wc -l | tr -d ' ')

echo
echo "Stress-test repo created at $target"
echo "$total_commits commits, $total_heads concurrent heads"
echo "release-1.0/v1.0 and release-2.0 sit deep in main's history; main's ancestry is immutable"
echo "Working copy is a fresh empty commit on top of main."
