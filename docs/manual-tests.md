# Manual Test Checklist

This document provides a comprehensive manual test checklist for the Jujutsu IDE plugin. These tests require GUI interaction and should be verified manually using `./gradlew runIde`.

Use this checklist:
- Before releases as a regression checklist
- When verifying feature parity with standard VCS logs
- When onboarding new contributors to understand expected behavior

## How to Run Manual Tests

1. Start the IDE with the plugin: `./gradlew runIde`
2. Open a project with a Jujutsu repository (or create one with `jj git init`)
3. Work through each section below, checking off items as you verify them

## Test Categories

### Table Selection & Navigation

- [ ] Single row selection with mouse click
- [ ] Multi-row selection with Shift+click (range)
- [ ] Multi-row selection with Ctrl/Cmd+click (non-contiguous)
- [ ] Arrow key navigation (Up/Down)
- [ ] Page Up/Page Down navigation
- [ ] Home/End key navigation
- [ ] Selection persists after filtering (if entry still visible)
- [ ] Selection clears when filtered entry is hidden

### Column Management

- [ ] Column visibility toggle via column header context menu
- [ ] Column reordering via drag-and-drop
- [ ] Column resizing via drag on separator
- [ ] Double-click column separator to auto-fit width
- [ ] Column widths persist across IDE restarts
- [ ] Column visibility persists across IDE restarts

### Graph Rendering

- [ ] Graph lines render correctly for linear history
- [ ] Graph lines render correctly for merges
- [ ] Graph lines render correctly for branches
- [ ] Working copy (@) indicator visible
- [ ] Colors differentiate branches
- [ ] Graph column auto-sizes to content

### Details Panel

- [ ] Details panel shows on row selection
- [ ] Metadata displays correctly (author, date, change ID)
- [ ] Description renders HTML formatting
- [ ] Splitter position persists
- [ ] Toggle details panel position (right/bottom) works

### Details Changes Panel

- [ ] File change tree shows correct files
- [ ] Double-click file opens it in editor
- [ ] Menu has "Compare with Another Commit" ❌ jj-idea-7d9p
- [ ] Menu has "Compare Before with Another Commit" ❌ jj-idea-lo7u
- [ ] open file for historical opens correct version ✅
- [ ] open file for working copy opens editable editor ✅


### Context Menu Actions

- [ ] Right-click opens context menu
- [ ] **Copy Change ID** works and copies to clipboard
- [ ] **Copy Description** works and copies to clipboard
- [ ] **New Change From This** creates new change and refreshes
- [ ] **Edit** action changes working copy
- [ ] **Describe** action opens dialog and updates description
- [ ] **Abandon** action removes change after confirmation

### Toolbar & Filters

- [ ] Refresh button reloads data
- [ ] Text search filters entries in real-time
- [ ] Regex toggle enables regex matching
- [ ] Case sensitivity toggle works
- [ ] Author dropdown shows all authors
- [ ] Author filter restricts visible entries
- [ ] Bookmark/reference filter works
- [ ] Date filter restricts to recent commits
- [ ] Clear filters (X button) resets all filters
- [ ] Filters combine correctly (AND logic)

### Multi-Repository (if applicable)

- [ ] Root filter appears for multi-root projects
- [ ] Root filter hides for single-root projects
- [ ] Root gutter column shows repo names
- [ ] Filtering by root works correctly
- [ ] Entries from different roots sort by timestamp

### Auto-Refresh

- [ ] Log refreshes when files change in working copy
- [ ] Log refreshes after VCS operations (describe, new, edit)
- [ ] Working copy (@) selection maintained after refresh
- [ ] No flickering during refresh

### Visual Consistency

- [ ] Light theme renders correctly
- [ ] Dark theme renders correctly
- [ ] Icons display at correct size
- [ ] Row striping visible
- [ ] Hover highlighting works
- [ ] Selected row highlighting distinct
- [ ] Disabled actions appear grayed out

### Edge Cases

- [ ] Empty repository (no commits) shows appropriate message
- [ ] Very long descriptions truncate with ellipsis
- [ ] Non-ASCII characters in descriptions display correctly
- [ ] Large repository (100+ commits) loads without hanging
- [ ] Rapid filtering doesn't cause errors

### Keyboard Shortcuts

Verify these keyboard shortcuts work in the log view:

| Shortcut | Expected Action |
|----------|-----------------|
| Enter | Open selected file (if file selected in details) |
| F4 | Open selected file |
| Ctrl/Cmd+C | Copy selection (change ID or file path) |
| Delete | Abandon selected change (with confirmation) |
| F2 | Rename/describe selected change |

### Working Copy Panel

- [ ] Description text area shows current description
- [ ] "Describe" button updates description via `jj describe`
- [ ] "New Change" button creates new change via `jj new`
- [ ] Changed files tree shows correct status colors
- [ ] File type icons display correctly
- [ ] Double-click opens file in editor
- [ ] Right-click shows context menu with file actions
- [ ] Open shows working copy as editable ✅
- [ ] Single click does nothing ❌ jj-idea-c82g
- [ ] Menu has "Compare with Another Commit" ❌ jj-idea-7d9p
- [ ] Menu has "Compare Before with Another Commit" ❌ jj-idea-lo7u
- [ ] Show diff for multiple files opens multiple editors ✅
- [ ] Open for multiple files opens multiple editors ✅
- [ ] Menu has Open in -> remote ✅
- [ ] Open in -> remote for single parent opens that parent (resolves to pushed ancestor)
- [ ] Open in -> remote hidden when no pushed ancestor exists

### Git Push Dialog

Setup: have a local bookmark that has never been pushed to the remote.

- [ ] Open push dialog (VCS menu → Push) → "Tracking bookmarks (default)" selected → OK → push completes (shows success notification)
- [ ] Open push dialog → "Tracking bookmarks (default)" → if new bookmark exists, confirmation dialog appears asking whether to create remote bookmark → confirm → push succeeds with `--allow-new`
- [ ] Open push dialog → "Specific bookmark" → select an untracked bookmark → OK → push succeeds with `--allow-new`
- [ ] Open push dialog → "All bookmarks" → OK → pushes all bookmarks
- [ ] Cancel push dialog → no push occurs

### Settings (Version Control > Jujutsu)

- [ ] JJ executable path can be configured
- [ ] File picker works for selecting executable
- [ ] Auto-refresh toggle works
- [ ] Change ID format preference (short/long) affects display
- [ ] Log change limit affects number of entries loaded
- [ ] Settings persist across IDE restarts

### Error Handling

- [ ] Invalid JJ path shows helpful error message
- [ ] Non-JJ repository shows appropriate message
- [ ] Network errors during operations show user-friendly errors
- [ ] Concurrent operations don't cause corruption

### Project Tool Window
- [ ] File in tool window has Jujutsu menu ✅
- [ ] Jujutsu menu has "Show Diff" ✅
- [ ] Jujutsu menu has "Compare with Another Commit..." ✅
- [ ] If file has changed, Jujutsu menu has "Compare Before with Another Commit..." ❌ jj-idea-lo7u
- [ ] Show diff for multiple files opens multiple editors ✅
- [ ] Menu has Open in -> remote ✅
- [ ] Open in -> remote for single parent opens that parent ✅

### Diffs
- [ ] Show diff for a directory with changed files shows diffs for each file in the directory
- [ ] Show diff for a directory with no changed files does nothing (no crash)
- [ ] Diff for unchanged file shows no changes (before view has same content, shows content as identical) ✅
- [ ] Diff for modified file and single parent shows before from parent, current from selected ✅
- [ ] Diff for deleted file and single parent shows before from parent, empty current ✅
- [ ] Diff for added file and single parent shows empty before, current from @ ✅
- [ ] Diff for renamed file and single parent shows before from @- with previous filepath, current from @ ✅
- [ ] Diff from working copy shows before = parent, current from working copy
- [ ] When right-hand diff pane contains working copy, it is editable
- [ ] When right-hand diff pane contains historical version, it is not editable

### Editors for Current Files
- [ ] Menu has Jujutsu sub-menu ✅
- [ ] Jujutsu menu has "Show Diff" ✅
- [ ] Jujutsu menu has "Compare with Another Commit" ✅
- [ ] Jujutsu menu has "Annotate" ✅
- [ ] Annotate fetches annotations for the correct revision ✅
- [ ] if file has changed, Jujutsu menu has compare before with another commit ❌ jj-idea-lo7u
- [ ] diff for unchanged file shows no changes (before view has same content, shows content as identical) ✅
- [ ] diff for modified file and single parent shows before from @-, current from @ ✅
- [ ] has open in -> remote ✅
- [ ] open in -> remote for single parent opens that parent ✅

### Conflict Resolution

#### Test setup

Create a reproducible conflict in a scratch jj repo:

```bash
mkdir /tmp/jj-conflict-test && cd /tmp/jj-conflict-test
jj git init
echo -e "line 1\nshared line\nline 3" > file.txt
jj describe -m "initial"
jj new -m "change A"
echo -e "line 1\nchanged by A\nline 3" > file.txt
jj new -r @- -m "change B"
echo -e "line 1\nchanged by B\nline 3" > file.txt
jj rebase -r @- -d @   # rebase change A onto change B → conflict
```

The working copy is now change A rebased on change B, with `file.txt` in conflict.

To test each conflict marker style (jj 0.37+ defaults to `git`):
```bash
jj config set ui.conflict-marker-style git       # <<<<<<< / ||||||| / ======= / >>>>>>>
jj config set ui.conflict-marker-style snapshot  # +++++++ / ------- / +++++++
jj config set ui.conflict-marker-style diff      # +++++++ / %%%%%%% / \\\\\\\
```
After each `config set`, rerun `jj rebase` (or `jj restore`) to regenerate conflict markers in the chosen format.

Open `/tmp/jj-conflict-test` as a project in the plugin IDE (`./gradlew runIde`).

#### Detection

- [ ] `file.txt` appears in the Working Copy panel with red (MERGED_WITH_CONFLICTS) status
- [ ] All three marker styles (git, snapshot, diff) correctly mark the file as conflicted

#### "Resolve Conflicts" context menu action (selection-scoped)

- [ ] Right-clicking a **non-conflicted** file in the Working Copy panel: "Resolve Conflicts…" is **not visible**
- [ ] Right-clicking `file.txt` (conflicted): "Resolve Conflicts…" **is visible**
- [ ] Invoking it opens the merge dialog containing **only** `file.txt`, not unrelated files
- [ ] Multi-select: selecting one conflicted + one non-conflicted file → dialog contains only the conflicted file
- [ ] Multi-select: selecting two conflicted files → dialog contains both

#### "Resolve Conflicts" global action (editor / project view / VCS menu)

- [ ] Right-clicking any file or directory in the Project view → Jujutsu → Resolve Conflicts…: opens dialog with **all** conflicted files
- [ ] Opening `file.txt` in the editor, right-clicking → Jujutsu → Resolve Conflicts…: opens all conflicted files (global action, not scoped to editor file)

#### Three-way merge dialog — content correctness

- [ ] Left pane shows "ours" content (`changed by A`, the rebased change)
- [ ] Right pane shows "theirs" content (`changed by B`, the destination)
- [ ] Center pane is editable; initially shows a proposed merge result (not identical to left or right)
- [ ] Left and right panes are **not** identical — conflict regions are highlighted
- [ ] Works correctly for all three marker styles (git, snapshot, diff)

#### Resolving via the merge dialog

- [ ] Edit the center pane to a desired resolution and click Apply / Save
- [ ] After closing the dialog, `file.txt` content on disk reflects the resolution (no conflict markers)
- [ ] `file.txt` disappears from the Working Copy panel's conflict list (status updates on next refresh)

#### Accept Yours / Accept Theirs (bulk)

- [ ] In the multi-file merge dialog, selecting `file.txt` and clicking **Accept Yours**: file on disk contains "ours" content with no conflict markers
- [ ] Status refreshes: `file.txt` leaves conflicted state
- [ ] **Accept Theirs** analogously writes "theirs" content

#### Log details pane (commit selected in log table)

Use the same conflict setup above. The test repo has a conflicted commit that is **not** the working copy (e.g., run `jj new` to create an empty working copy on top of the conflicted change).

- [ ] Selecting the **conflicted historical commit** in the log: conflicted file appears in the details panel with red (MERGED_WITH_CONFLICTS) status
- [ ] Selecting the **conflicted historical commit**: "Resolve Conflicts…" is **not visible** in the details panel context menu (only working copy conflicts are resolvable)
- [ ] Selecting the **working copy commit** (empty, inherits conflict): "Resolve Conflicts…" **is visible** in the details panel context menu and opens the merge dialog for the inherited conflicted files

#### Log row context menu

- [ ] Right-clicking the **working copy entry** when conflicts exist: "Resolve Conflicts…" appears in the context menu
- [ ] Right-clicking the **working copy entry** when no conflicts exist: "Resolve Conflicts…" is **not visible** (hidden, not just disabled)
- [ ] Right-clicking a **non-working-copy entry** (even one with conflicts): "Resolve Conflicts…" is **not visible**
- [ ] Invoking "Resolve Conflicts…" from the log row context menu opens the merge dialog with all conflicted files

#### Multi-repo scoping

In a project with two jj roots each having conflicts:

- [ ] Right-clicking a conflicted file in root A's Working Copy panel → dialog shows only root A's conflicts
- [ ] Right-clicking a conflicted file in root B's → dialog shows only root B's conflicts
- [ ] Global action (VCS menu) → dialog shows conflicts from both roots

### Editors for Historical Versions
- [ ] has title including change id ✅
- [ ] has Jujutsu menu ✅
- [ ] Jujutsu menu has diff ✅
- [ ] Jujutsu menu has compare with another commit ✅
- [ ] compare with another commit opens that commit on LHS, editor's version on RHS ✅
- [ ] Jujutsu menu has compare with local ❌ jj-idea-zvzk
- [ ] if file has changed, Jujutsu menu has compare before with another commit ❌ jj-idea-lo7u
- [ ] diff for modified file and single parent shows before from parent, current from selected version ✅
- [ ] Jujutsu menu has annotate ❌ jj-idea-3jo
- [ ] Annotate fetches annotations for the correct revision
- [ ] has open in -> remote ✅
- [ ] open in -> remote for single parent opens that parent ✅
- [ ] open in -> remote for unpushed historical version resolves to nearest pushed ancestor ✅
