#!/usr/bin/env bash
# `jj ship [--now] [jj git push args]`: push master to github and origin, but only if master's
# content has passed the pre-push gate. Run `jj verify` ahead of time (it works in the
# background); this check is then instant. `--now` runs the gate synchronously instead.
# One-time setup per clone:
#   jj config set --repo aliases.ship   '["util", "exec", "--", "scripts/ship.sh"]'
#   jj config set --repo aliases.verify '["util", "exec", "--", "scripts/verify.sh"]'
set -euo pipefail
cd "${JJ_WORKSPACE_ROOT:-$(dirname "$0")/..}"
# shellcheck source=scripts/verify-lib.sh
source scripts/verify-lib.sh

NOW=false
PUSH_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --now) NOW=true ;;
        *) PUSH_ARGS+=("$arg") ;;
    esac
done

TREE=$(tree_of master)
STATE=$(state_of "$TREE")
if [ "$STATE" != pass ] && $NOW; then
    scripts/verify.sh master --wait
    STATE=$(state_of "$TREE")
fi
case "$STATE" in
    pass) echo "master (tree ${TREE:0:12}) passed the pre-push gate." ;;
    running) echo "Verification of master is still running: tail -f $STAMP_DIR/$TREE.log" >&2; exit 1 ;;
    fail) echo "The pre-push gate FAILED for master: see $STAMP_DIR/$TREE.log" >&2; exit 1 ;;
    none) echo "master (tree ${TREE:0:12}) has not been verified. Run 'jj verify master' (background), or 'jj ship --now'." >&2; exit 1 ;;
esac

jj git push --remote github -b master ${PUSH_ARGS[@]+"${PUSH_ARGS[@]}"}
# The GitLab mirror is often unreachable; don't fail a shipped push over it.
jj git push --remote origin -b master ${PUSH_ARGS[@]+"${PUSH_ARGS[@]}"} || echo "warning: push to origin failed; retry later" >&2
