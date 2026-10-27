# Shared by verify.sh and ship.sh. Verification results ("stamps") are keyed by the git tree id of
# the revision, so they survive describing, rebasing or moving a bookmark without changing files,
# and are invalidated by any content change. Stored in the git dir, which every jj workspace of
# the repo shares and which is never tracked.

STAMP_DIR="$(jj git root)/jj-idea-verified"
mkdir -p "$STAMP_DIR"

# tree_of REV -> git tree id of REV's content, ignoring .beads/. The finish sequence re-exports
# beads after the gate has run; that mechanical change must not invalidate a pass stamp.
tree_of() {
    local g idx commit
    g=$(jj git root)
    commit=$(jj log -r "$1" --no-graph -T commit_id)
    idx=$(mktemp)
    GIT_INDEX_FILE=$idx git --git-dir="$g" read-tree "$commit"
    GIT_INDEX_FILE=$idx git --git-dir="$g" ls-files -- .beads |
        while IFS= read -r f; do printf '0 0000000000000000000000000000000000000000\t%s\n' "$f"; done |
        GIT_INDEX_FILE=$idx git --git-dir="$g" update-index --index-info
    GIT_INDEX_FILE=$idx git --git-dir="$g" write-tree
    rm -f "$idx"
}

# state_of TREE -> pass | fail | running | none  (a running stamp whose process died counts as none)
state_of() {
    local t=$1
    if [ -f "$STAMP_DIR/$t.pass" ]; then echo pass
    elif [ -f "$STAMP_DIR/$t.fail" ]; then echo fail
    elif [ -f "$STAMP_DIR/$t.running" ] && kill -0 "$(cut -d' ' -f1 "$STAMP_DIR/$t.running")" 2>/dev/null; then echo running
    else echo none
    fi
}
