#!/usr/bin/env bash
# `jj verify [REV] [--wait]`: run the pre-push gate (scripts/pre-push.sh) on REV's content in a
# separate jj workspace, in the background, so editing and other builds can carry on. On
# completion it records a pass/fail stamp for the revision's tree, which `jj ship` checks, and
# raises a macOS notification. REV defaults to @ (or @- if @ is empty).
#
#   jj config set --repo aliases.verify '["util", "exec", "--", "scripts/verify.sh"]'
#
# The gate runs in $JJ_VERIFY_DIR (default: ../<this checkout>-verify), created on first use, as a
# jj workspace named verify-<this workspace's name> (e.g. verify-lane2). Workspace names are
# shared by every checkout of the repo, so each lane needs its own verify workspace name. It never
# holds your edits, only a copy of REV. The workspace is registered only while a run is in
# progress: afterwards it is forgotten and its "verify scratch" change abandoned, so the log stays
# clean, while the directory is kept so the next run reuses its build caches. Don't start
# its first run at the same moment as the first Gradle build in another workspace: both
# initialise the shared IntelliJ platform layout index and can truncate it.
set -euo pipefail
cd "${JJ_WORKSPACE_ROOT:-$(dirname "$0")/..}"
# shellcheck source=scripts/verify-lib.sh
source scripts/verify-lib.sh

WAIT=false
REV=
for arg in "$@"; do
    case "$arg" in
        --wait) WAIT=true ;;
        *) REV=$arg ;;
    esac
done
if [ -z "$REV" ]; then
    if [ "$(jj log -r @ --no-graph -T 'empty')" = "true" ]; then REV='@-'; else REV='@'; fi
fi

TREE=$(tree_of "$REV")
LOG="$STAMP_DIR/$TREE.log"
case "$(state_of "$TREE")" in
    pass) echo "Already verified: tree ${TREE:0:12} (log: $LOG)"; exit 0 ;;
    running) echo "Verification already running for tree ${TREE:0:12} (log: $LOG)"; exit 0 ;;
    fail) echo "Previously FAILED for tree ${TREE:0:12}; re-running (old log kept as $LOG.prev)"
        mv -f "$LOG" "$LOG.prev" 2>/dev/null || true; rm -f "$STAMP_DIR/$TREE.fail" ;;
esac

VERIFY_DIR=${JJ_VERIFY_DIR:-"$(cd .. && pwd)/$(basename "$PWD")-verify"}
COMMIT=$(jj log -r "$REV" --no-graph -T commit_id)
# Workspace names are repo-global, so derive one from this lane's own workspace name.
LANE=$(jj log -r @ --no-graph -T 'working_copies' | awk '{print $1}' | sed 's/@$//')
VERIFY_NAME="verify-${LANE:-$(basename "$PWD")}"

run_gate() {
    echo "$$ $(date +%s)" > "$STAMP_DIR/$TREE.running"
    MAIN_DIR=$PWD
    if jj workspace list -T 'name ++ "\n"' | grep -qx "$VERIFY_NAME"; then
        # Still registered (a crashed run, or a pre-cleanup checkout): reuse it.
        if [ -d "$VERIFY_DIR" ]; then
            cd "$VERIFY_DIR"
            # This workspace only ever holds a throwaway copy of REV, so there are no edits for
            # update-stale to lose (unlike a working lane; see the jj-parallel-lanes caveat).
            jj workspace update-stale >/dev/null 2>&1 || true
            jj new "$COMMIT" -m "verify scratch" >/dev/null 2>&1
            cd "$MAIN_DIR"
        else
            jj workspace forget "$VERIFY_NAME"
        fi
    fi
    if ! jj workspace list -T 'name ++ "\n"' | grep -qx "$VERIFY_NAME"; then
        # The workspace is forgotten after every run but its directory is kept for the build
        # caches. `workspace add` needs a fresh directory, so move the old one aside and bring
        # back only the gitignored entries (never stale tracked files, which would end up in
        # the gate's snapshot).
        OLD_DIR=
        if [ -d "$VERIFY_DIR" ]; then
            OLD_DIR="$VERIFY_DIR.old"
            rm -rf "$OLD_DIR"
            mv "$VERIFY_DIR" "$OLD_DIR"
        fi
        jj workspace add --name "$VERIFY_NAME" -r "$COMMIT" -m "verify scratch" "$VERIFY_DIR"
        if [ -n "$OLD_DIR" ]; then
            GIT_ROOT=$(jj git root)
            for entry in "$OLD_DIR"/* "$OLD_DIR"/.[!.]*; do
                name=$(basename "$entry")
                [ -e "$entry" ] && [ ! -e "$VERIFY_DIR/$name" ] || continue
                if git --git-dir="$GIT_ROOT" --work-tree="$OLD_DIR" check-ignore -q -- "$name"; then
                    mv "$entry" "$VERIFY_DIR/$name"
                fi
            done
            rm -rf "$OLD_DIR"
        fi
    fi
    cd "$VERIFY_DIR"
    echo "verifying commit $COMMIT (tree $TREE) in $VERIFY_DIR"
    # Another Gradle build (e.g. in another workspace) extracting the same IDE image at the same
    # moment makes Gradle's hdiutil step fail. That's infrastructure, not a gate failure: retry.
    ATTEMPT_LOG=$(mktemp)
    RESULT=fail
    for attempt in 1 2 3 4; do
        if scripts/pre-push.sh 2>&1 | tee "$ATTEMPT_LOG"; [ "${PIPESTATUS[0]}" -eq 0 ]; then RESULT=pass; break; fi
        grep -q "hdiutil" "$ATTEMPT_LOG" || break
        echo "IDE extraction collided with another Gradle build; retry $attempt/3 in 90s"
        sleep 90
    done
    rm -f "$ATTEMPT_LOG"
    # Unregister the workspace so its "verify scratch" change doesn't linger in the log (the
    # directory stays, keeping build caches warm), then abandon it and any orphans from earlier
    # runs. Other lanes' in-flight scratches are working copies, so they are left alone.
    cd "$MAIN_DIR"
    jj workspace forget "$VERIFY_NAME" || true
    jj abandon '(description(exact:"verify scratch\n") & empty()) ~ working_copies()' >/dev/null 2>&1 || true
    rm -f "$STAMP_DIR/$TREE.running"
    date > "$STAMP_DIR/$TREE.$RESULT"
    echo "RESULT: $RESULT"
    command -v osascript >/dev/null && osascript -e \
        "display notification \"Gate $RESULT for ${TREE:0:8}\" with title \"jj verify\"" || true
    [ "$RESULT" = pass ]
}

if $WAIT; then
    run_gate 2>&1 | tee "$LOG"
    exit "${PIPESTATUS[0]}"
fi
# Background: a new session so it outlives this shell. The subshell re-enters run_gate.
nohup bash -c "$(declare -f run_gate); STAMP_DIR='$STAMP_DIR' TREE='$TREE' COMMIT='$COMMIT' VERIFY_DIR='$VERIFY_DIR' VERIFY_NAME='$VERIFY_NAME' run_gate" \
    > "$LOG" 2>&1 < /dev/null &
disown
echo "Verifying tree ${TREE:0:12} in the background (workspace: $VERIFY_DIR)."
echo "  log:  tail -f $LOG"
echo "  then: jj ship"
