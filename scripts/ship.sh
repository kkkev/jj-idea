#!/usr/bin/env bash
# `jj ship`: run the pre-push gate, then push master. One-time setup per clone:
#   jj config set --repo aliases.ship '["util", "exec", "--", "scripts/ship.sh"]'
# Extra arguments (e.g. --dry-run) are passed to `jj git push`.
set -euo pipefail
cd "${JJ_WORKSPACE_ROOT:-$(dirname "$0")/..}"

scripts/pre-push.sh

jj git push --remote github -b master "$@"
# The GitLab mirror is often unreachable; don't fail a shipped push over it.
jj git push --remote origin -b master "$@" || echo "warning: push to origin failed; retry later" >&2
