# GitLab Issues for Jujutsu IntelliJ Plugin

This file contains issues to be created in the GitLab project. Use this as a reference when creating issues manually or via the GitLab CLI.

---

## Issue 1: Detect jj versions; use commands according to version

**Title:** Version detection and compatibility for jj commands

**Description:**

Different versions of Jujutsu (jj) have different template keywords and command syntax. We need to:

1. Detect the installed jj version on startup
2. Map template keywords to the correct version (e.g., `working_copy` vs `current_working_copy`)
3. Use version-appropriate command syntax
4. Show a warning if jj version is too old or unsupported

**Acceptance Criteria:**
- [ ] Plugin detects jj version via `jj --version`
- [ ] Template keywords are chosen based on detected version
- [ ] Plugin works with jj 0.20.0+
- [ ] User is warned if jj version is incompatible

**Labels:** enhancement, compatibility

**Priority:** High

---

## Issue 2: Fix preview

**Title:** Fix file preview functionality

**Description:**

The file preview feature needs improvements:

1. Single-click on a file should show preview in a preview tab
2. Preview should not create permanent tabs
3. Preview should work correctly with the IntelliJ preview tab system
4. Consider showing diff preview instead of just file content

**Current Issues:**
- Preview behavior may not match IntelliJ conventions
- Need to verify preview vs permanent tab behavior

**Acceptance Criteria:**
- [ ] Single-click opens file in preview tab
- [ ] Preview tab is replaced when another file is selected
- [ ] Preview behavior matches Git plugin
- [ ] Diff preview option available

**Labels:** bug, ui, preview

**Priority:** Medium

---

## Issue 3: Implement log

**Title:** Complete implementation of commit log view

**Description:**

The log view (History tab) needs completion and enhancement:

1. Ensure all commits are displayed correctly
2. Show commit graph/relationships between commits
3. Display commit metadata (author, timestamp, etc.)
4. Allow filtering and searching commits
5. Support pagination for large repositories

**Current Status:**
- Basic log display is implemented
- Need to add visual graph representation
- Missing some metadata fields

**Acceptance Criteria:**
- [ ] Commit log shows all commits with correct ordering
- [ ] Visual graph shows parent/child relationships
- [ ] All commit metadata displayed (author, date, etc.)
- [ ] Search/filter functionality working
- [ ] Performance is acceptable for large repositories (1000+ commits)

**Labels:** feature, log, ui

**Priority:** High

---

## Issue 4: Allow to edit commits

**Title:** Enable commit editing (describe, amend, etc.)

**Description:**

Users should be able to edit commits in the history:

1. Edit commit description (message) via `jj describe -r <rev>`
2. Amend file changes to any commit via `jj squash`
3. UI for selecting which commit to edit
4. Show which commit is currently being edited
5. Support editing multiple commits in a session

**Acceptance Criteria:**
- [ ] Right-click context menu on commit → "Edit Description"
- [ ] Can edit description of any commit, not just working copy
- [ ] Can amend changes to selected commit
- [ ] Current edit target is clearly indicated in UI
- [ ] Changes are immediately reflected in log view

**Labels:** feature, editing, core-workflow

**Priority:** High

---

## Issue 5: Show bookmarks in the log

**Title:** Display bookmarks (branches) in commit log view

**Description:**

Bookmarks should be visible in the log view:

1. Show bookmark names next to commits
2. Color-code local vs remote bookmarks
3. Show current bookmark with special indicator
4. Support displaying multiple bookmarks on same commit
5. Make bookmarks clickable for actions

**Current Status:**
- Log parser extracts bookmark data
- Need to improve rendering in table view

**Acceptance Criteria:**
- [ ] Bookmarks appear in log view next to commit
- [ ] Local and remote bookmarks distinguished visually
- [ ] Current bookmark highlighted
- [ ] Multiple bookmarks on same commit handled well
- [ ] Bookmark click opens context menu with actions

**Labels:** feature, bookmarks, ui, log

**Priority:** Medium

---

## Issue 6: Make bookmark management like Git branch management

**Title:** Implement bookmark management UI similar to Git branches

**Description:**

Create a bookmark management system similar to IntelliJ's Git branch UI:

1. "Branches" dropdown in toolbar → "Bookmarks"
2. List all local and remote bookmarks
3. Create new bookmark
4. Rename/delete bookmarks
5. Track remote bookmarks
6. Push/pull bookmarks
7. "Checkout" bookmark (move working copy to bookmark)

**Acceptance Criteria:**
- [ ] Bookmarks menu accessible from toolbar
- [ ] Shows tree of local/remote bookmarks
- [ ] Create bookmark: `jj bookmark create <name>`
- [ ] Delete bookmark: `jj bookmark delete <name>`
- [ ] Move to bookmark: `jj new <bookmark>`
- [ ] Track remote bookmarks: `jj bookmark track`
- [ ] Push/pull actions available

**Labels:** feature, bookmarks, ui, git-parity

**Priority:** High

---

## Issue 7: Allow to create new commit at any place in the commit log

**Title:** Create new commit at arbitrary position in history

**Description:**

Users should be able to create a new commit anywhere in the commit graph:

1. Right-click on any commit in log → "New commit here"
2. Execute `jj new <selected-rev>`
3. Update working copy view to reflect new position
4. Show clear indication of where working copy is now
5. Explain to user what happened (new commit created on top of X)

**Acceptance Criteria:**
- [ ] Right-click context menu on any commit
- [ ] "New commit here" action executes `jj new <rev>`
- [ ] Working copy updates to show new empty commit
- [ ] Log view shows new working copy position
- [ ] User notification explains the operation

**Labels:** feature, workflow, log

**Priority:** Medium

---

## Issue 8: Allow to create new commit on several commits (Git merge functionality)

**Title:** Support creating merge commits (multi-parent commits)

**Description:**

Implement jj's merge functionality:

1. Select multiple commits in log view (Ctrl+Click)
2. Action: "Create merge commit" or "New commit with these parents"
3. Execute `jj new <rev1> <rev2> ...`
4. Handle conflicts if they arise
5. Show merge commit in log graph with multiple parent lines

**Acceptance Criteria:**
- [ ] Multi-select commits in log view (Ctrl/Cmd+Click)
- [ ] Context menu: "Merge these commits" or "New commit on these"
- [ ] Executes `jj new <rev1> <rev2> ...`
- [ ] Conflict resolution UI appears if needed
- [ ] Merge commit shown with multiple parents in graph
- [ ] Working copy view indicates multi-parent state

**Labels:** feature, merge, workflow, advanced

**Priority:** Low

---

## Additional Technical Tasks

### Set up CI/CD pipeline
- [ ] Create `.gitlab-ci.yml` for automated builds
- [ ] Add test execution to CI
- [ ] Add code quality checks
- [ ] Publish build artifacts

### Documentation improvements
- [ ] Add screenshots to README
- [ ] Create user guide
- [ ] Document JJ workflow in plugin context
- [ ] Add troubleshooting section

### Testing
- [ ] Increase test coverage
- [ ] Add integration tests with real jj repository
- [ ] Test with multiple jj versions
- [ ] Performance testing with large repositories

---

## How to Create These Issues

### Using GitLab CLI (glab)

After authenticating with `glab auth login`, run:

```bash
# Issue 1
glab issue create --title "Version detection and compatibility for jj commands" \
  --description "See GITLAB_ISSUES.md" \
  --label "enhancement,compatibility"

# Issue 2
glab issue create --title "Fix file preview functionality" \
  --description "See GITLAB_ISSUES.md" \
  --label "bug,ui,preview"

# Issue 3
glab issue create --title "Complete implementation of commit log view" \
  --description "See GITLAB_ISSUES.md" \
  --label "feature,log,ui"

# Issue 4
glab issue create --title "Enable commit editing (describe, amend, etc.)" \
  --description "See GITLAB_ISSUES.md" \
  --label "feature,editing,core-workflow"

# Issue 5
glab issue create --title "Display bookmarks in commit log view" \
  --description "See GITLAB_ISSUES.md" \
  --label "feature,bookmarks,ui,log"

# Issue 6
glab issue create --title "Implement bookmark management UI similar to Git branches" \
  --description "See GITLAB_ISSUES.md" \
  --label "feature,bookmarks,ui,git-parity"

# Issue 7
glab issue create --title "Create new commit at arbitrary position in history" \
  --description "See GITLAB_ISSUES.md" \
  --label "feature,workflow,log"

# Issue 8
glab issue create --title "Support creating merge commits (multi-parent commits)" \
  --description "See GITLAB_ISSUES.md" \
  --label "feature,merge,workflow,advanced"
```

### Using GitLab Web UI

1. Go to your project → Issues → New Issue
2. Copy the title and description from this file
3. Add the specified labels
4. Set priority and assignees as needed
