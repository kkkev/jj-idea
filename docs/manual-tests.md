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
- [ ] On a commit with ~30 bookmarks (e.g. `for i in $(seq 1 30); do jj bookmark create
      bm-$i; done`), the log row still shows description text (not blank), and the
      bookmarks collapse behind a grey "+N more" chip rather than overflowing the cell
      (jj-idea-w61m)
- [ ] Stress-test repo, many concurrent branches (`jj-idea-1ojh`, `jj-idea-5i6i`): open
      `jj-stress-test` (1084 commits, ~26 concurrent heads incl. a ~200-commit
      `deep-branch` and an `octopus-merge`); set Settings → Version Control → Jujutsu →
      Log Limit to 200 so several branches fall out of view; apply an author or date
      filter to shrink the visible set further; confirm tree lines never cross over
      unrelated commits or share a lane (dropped edges to filtered-out ancestors are
      expected and tracked separately as `jj-idea-hlu3`, not a bug here); clear the
      filter and confirm the graph restores immediately without a manual Refresh
- [ ] Hovering that row's tooltip lists every bookmark, including the ones collapsed
      behind "+N more" (jj-idea-w61m), wrapping the bookmark list across multiple lines
      and showing the full description without being clipped by the screen edge; if the
      content is taller than the screen it scrolls instead of clipping (jj-idea-szn8)
- [ ] Left-clicking the "+N more" chip opens a popup listing the hidden bookmarks, each
      as a sub-menu with the usual bookmark actions (Rename…, Delete, Forget, etc.); right-
      clicking it does the same (jj-idea-w61m)

### Details Panel

- [ ] Details panel shows on row selection
- [ ] Metadata displays correctly (author, date, change ID)
- [ ] Description renders HTML formatting
- [ ] Splitter position persists
- [ ] Toggle details panel position (right/bottom) works
- [ ] On a commit with several long/hyphenated bookmarks (e.g. `hotfix/issue-123`,
      `feature/long-name-here`), narrow the panel until the bookmark line wraps — each
      bookmark (icon + name) stays intact on one line; wrapping only occurs between
      bookmarks, never inside a name or between its icon and text (jj-idea-kds1)

### Details Changes Panel

- [ ] File change tree shows correct files
- [ ] Double-click file opens diff in a single editor tab (preview tab)
- [ ] Enter on selected file opens the same diff tab
- [ ] Clicking a different file while the diff tab is open swaps its content; tab count stays at 1
- [ ] Escape inside the diff tab closes it
- [ ] Cmd/Ctrl+D opens the same diff preview tab (routes through preview when available)
- [ ] F4 still opens the file in a regular editor tab (no "Synchronous execution on EDT" error in IDE log)
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

### Squash Into...

#### Test setup

Create a small jj repo with a few stacked changes:

```bash
mkdir /tmp/jj-squash-test && cd /tmp/jj-squash-test
jj git init
echo "base" > base.txt && jj describe -m "base"
jj new -m "change A" && echo "A content" > a.txt
jj new -m "change B" && echo "B content" > b.txt
jj new -m "change C" && echo "C content" > c.txt
```

This gives a linear stack: base → A → B → C (@ = C).

Open `/tmp/jj-squash-test` in the plugin IDE.

#### Availability / enablement

- [ ] "Squash Into..." is present in context menu for a single mutable change
- [ ] "Squash Into..." is present when 2+ mutable changes are selected
- [ ] "Squash Into..." is **disabled** when any selected change is immutable
- [ ] "Squash Into..." is **disabled** when selections span multiple repos (multi-root project)
- [ ] "Squash into Parent..." still works for single-parent mutable changes (regression)

#### Destination picker

- [ ] Dialog opens with source change(s) listed at the top
- [ ] Source change itself is **not** selectable as destination
- [ ] Immutable changes are **not** shown in the destination table
- [ ] **Descendants of the source ARE shown** as valid destinations (e.g. selecting "change A", "change B" and "change C" should both appear)
- [ ] Typing in the search field filters by change ID, description, and bookmark name
- [ ] Clearing the search restores the full filtered list
- [ ] Selecting a destination populates the description field (if user hasn't typed)

#### Description auto-population (full squash — all files selected)

- [ ] Source and destination both have descriptions → field pre-fills with `<dest desc>\n\n<source desc>`
- [ ] Destination description empty, source non-empty → field shows source description only (jj will use it)
- [ ] Source description empty, destination non-empty → field shows destination description only
- [ ] Both empty → field is empty
- [ ] Multi-source: all non-empty source descriptions appended after dest description
- [ ] Editing the description field prevents further auto-updates on destination change

#### Description auto-population (partial squash — some files unchecked)

- [ ] Field shows **only the destination description**, regardless of what the source description is
- [ ] Switching back to all-files-selected restores the combined pre-fill (if user hasn't edited)

#### Validation

- [ ] "Squash" button is active initially (if destination pre-selected after load)
- [ ] Clicking "Squash" with no destination selected shows inline error "Select a destination"
- [ ] Unchecking all files in the tree shows inline error "Select at least one file"

#### "Delete empty source and move working copy" checkbox

- [ ] Checkbox is **enabled** when all files are selected (full squash)
- [ ] Checkbox is **disabled** (grayed out) when any file is unchecked (partial squash — source won't be empty)
- [ ] Checkbox defaults to unchecked
- [ ] Last-used state is remembered across dialog opens

**Full squash, checkbox unchecked (default):**
1. Select "change A" → "Squash Into..." → pick "change B", leave all files
2. Leave checkbox unchecked → click "Squash"
- [ ] "change A" is kept (now empty) — it was NOT abandoned
- [ ] Working copy stays where it was (@ does not move to B)
- [ ] Log selection stays on "change A"

**Full squash, checkbox checked:**
1. Select "change A" → "Squash Into..." → pick "change B", leave all files
2. **Check** the checkbox → click "Squash"
- [ ] "change A" disappears (abandoned)
- [ ] "change B" now contains `a.txt`
- [ ] If "change A" was the working copy (@), working copy moves to "change B"

**Partial squash, checkbox disabled:**
1. Add two files: `jj new -m "multi" && echo "x" > x.txt && echo "y" > y.txt`
2. Select that change → "Squash Into..." → pick any destination
3. Uncheck `y.txt` in the file tree
- [ ] Checkbox is grayed out and cannot be checked
4. Click "Squash"
- [ ] Only `x.txt` moves to the destination
- [ ] Source change still exists (now containing only `y.txt`)
- [ ] Source description is unchanged (no `--message` sent)
- [ ] Destination description is unchanged

#### Squashing a parent into a child (descendant target)

1. Select "change A" → "Squash Into..." → pick "change C" as destination
- [ ] "change C" appears in the destination picker
2. Leave all files selected → click "Squash" (checkbox unchecked)
- [ ] Squash completes without error
- [ ] "change A"'s content is now in "change C"

#### Multi-source squash

1. Ctrl/Cmd+click to select "change A" and "change B" → "Squash Into..."
2. File tree shows files from both A and B combined
3. Pick "change C" as destination → click "Squash"
- [ ] Both A and B disappear from log (or are kept if checkbox unchecked — check either way)
- [ ] "change C" now contains files from both A and B

#### Working copy as source

1. Make sure `@` is on "change C" with some content
2. Select `@` → "Squash Into..." → pick "change B" as destination

**Without checkbox:**
3. Leave checkbox unchecked → click "Squash"
- [ ] Working copy stays on "change C" (now empty or partial)
- [ ] "change B" has the squashed content

**With checkbox:**
3. Check the checkbox → click "Squash"
- [ ] "change C" (old @) is abandoned
- [ ] Working copy moves to "change B" (now @)
- [ ] No stranded empty change left behind

#### Merge commit target

1. Create a merge: `jj new -m "merge" change_a change_b`
2. Select the merge commit → "Squash Into..."
- [ ] Merge commit appears as a valid destination in the picker
- [ ] Squashing into the merge commit succeeds
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

#### Graph layout under filtering (jj-idea-7jkr)

Use a repo with several concurrent branches (at least 3–4 parallel lanes in the graph).

- [ ] Type a text filter that hides some rows — the graph re-draws to match the **visible** rows only: lines do not extend to hidden commits, no misaligned passthrough lines across the remaining rows
- [ ] Clear the text filter — the graph returns to the full layout immediately (no stale passthrough lines from the filtered view)
- [ ] Apply author, date, or root filter on a multi-branch repo — same check: graph lines align with visible rows only
- [ ] Rapid typing (several characters quickly) converges to a single correct layout within ~250 ms (no flickering per keystroke)

### Reference Filter (bookmark/tag dropdown)

Use a repo where the log limit is **smaller** than total history, with at least one bookmark and
one tag pointing at commits **beyond** the limit. The stress-test repo at limit 100 works: `main`
and the `release-*` bookmarks/tags sit deep in history, and `main`'s ancestry is immutable.

- [ ] Dropdown lists **all** local bookmarks (with the gold bookmark icon, narrower than the tag icon), not only those on loaded log rows
- [ ] Dropdown lists **all** tags (with the green tag icon), including tags beyond the log limit
- [ ] Bookmark and tag icons are visibly distinct from each other and from the "@" working-copy icon, and colored to match the bookmark/tag colors used in the log table
- [ ] The currently-selected reference shows a checkmark next to its icon; no other row does
- [ ] Hovering over rows or moving the keyboard selection up/down does **not** move the checkmark — it stays on the actually-selected reference
- [ ] Creating/deleting a bookmark or tag in the terminal updates the dropdown after the auto-refresh (~300 ms) — without clicking Refresh or saving a file (external jj ops are detected via the op-heads watch)
- [ ] Selecting a reference that **is** on a loaded row filters the log to that commit and its ancestors, and the dropdown closes
- [ ] Selecting a reference whose target is **outside** the log limit expands the log to a context window around that commit, then applies the ancestor filter (no silent empty result)
- [ ] Selecting "@" (working copy) filters to the working copy and its ancestors
- [ ] Reopening the dropdown while a filter is active scrolls to and highlights the currently-selected reference
- [ ] Arrow up/down moves the highlight; Enter applies the highlighted reference and closes the dropdown
- [ ] Clearing the filter restores the full (limited) log

### Bookmark Widget

#### Single-repo project

- [ ] "Bookmark: \<name\>" label appears in the log toolbar to the left of the Reference filter when @ has a local bookmark
- [ ] Label shows "Bookmark:" with nothing after it when @ has no local bookmarks (no placeholder text)
- [ ] Label updates reactively: run `jj bookmark create foo` in the terminal — label changes to "Bookmark: foo" within ~300 ms, without saving a file or restarting (external jj ops detected via the op-heads watch)
- [ ] Click the widget — dropdown opens with "Create Bookmark Here…" at the top
- [ ] Dropdown lists all local bookmarks in the repo (not just those on @, and including bookmarks beyond the log limit), each as a sub-menu
- [ ] For a bookmark **on @**: sub-menu contains Rename…, Delete, Forget (no Move Here)
- [ ] For a bookmark **not on @**: sub-menu contains Move…, Rename…, Delete, Forget
- [ ] Remote bookmarks (e.g. `master@origin`) are folded into the corresponding local bookmark's sub-menu as Track/Untrack, not shown as separate top-level items
- [ ] "Create Bookmark Here…" → dialog appears → enter name → confirm → bookmark created at @, label updates, log decorations update
- [ ] Rename… → dialog → confirm → bookmark renamed in log and label
- [ ] Delete → bookmark removed; if it was on @, label reverts to blank
- [ ] Forget (for a remote bookmark entry) → remote tracking removed

#### Multi-repo project

- [ ] Bookmark widget is present in the toolbar (not hidden)
- [ ] Label shows "Bookmark:" with nothing after it regardless of which bookmarks exist
- [ ] Click the widget — dropdown shows one sub-menu **per repo**, named by repo display name
- [ ] Each repo sub-menu contains the same structure as the single-repo dropdown: "Create Bookmark Here…" at the top, then the repo's bookmark sub-menus
- [ ] "Create Bookmark Here…" inside repo-a's sub-menu creates a bookmark at **repo-a's** working copy, not repo-b's (check via `jj bookmark list` in each repo)
- [ ] Rename/Delete/Forget in repo-b's sub-menu affects only repo-b

### Multi-Repository (if applicable)

- [ ] Root filter appears for multi-root projects
- [ ] Root filter hides for single-root projects
- [ ] Root gutter column shows repo names
- [ ] Filtering by root works correctly
- [ ] Entries from different roots sort by timestamp

### Auto-Refresh

- [ ] Log refreshes when files change in working copy
- [ ] Log refreshes after VCS operations (describe, new, edit)
- [ ] Log refreshes after an **external** jj operation run in a terminal (e.g. `jj new`, `jj bookmark create`) within ~300 ms, without saving a file (op-heads watch)
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
- [ ] Double-click opens diff in a single editor tab (preview tab)
- [ ] Enter on selected file opens the same diff tab
- [ ] Clicking a different file while the diff tab is open swaps its content; tab count stays at 1
- [ ] Single click does nothing if diff tab is not open ✅
- [ ] Single click swaps diff content if diff tab is already open
- [ ] Escape inside the diff tab closes it
- [ ] Cmd/Ctrl+D opens the same diff preview tab
- [ ] F4 still opens the file in a regular editor tab (no "Synchronous execution on EDT" error in IDE log)
- [ ] Right-click shows context menu with file actions
- [ ] Open shows working copy as editable ✅
- [ ] Menu has "Compare with Another Commit" ❌ jj-idea-7d9p
- [ ] Menu has "Compare Before with Another Commit" ❌ jj-idea-lo7u
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
- [ ] Backend without `remote_bookmarks()` revset support (jj-idea-2wpq, GitHub #35; can't be
  reproduced with stock jj — requires a non-standard backend, e.g. Google-internal
  Piper/p4base-backed jj) loads the log and working copy successfully, minus the
  pushed-ancestor decoration (the "Open File in remote" action stays hidden) — instead of
  failing the whole load. A one-time WARN is logged on first detection; subsequent
  refreshes/loads for that repo don't re-probe or repeat the warning for the rest of the
  session

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
- [ ] `file.txt` disappears from the Working Copy panel's conflict list **automatically**, without pressing Refresh (jj-idea-3cvb: a stale conflict decoration used to survive even a manual Refresh)
- [ ] Right-clicking `file.txt` again (now resolved): "Resolve Conflicts…" is **not visible**, and if triggered anyway does not throw

#### Accept Yours / Accept Theirs (bulk)

- [ ] In the multi-file merge dialog, selecting `file.txt` and clicking **Accept Yours**: file on disk contains "ours" content with no conflict markers
- [ ] `file.txt` leaves conflicted state automatically, without pressing Refresh
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

### .gitignore File Status (jj-idea-ww5)

**Setup**: open this project itself in `./gradlew runIde` — it has a `.gitignore` with `build/`, `.gradle/`, etc.

#### Project Tool Window — ignored file coloring

- [ ] `build/` directory is displayed with grayed-out (IGNORED) color in the Project tree
- [ ] `.gradle/` directory is grayed out
- [ ] `src/` and tracked source files are NOT grayed out
- [ ] Nested files inside `build/` (e.g. `build/classes/Main.class`) are also grayed out (parent propagation)

#### Local Changes — Ignored Files node

- [ ] Version Control → Local Changes shows an "Ignored Files" group
- [ ] `build/` and its contents appear under "Ignored Files"
- [ ] Tracked modified files (e.g. a file you just edited) do NOT appear under "Ignored Files"; they appear as changes

#### Reactive update on .gitignore edit

Note: order matters — jj auto-tracks files created before the matching gitignore rule exists,
so adding a file to .gitignore after it's already tracked will not untrack it (same as git).

- [ ] Add `*.xyz` to `.gitignore` and save first
- [ ] Then create a new file `test-ignored.xyz` in the repo root
- [ ] It should appear gray (IGNORED color) in the Project tree immediately (jj did not auto-track it)
- [ ] Remove `*.xyz` from `.gitignore` and save → `test-ignored.xyz` turns unversioned color (green/teal)
- [ ] Delete `test-ignored.xyz` when done

#### Tracked files not wrongly ignored

- [ ] Edit a tracked file (e.g. `CHANGELOG.md`) — it should remain non-gray and appear in working copy changes
- [ ] Even if `.gitignore` contained a pattern matching `CHANGELOG.md`, a tracked file would not be grayed (not tested here — tracked files are never passed to the ignore check)

#### Ignore-scan watchdog (jj-idea-la8w)

The watchdog (5s) now aborts the in-progress full ignore-scan instead of merely logging — hard
to trigger on a small repo, so this is mostly a code-level scale test
(`GitignoreScanTest.kt`), but the disable escape hatch remains manually verifiable:

- [ ] Settings / Version Control / Jujutsu → per-repo "disable ignored-file scanning" checkbox
      still works: enable it, edit `.gitignore`, confirm the Ignored Files node stops updating
- [ ] If you have access to a very large repo: a slow scan should show the "ignore scan slow"
      notification once per repo per session, with "disable" and "report" actions still
      functioning; the IDE should not hang waiting for the scan to finish after the watchdog
      fires

#### Large ignored-file set cap (jj-idea-cvqz)

Ignored files are now reported via `ChangelistBuilder.processIgnoredFile` inside the CLM refresh
(same cycle as change detection). The async scan still runs off the refresh thread; `getChanges`
reads the cached set. A `IGNORE_REPORT_CAP` (50,000 entries) limits the number of
`processIgnoredFile` calls per refresh. If the cached set exceeds the cap, a one-shot
"Jujutsu Ignored-File List Is Very Large" notification appears with a "disable scanning" action.

- [ ] Open a repo with ignored files — they appear under "Ignored Files" in Local Changes
- [ ] Ignored files still update after editing `.gitignore` (the async rescan triggers a CLM
      refresh, which calls `getChanges` again and picks up the updated set)
- [ ] If you have access to a repo with >50,000 ignored top-level entries: the notification
      fires once; "disable scanning" action disables the setting and the Ignored Files node
      becomes empty

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

### Split — Hunk-level selection (jj-idea-5isf.1)

Setup: create a scratch jj repo with a file that has at least two separate hunks of changes.

#### Basic hunk selection (native merge picker)
- [ ] Right-click a mutable change → **Split…** → dialog shows changed-files list on the left and a native read-only diff preview on the right
- [ ] Click a file in the list → right panel shows a native syntax-highlighted `Before (original) ↔ After (all changes)` diff
- [ ] Untick a file → right preview switches to `Before (original) ↔ First commit (unchanged)` with **no diff** (empty right side); re-tick → full diff returns
- [ ] Fully-ticked files show a filled checkbox; unticked show empty; partially-picked (see below) show a **half-checked** box
- [ ] Directory nodes containing a partial file also show a half-checked box

#### Hunk picking with the native merge window
- [ ] Click **Pick Hunks…** → IntelliJ's 3-pane merge window opens titled "Pick hunks for first commit — <filename>"
- [ ] Resolve button reads **"Use for First Commit"** (not "Apply"); cancel button reads **"Cancel Split"**
- [ ] After editing the result and pressing Cancel → confirmation dialog reads **"Cancel Hunk Selection?"** / **"Discard the hunks you picked for the first commit?"**
- [ ] Accept some changes (>>) and press "Use for First Commit" → merge window closes; file shows **half-checked** in the file list; summary shows "(N partial)"; right preview shows `Before ↔ First commit` (the picked content)
- [ ] Accepting **every** change → file remains fully checked (no half-check); summary unchanged
- [ ] Accepting **no** changes → file becomes unchecked (excluded from first commit)
- [ ] Cancelling the merge → file state unchanged
- [ ] Split (linear) → first commit contains only the picked hunks; second commit has the rest
- [ ] Log refreshes selecting the newly created change
- [ ] (Platform) "All changes have been processed — Save changes and finish merging" balloon appears when all changes accepted — this is platform wording, expected

#### Descriptions
- [ ] Both description fields are pre-populated with the source commit's description
- [ ] Editing the parent description field updates the first commit; editing child updates the second

#### Parallel split
- [ ] Check "Create parallel commits" → header labels switch to "First" / "Second"
- [ ] Split → two sibling commits created (not parent/child)

#### Whole-file fast path
- [ ] With no partial hunk selection (all files fully checked or unchecked) → split completes via file-level `jj split` (no diff-editor overhead); verify via log that both commits have the expected files

#### Binary / conflicted files
- [ ] A binary file in the changed list shows no "Pick Hunks…" button (whole-file only)

### Settings — Support section

- [ ] Open **Settings → Version Control → Jujutsu**
- [ ] A **Support** group appears at the bottom of the panel with a "Sponsor this plugin on GitHub..." link
- [ ] Clicking the link opens `https://github.com/sponsors/kkkev` in the default browser
