# Manual Test Checklist

This document provides a comprehensive manual test checklist for the Jujutsu IDE plugin. These
tests require GUI interaction and should be verified manually using `./gradlew runIde`.

Use this checklist:
- Before releases: the [Release smoke](#release-smoke) pass, then the sections it points to for anything that fails (a full pass only when the smoke pass shows broad breakage)
- When verifying feature parity with standard VCS logs
- When onboarding new contributors to understand expected behavior
- **After a change, to re-run only the affected sections** — see [How to scope a run](#how-to-scope-a-run)

## How to Run Manual Tests

1. Start the IDE with the plugin: `./gradlew runIde`
2. Open a project with a Jujutsu repository (or create one with `jj git init`)
3. Before a release, run [Release smoke](#release-smoke). For a change, scope to the affected
   sections (see below) — the full list is too large to run for every PR; a full pass is for
   when smoke shows broad breakage.

## How to scope a run

Each section's **Code:** line is the blast-radius map. To scope a run: list the files your
change touched → `grep -n 'Code:' docs/manual-tests.md` for each → check
[Shared Surfaces](#shared-surfaces) and each match's **Also re-run:** line → run the union.
See contributing.md § "Manual regression scope as a deliverable" for how this feeds a PR report.

## Release smoke

The short pass to run before every release, before deciding whether the full sections need a run. Each line is something a user would notice broken within a minute; the tag names the section with the deep checks. Use FX-STACK for squash/split, FX-CONFLICT for conflicts and FX-STRESS (`SCALE=6 WITH_REMOTE=1`) for the log. A failure here means run that whole section.

**Log**
- [ ] Open the Jujutsu log: the first page loads fast, the graph draws (linear, merge, branch), `@` is marked, and selecting a row fills the details panel (MT-LOG-TABLE, MT-LOG-GRAPH, MT-LOG-DETAILS)
- [ ] Filter by text, Author, Reference and Scope in turn, then clear them all: rows narrow correctly, nothing flickers, the full log returns (MT-LOG-FILTER)
- [ ] Run `jj new` and `jj bookmark create x` in a terminal: the log and the bookmark widget update within ~1 s without Refresh (MT-LOG-REFRESH)
- [ ] On `r80k-git` (or FX-STRESS `SCALE=6`), scroll to the end: pages keep loading with no freezes and the viewport stays put (MT-LOG-REFRESH "Paged log loading")
- [ ] Hover a row, a bookmark chip and an author name: tooltips and hover cues behave; right-click a chip and an author name for their menus (MT-LOG-TABLE)

**Changing history**
- [ ] Right-click a row: New Change, Edit, Describe, Abandon each work, and the Abandon balloon's Undo brings the change back (MT-CTXMENU)
- [ ] Rebase dialog with its preview, Duplicate Onto, and Move Up/Move Down on a small stack (MT-CTXMENU)
- [ ] Squash Into: pick a destination, see the result preview, tick/untick a file, squash (MT-SQUASH)
- [ ] Split a change with a file ticked, and open Pick Hunks… on a two-hunk file and apply a partial pick (MT-SPLIT)
- [ ] Drag a commit onto another to rebase, drag a bookmark chip onto a commit to move it, and drag the `@` marker to edit a commit (MT-DND)
- [ ] The Operations tab (if enabled): revert an operation and check the log updates (MT-OPLOG)

**Bookmarks and remotes**
- [ ] Bookmark widget: Create Bookmark Here…, Advance, Rename…, Delete; the Bookmarks panel tree shows Local/remote/Tags and "Filter Log to Bookmark" narrows the log (MT-BOOKMARK)
- [ ] Push a bookmark (dialog, confirmation for a new remote bookmark) and Fetch; a tag push if jj ≥ 0.44 (MT-GIT)

**Working copy, diff and files**
- [ ] Working Copy panel: describe `@`, New Change, change a file and see it listed with the right colour, open Restore… and restore one file (MT-WORKINGCOPY)
- [ ] Show Diff from the log and from the Working Copy panel opens a single preview tab that swaps on click; Annotate a file and click an annotation to select its change (MT-DIFF, MT-DIFF-PREVIEW)
- [ ] Set Diff Base to "Latest immutable ancestor" and back: gutter markers follow (MT-DIFFBASE)
- [ ] Ignored files are grey and appear under Ignored Files; the Tracked toggle on an ignored file works (MT-IGNORE)

**Conflicts**
- [ ] With FX-CONFLICT: the file shows red, the editor banner offers Accept links and Open Merge Tool, an accept resolves it with a "Resolve conflict completed" balloon, and cancelling the merge tool leaves the markers intact (MT-CONFLICT)

**Settings, environment and projects**
- [ ] Settings → Version Control → Jujutsu opens with no horizontal scrollbar; **Test** on the jj path works, and Installation Help shows (MT-SETTINGS)
- [ ] A project with no `.jj` offers Initialize and then populates the Working copy window; a stale workspace and a broken store each show their notification instead of an error (MT-WORKINGCOPY)
- [ ] In a two-repo project: the Root filter, the per-repo bookmark widget menu and the Working Copy "Other Repositories" node all behave (MT-CROSS, MT-BOOKMARK, MT-WORKINGCOPY)
- [ ] Switch between a light and a dark theme: chips, markers and the graph are readable in both (MT-LOG-TABLE, MT-CROSS)

## Shared Surfaces

Components whose blast radius exceeds their own package:

| Component | Feeds |
|---|---|
| `ui/components/TextCanvas.kt`, `LogEntryText.kt`, `HtmlTextCanvas.kt`, `UnbreakableContent.kt`, `AtomicHtmlView.kt`, `HtmlIcons.kt`, `Linkifier.kt` | [MT-LOG-DETAILS](#mt-log-details), [MT-WORKINGCOPY](#mt-workingcopy), [MT-BOOKMARK](#mt-bookmark) |
| Renderer trio: `ui/log/JujutsuLogTableRenderers.kt`, `ui/log/JujutsuGraphAndDescriptionRenderer.kt`, `ui/log/LaidOutCell.kt`, `ui/log/LogClickTarget.kt`, `LogEntryText.kt.appendSummaryAndStatuses` | [MT-LOG-TABLE](#mt-log-table), [MT-LOG-GRAPH](#mt-log-graph), [MT-LOG-DETAILS](#mt-log-details), [MT-DND](#mt-dnd) (the `@` marker's hit target) |
| `ui/components/RevisionSelectorPopup.kt` | [MT-CTXMENU](#mt-ctxmenu), [MT-DIFF](#mt-diff), [MT-DIFFBASE](#mt-diffbase) (quick action's "Choose revision...") |
| Shared revset input (`ui/components/RevsetTextField.kt`, `RevsetCompletionProvider.kt`, `RevsetInputDialog.kt`, `jj/revset/`; used by the Scope popup, the log search box in revset mode, the Settings revset fields and the Set Diff Base → Custom revset dialog) | [MT-LOG-FILTER](#mt-log-filter), [MT-SETTINGS](#mt-settings), [MT-DIFFBASE](#mt-diffbase) |
| Shared commit picker (`ui/components/CommitPickerPanel.kt`, `ui/components/LogSearchField.kt`, used by Rebase, Squash Into…, Duplicate Onto…, Move Bookmark to Change) | [MT-CTXMENU](#mt-ctxmenu), [MT-SQUASH](#mt-squash), [MT-SPLIT](#mt-split), [MT-BOOKMARK](#mt-bookmark), [MT-LOG-FILTER](#mt-log-filter) |
| `ui/components/IconAwareTooltip.kt` (icon-aware tooltip installer, incl. `installIconAwareTableTooltip`) and `ui/log/LogPreviewTable.kt` (shared picker/preview table setup) | [MT-LOG-TABLE](#mt-log-table), [MT-CTXMENU](#mt-ctxmenu), [MT-SQUASH](#mt-squash) |
| Diff preview-tab helper (`ui/common/JujutsuEditorTabDiffPreview.kt`) | [MT-DIFF-PREVIEW](#mt-diff-preview), and its three referrers |
| Shared post-operation graph preview (`ui/rebase/RebasePreviewPanel.kt`, `ui/rebase/RebaseSimulator.kt`, `ui/squash/SquashSimulator.kt`) | [MT-CTXMENU](#mt-ctxmenu) (Rebase / New Change dialogs), [MT-SQUASH](#mt-squash) |
| In-dialog file diff preview shell (`ui/common/FileDiffPreviewPanel.kt`) | [MT-SPLIT](#mt-split), [MT-SQUASH](#mt-squash) |
| Shared hunk-pick preview cache/renderer (`ui/common/HunkPickPreviewController.kt`, `ui/common/HunkSelectionModel.kt`) and the diff-editor staging protocol (`diffedit/DiffEditTool.kt`, `diffedit/HunkApplyMain.kt`) | [MT-SPLIT](#mt-split), [MT-SQUASH](#mt-squash) |
| Multi-repo scoping (root-aware actions/filters generally) | [MT-CROSS](#mt-cross), plus every section with a repo-scoped action |
| `actions/filechange/FileChangeActionGroup.kt` (file-change right-click menu, via `JujutsuChangesTree.installHandlers()`) | [MT-LOG-DETAILS](#mt-log-details), [MT-WORKINGCOPY](#mt-workingcopy), [MT-CTXMENU](#mt-ctxmenu) (`JujutsuCompareChangesPanel`) |
| `diffedit/HunkCheckboxDiffExtension.kt` + `diffedit/ClickableDiffGutter.kt` (plugin-wide `diff.DiffExtension`, gated to a no-op unless the request carries the hunk-checkbox session; the clickable-gutter helper is shared with the arrow extension below) | [MT-SPLIT](#mt-split), [MT-SQUASH](#mt-squash), [MT-DIFF](#mt-diff), [MT-DIFF-PREVIEW](#mt-diff-preview) |
| `diffedit/HunkArrowDiffExtension.kt` (plugin-wide `diff.DiffExtension` — fires on every diff viewer the platform creates, gated to a no-op elsewhere) | [MT-SPLIT](#mt-split), [MT-SQUASH](#mt-squash), [MT-DIFF](#mt-diff), [MT-DIFF-PREVIEW](#mt-diff-preview) |
| `ui/editor/conflict/JujutsuConflictGutterInstaller.kt` (plugin-wide `editorFactoryListener` — fires on every editor the platform creates, gated to a no-op outside a main editor on a jj-tracked file) | [MT-CONFLICT](#mt-conflict), [MT-DIFF](#mt-diff), [MT-DIFF-PREVIEW](#mt-diff-preview), [MT-DIFFBASE](#mt-diffbase) (shares the gutter), [MT-WORKINGCOPY](#mt-workingcopy) (its preview tabs open editors this listener sees), [MT-CROSS](#mt-cross) (multi-repo scoping of the repo-detection gate) |
| `vcs/diffbase/DiffbaseService.kt` (shared base-revision resolver) | [MT-DIFFBASE](#mt-diffbase), [MT-DIFF](#mt-diff) (Annotate), [MT-WORKINGCOPY](#mt-workingcopy) (gutter markers) |
| `actions/diffbase/SetDiffbaseAction.kt` (quick action) | [MT-DIFFBASE](#mt-diffbase), [MT-CTXMENU](#mt-ctxmenu) (shares `RevisionSelectorPopup`), [MT-CROSS](#mt-cross) (multi-repo submenu) |
| `actions/JujutsuMainMenuGroup.kt` ("Jujutsu" submenu in `Vcs.MainMenu`) | [MT-DIFFBASE](#mt-diffbase) |
| `jj/cli/CliExecutor.kt` `execute`/`executeBytes` (process launch, timeout and cancellation for every jj call) | [MT-DIFF](#mt-diff) (Annotate cancel), [MT-LOG-REFRESH](#mt-log-refresh) (cancelled load), plus a smoke pass of any section that runs jj |

## Fixtures

Shared setup, referenced by ID instead of repeated inline. Each fixture is a runnable script
under `scripts/fixtures/` rather than an embedded snippet — run it, then open the printed
directory as a project in the plugin IDE (`./gradlew runIde`).

| ID | Script | Creates |
|---|---|---|
| FX-STACK | `scripts/fixtures/fx-stack.sh [target-dir]` | Linear stack base → A → B → C (@ = C), for squash testing |
| FX-CONFLICT | `scripts/fixtures/fx-conflict.sh [target-dir] [marker-style] [wc-position]` | A content conflict on `file.txt` (change A rebased onto change B); `marker-style` is `git` (default), `snapshot`, or `diff` — rerun with a different style against the same repo to test all three. `wc-position` is `conflicted` (default, working copy on the conflict) or `sibling` (working copy on change B, a clean commit unrelated to the conflict — GitHub #119 / jj-idea-ct7e's repro position) |
| FX-MD-CONFLICT | `scripts/fixtures/fx-md-conflict.sh [target-dir]` | A modify/delete conflict on `a.txt` — one side deletes the file entirely, so "accept" on that side must remove it from disk, not leave an empty file; a different case from FX-CONFLICT's content conflict |
| FX-STRESS | `scripts/fixtures/fx-stress.sh [target-dir]` | A ~1084-commit repo with ~26 concurrent heads (main trunk, 20 short feature branches, 5 long branches, a 200-commit deep branch, an octopus merge, a hotfix/* cluster) for log-graph and filter stress testing. Reused as `jj-stress-test` wherever a section says FX-STRESS; don't build a separate throwaway multi-branch repo. Env knobs: `SCALE=<n>` multiplies the trunk / deep-branch / long-branch lengths (`SCALE=6` is ~7,000 commits, GitHub #69's repo size), and `WITH_REMOTE=1` pushes `main` and a few branch bookmarks to a bare repo beside the target so `remote_bookmarks()` is non-empty (a plain `jj git init` leaves it empty, which makes the log template's `hasPushedAncestor` trivially cheap and understates its real cost) |
| FX-SCALE-SET | `scripts/fixtures/fx-scale-set.sh [target-dir]` (default `~/workspace/jj-scale`; `ONLY="name ..."` builds a subset) | A ladder of repo sizes for tuning large-repo log behaviour (paged loading, idle trickle, first-page size, prefetch distance; GitHub #69): `s1k-synthetic` (~1.1k commits) and `s6k-synthetic` (~6k, `WITH_REMOTE`, the #69 reporter's shape) via FX-STRESS, plus real code cloned with `jj git clone --colocate`: `r2k-openNDS` (~2k, C), `r6k-longhorn` (~6.3k, Go), `r36k-keycloak` (~35k, Java/Maven; the IDE does Maven import and indexing on open) and `r80k-git` (~86k, C; well past the 10,000-row idle-trickle cap). Local checkouts under `~/workspace` are cloned from when present (read-only), else GitHub. Each load logs `perf: log-load`, `perf: log-edt-wait` and `perf: log-apply` lines to idea.log (grep `perf:`) |

Each script fails loudly (non-zero exit, explanatory message) if the jj version installed
produces a different topology than expected, rather than silently leaving a broken fixture —
see each script's header comment for the specific invariant it checks.

## Test Tooling

**Multiple jj versions.** Version-gated features (`jj/JjFeature.kt`, `jj/JjVersion.MINIMUM`)
need testing against both a jj new enough to support the feature and one that isn't. Keep
`jj` on `PATH` at whatever's newest (e.g. `brew upgrade jj` on macOS); pin specific other
versions alongside it with `scripts/jj-install-version.sh`:

```bash
scripts/jj-install-version.sh 0.39.0   # -> ~/.local/bin/jj-0.39.0, jj-0.39
scripts/jj-install-version.sh 0.37.0   # -> ~/.local/bin/jj-0.37.0, jj-0.37
```

Downloads the matching prebuilt release binary for the current platform from
[jj-vcs/jj releases](https://github.com/jj-vcs/jj/releases). To exercise the plugin against
a pinned version: **Settings → Version Control → Jujutsu → JJ executable path**, point it at
e.g. `~/.local/bin/jj-0.39`, then **Test**. Clear the field (or point it back at plain `jj`)
to return to default resolution. See contributing.md § "Testing against multiple jj
versions" and MT-BOOKMARK's "Version gating" checklist for the scenario this was built for.

## Known gaps

Not checkboxes — just a reminder of what's known-missing so you don't file a duplicate bug:

- **jj-idea-7d9p** — "Compare with Another Commit" missing from Details Changes Panel / Working Copy Panel.
- **jj-idea-zvzk** — "Compare with local" missing from editors for historical versions.

## Test Sections

### MT-LOG-TABLE

**Log table selection, navigation, and row interaction**

**Code:** `ui/log/JujutsuLogTable.kt`, `ui/log/UnifiedJujutsuLogPanel.kt`, `ui/log/JujutsuColumnManager.kt`, `ui/log/JujutsuLogContextMenuActions.kt`, `ui/log/LogClickTarget.kt`, `ui/components/TextCanvas.kt`, `ui/components/LogEntryText.kt` (`appendBookmarks`, `bookmarkRefChips`, the `@git` filter), `ui/log/JujutsuLogTableDnD.kt`, `ui/dnd/` (payload/target model, zone geometry, guards, dispatch), `ui/log/JujutsuCustomLogTabManager.kt`, `actions/top/OpenJujutsuLogTabAction.kt`
**Also re-run:** MT-LOG-DETAILS (issue-tracker link rendering is shared with the details panel)

#### Opening the log

- [ ] With the Version Control tool window closed or hidden, **VCS → Jujutsu → Open Jujutsu Log** opens it on the Jujutsu log tab. With the tab already open but the tool window hidden, re-invoking it (menu, VCS Operations popup, or its shortcut) brings the tool window forward and selects the existing tab, with no second tab (GitHub #118)
- [ ] The VCS Operations popup (Ctrl/Cmd+`) lists "Open Jujutsu Log" near the top of the Jujutsu section. In a plain Git project there is no "Jujutsu" submenu and no "Open Jujutsu Log" entry

#### Row order and selection

- [ ] Single-repo log rows follow `jj log` order (compare the top ~50 rows with `jj log -r 'all()'`), and paging never reshuffles rows already shown
- [ ] Single-click, Shift+click range, Ctrl/Cmd+click non-contiguous, Up/Down, Page Up/Down and Home/End all select as expected
- [ ] Changing or clearing each filter (reference, author, date, text, root) while the selected commit stays visible keeps it selected and keeps the details panel populated, with no flicker to empty. A filter that hides the selected commit clears the selection and shows the details panel's empty state
- [ ] With the window narrow and scrolled right, selecting a commit (click, arrows, ref-chip click, details-panel parent link, or a log refresh) does not reset the horizontal scroll
- [ ] Clicking a parent or change-id link in the details or working copy panel selects that commit in the log and it stays selected (no flicker); if it is beyond the log limit, the log expands to include it
- [ ] Select a change outside the loaded window, then `jj abandon` it (or squash it into its parent) in a terminal and refresh twice: no "Uncaught exception" dialog, the selection is simply dropped, and selecting another off-window change still expands normally (GitHub #76)
- [ ] Scroll partway down, **Accept side** on a conflicted file, then **Undo**: the log selects and shows `@`, and loading more history afterwards does not snap back to it
- [ ] Ctrl/Cmd+C copies the change ID (or the file path when a file is selected); Delete abandons the selected change after a confirmation; F2 opens the describe dialog

#### Show Diff: Enter and double-click

- [ ] Enter on a selected commit, and double-clicking a commit row (outside a chip or the root gutter), open that commit's diff
- [ ] Double-clicking a bookmark or tag chip does nothing (chips have no left-click action, only a right-click menu). Double-clicking the "+N more" chip shows the hidden-refs popup without opening a diff
- [ ] Double-clicking the root gutter column (multi-repo) toggles expansion, with no diff
- [ ] Rebinding "Show Diff" in Settings → Keymap (to another jj action, or cleared) changes what both Enter and double-click do

#### Hover tooltips

**Code:** `ui/components/IconAwareTooltip.kt`

- [ ] Hovering a row shows the tooltip after about half a second (like a toolbar button), not instantly; also in Rider, and in IDEA with registry `ide.tooltip.initialDelay` set to 0 (GitHub #144)
- [ ] With an Issue Navigation pattern configured so a row's tooltip holds a link, hover until it appears and move the pointer into it: it stays open. The issue link opens the browser, the author's `mailto:` link opens the mail client, and click-drag selects text instead of dismissing it
- [ ] Moving the pointer sideways or away (not towards it), or onto another tool window or editor, dismisses it. Moving between adjacent rows without entering the tooltip updates its content to each row
- [ ] Scrolling the log (wheel, scrollbar drag, Page Up/Down) with the pointer still hides the tooltip at once and it does not reappear until the pointer moves, then it shows the *new* row under the pointer

#### Columns and View Options

- [ ] No blank header row above the table (GitHub #78); it starts directly under the toolbar
- [ ] Resize a column by dragging its border in the rows; reorder it by dragging horizontally. A mostly-vertical drag does not start a reorder, and on a commit row starts a commit drag/drop instead. Double-clicking a border does not auto-fit
- [ ] The root gutter column (leftmost) can't be resized or reordered. The Date column (rightmost) resizes by its right edge like the others and is not noticeably narrower than the other fixed columns with Fit Columns to Window Width on
- [ ] Column widths and visibility persist across IDE restarts
- [ ] The eye-icon **View Options** button is the only view button (no separate Columns or Details Position buttons). Its popup has a "Columns" header (per-column toggles, a separator, then Fit Columns to Window Width) and a "Details" header (Right/Bottom), followed by Alternating Row Colors, Hover Tooltips and Bookmark Ahead/Behind Counts, all checked by default. Each toggle takes effect without a restart, and Details Right/Bottom moves the details pane
- [ ] Unchecking **Hover Tooltips** stops row tooltips immediately and re-checking restores them. The one global setting also applies to file-history tabs, the Working copy tool window and the bookmarks panel's tooltips
- [ ] Unchecking **Bookmark Ahead/Behind Counts** removes the ↑n↓m arrows at once from the log's bookmark chips and the bookmarks panel leaves (a collapsed remote folder with unsynced bookmarks still shows just a `•`; the hover tooltip still shows the counts). Re-checking restores them. The setting survives a restart and applies to every project (GitHub #128)
- [ ] Unchecking **Alternating Row Colors** makes log rows uniform, and the Duplicate, Squash-into and Rebase pickers too when next opened. Re-checking restores striping everywhere, including tables opened while it was off
- [ ] **Align Status and Change ID Columns** (off by default): off, a description follows the id directly. On, in a repo with varying unique-prefix lengths (ideally with a divergent `/N` id, the bold `@` row and immutable/conflicted commits), every description starts at the same x and the immutable/conflict icons share one status column sized to the widest icon run on screen (rows without icons keep the id and description x unchanged). Open logs update at once, hiding Status or Change ID removes that padding, turning it off removes the padding at once, and issue links still hover and click correctly (GitHub #91)
- [ ] **Fit Columns to Window Width** is checked on a fresh tab. Narrowing the window shrinks the description column and narrows and ellipsizes author and date before any horizontal scrollbar appears; docking the details pane to the right re-fits the table; widening restores the columns.
- [ ] Toggling Fit off and on switches between responsive and manual immediately and persists across closing and reopening the tab. With Fit on, a manually widened author column keeps its width through narrowing and widening, and persists across reopening the tab

#### Issue-tracker links

Setup: Settings → Version Control → Issue Navigation, add a pattern (e.g. `[A-Z]+-\d+` → `https://example.com/browse/$0`). This is the one full check; MT-LOG-DETAILS and MT-BOOKMARK only cover what differs on their surface.

- [ ] A description containing a match (`Fixes JIRA-123`) shows it link-coloured, not underlined at rest. Hovering just the reference shows a hand cursor and underlines only that word; elsewhere in the description the cursor is the default. Left-click opens the URL in the browser, and right-click shows a single "Open <url>" action. The row tooltip renders it as a link too
- [ ] With the description column narrowed so text truncates, a reference before the truncation point is still a link, and one after it is dropped with the rest of the truncated text, not left half-rendered
- [ ] With no patterns configured the description renders as plain text, with no link and no hover cue
- [ ] A bookmark or tag name containing a match (`jira-123-fix-thing`) renders the reference link-coloured inside the chip, with the rest of the chip plain. Hovering just the reference shows a hand cursor and underlines only it; hovering the rest of the chip shows no hand cursor. Left-click on the reference opens the browser and elsewhere on the chip does nothing. Right-click on the reference shows the single "Open <url>" action, and right-click elsewhere on the chip shows the usual bookmark/tag menu. Same for a tag; a name with no match is unaffected

#### Description word spacing and copying

- [ ] A long multi-word description wraps at word boundaries in the description column, the details panel and the tooltip, not only at the column edge (GitHub #77)
- [ ] Text selected with the mouse in any of those, copied and pasted into a plain text editor, has ordinary spaces (not non-breaking spaces). Copy Description copies the description exactly as typed

#### Author, committer and chip click targets

Author and committer names are left-click hyperlinks; right-click opens a menu with the default action highlighted. Bookmark and tag chips have **no** left-click action (right-click only) and show a grey hover background with no hand cursor, so an issue reference inside a chip name can be its own link.

- [ ] Author and committer names are link-coloured with no underline at rest; hovering the name adds the underline and a hand cursor, and moving off removes the underline. Blank cell space to the right of a short name neither underlines, shows a hand cursor nor launches anything
- [ ] Left-clicking an author or committer name opens the OS mail client addressed to them. Right-clicking an author shows **Send Email to …** highlighted, a separator, then **Filter Log by …**; right-clicking a committer shows only **Send Email to …**
- [ ] **Filter Log by …** narrows the log to that author (the Author chip updates) and closes the menu; choosing it again clears the filter. It shows a checkmark exactly when that author is the active filter
- [ ] Hovering a bookmark or tag chip shows a grey background across the chip's whole width (left edge, middle, right edge) and no hand cursor; the bookmark/tag accent colour stays visible on top. The highlight covers only that chip, not the gaps or a neighbouring chip. Left-click does nothing
- [ ] Right-clicking a chip opens a menu with **Filter Log to '…'** highlighted, then a separator and rename/delete/forget/move/advance/track actions (Advance: MT-BOOKMARK; a tag also gets **Push Tag**: MT-GIT). Choosing Filter applies it and closes the menu; choosing it again clears it. It shows a checkmark exactly when that reference is the active filter
- [ ] The "+N more" chip shows a hand cursor and the same grey highlight on hover; left-click opens its popup of hidden refs, each openable via its own submenu
- [ ] In a colocated repo a bookmark shows only its local chip and any real-remote chips, never an `@git` chip, and the "+N more" count and popup exclude `@git` too, matching the bookmarks panel (GitHub #120)
- [ ] `jj new` off a bookmarked change without moving the bookmark: the new unbookmarked head's row shows a small slashed-bookmark marker after its (absent) chips; `jj bookmark set <name> -r <that change>` removes it after auto-refresh; a bookmarked change, or an ancestor of one, never shows it (bookmarks panel side: MT-BOOKMARK "Unbookmarked heads"). Narrowing the column until chips collapse into "+N more" leaves the marker uncollapsed after the overflow chip, like the `@` marker (GitHub #107)

#### Immutable merges: emptiness resolves in the background

Fixture: FX-SCALE-SET `r80k-git` (about 40% of rows are merges, most of them empty).

- [ ] First load shows the page in well under a second, with immutable merge rows showing a greyed "(…)" in place of "(empty)". Within about a second the visible rows' "(…)" turn into "(empty)" (most merges) or disappear (a merge that changed something, an "evil" merge); none stays on "(…)". Scrolling quickly to a deep page resolves the on-screen rows first
- [ ] Selecting an immutable merge that still shows "(…)" shows "Checking whether empty…" in the details status line, then "Empty" (or the item goes away if the merge is not empty)
- [ ] A mutable merge (`jj new a b` on two of your own changes) shows "(empty)" immediately. Non-merge and mutable rows never show "(…)"

#### Drag and drop: core

Operation-specific drops are under MT-DND.

- [ ] Dragging from a commit row starts a drag (drag/reject/drop cursor as appropriate); releasing over an unsupported target (a copy-modifier drag, or dropping on a bookmark chip) changes nothing in the log or repository
- [ ] Dragging near the bottom edge of a long log auto-scrolls the table as the pointer approaches the edge
- [ ] The selection highlight stays exactly as it was when the drag started for the whole gesture; it does not follow the row under the pointer, which would fight the drop-zone indicator. Click, Shift+click, Ctrl/Cmd+click, double-click, right-click (row and chip) and author/committer hover links all still work with drag and drop installed

#### Palette readability

- [ ] In a light theme (IntelliJ Light), bookmark chips (including the gold, GitHub #51), tag chips and the `@`, conflicted and divergent markers are readable against the row, and also against a selected row. Switching back to a dark theme (Darcula) shows nothing washed out

### MT-LOG-GRAPH

**Graph rendering**

**Code:** `ui/log/JujutsuCommitGraph.kt`, `ui/log/JujutsuGraphAndDescriptionRenderer.kt`, `ui/log/graph/DataStructures.kt`, `ui/log/graph/LayoutCalculator.kt`

- [ ] Graph lines render correctly for linear history, merges and branches; the working copy (`@`) indicator is visible; colours differentiate branches; the graph column auto-sizes to content
- [ ] On a commit with ~30 bookmarks (`for i in $(seq 1 30); do jj bookmark create bm-$i; done`), the row still shows description text (not blank) and the bookmarks collapse behind a "+N more" chip rather than overflowing the cell. The chip keeps a coloured bookmark icon so the row still reads as a branch head: the tracked-bookmark glyph if every hidden bookmark is tracked (all local bookmarks count as tracked), the plain glyph if any hidden bookmark is an untracked remote, and the green tag glyph when the overflow is tags only
- [ ] That row's tooltip lists every bookmark, including those behind "+N more", wrapping across lines and showing the full description without clipping at the screen edge; content taller than the screen scrolls instead of clipping
- [ ] Left- or right-clicking the "+N more" chip opens a popup listing the hidden bookmarks, each as a sub-menu with the usual bookmark actions (Rename…, Delete, Forget, …). With a large hidden count (~50+, e.g. via FX-STRESS) every sub-menu's actions are enabled and clickable, and each sub-menu is labelled with the same coloured bookmark (tracked/plain) or tag glyph its own chip would show

#### Concurrent branches and unresolved parents

Use FX-STRESS with Settings → Version Control → Jujutsu → Log Limit set to 200 so several branches fall out of view, then apply an author or date filter to shrink the visible set further.

- [ ] Tree lines never cross over unrelated commits or share a lane
- [ ] A commit whose parent is beyond the log limit (not loaded at all) shows a **faded straight line** down from its circle instead of a connector. A commit whose parent is loaded but hidden by the active filter shows a **wiggly line** instead, but only when that hidden parent has no visible ancestor at all; otherwise it is joined to its nearest visible ancestor by a full-length wavy edge (see below)
- [ ] A merge with one loaded parent and one unresolved parent shows its real connector plus a stub in its own lane. Clearing the filter restores the graph immediately, without a manual Refresh

#### Elided edges in a filtered log (GitHub #131)

Reuse FX-STRESS with `WITH_REMOTE=1`.

- [ ] With the search box in revset mode, filter `main | @ | remote_bookmarks()`: `@` and `main` are joined by a **wavy** edge in the lane colour (vertical stretches wave; the diagonals into and out of a commit are straight), not left as unrelated rows. Edges between directly adjacent visible commits stay solid
- [ ] Hovering a wavy edge shows "Revisions hidden by the current filter" followed by the target and row distance; clicking a long one navigates to the ancestor row
- [ ] An author filter that hides a commit in the middle of a branch joins its child to the nearest remaining ancestor by a wavy edge; a merge with one hidden parent shows one solid and one wavy edge
- [ ] Hiding every ancestor of a commit (filter to just that commit) leaves the short wiggly stub that ends at nothing, not a full-length wavy edge to another row
- [ ] Clicking a faded stub to load more rows while the filter is active recomputes wavy edges for the new rows (none stale, none missing); clearing the filter removes all wavy edges

#### Long-edge collapsing

Reuse FX-STRESS (its 200-commit deep branch and long branches have edges well over 50 rows).

- [ ] An edge spanning 50+ rows shows as a short line from its child with a **down arrow** one row below, and a short line into its parent with an **up arrow** one row above; nothing is drawn between the two
- [ ] An octopus merge whose parents are all far away fans out compactly: its first line drops straight down from the circle, and the rest use the nearest free columns (including one whose arrow from the commit above ends on the merge's row), with no gap of empty columns
- [ ] Arrow tips stop short of the row's centre (down arrows above it, up arrows below it) and never touch a circle or a connector leaving the middle of the row
- [ ] The column a long edge used is reused by other branches in the rows between the caps, so the graph is visibly narrower there than just outside the long edge
- [ ] Hovering either cap thickens **both** caps and shows the directional cursor even when both ends are on screen; the tooltip names the target and the row distance. Clicking either cap jumps to the other end (down arrow → parent, up arrow → child), loading it first if needed
- [ ] Scrolling through a long edge's middle with the paged log shows no stray vertical line and no shifting of other lanes as the arrows scroll in and out of view

#### Long-edge navigation

Reuse FX-STRESS with `SCALE=6 WITH_REMOTE=1`, per MT-LOG-REFRESH's "Paged log loading" fixture.

- [ ] Hovering a long line whose child (upper) end is visible always points **down**, toward the invisible parent; one whose parent (lower) end is visible always points **up**. Direction depends only on which end is off-screen, never on where within the row you point
- [ ] When *both* ends are off-screen (scrolled to the middle of a very long branch), direction depends on which half of the **viewport** the row is in: top half points up, bottom half points down. Moving the mouse gradually from one visible row to the next changes direction **at most once**, at a single stable transition row near the viewport's middle
- [ ] The active direction thickens the side of the line that *is* the navigation target and shows a directional resize cursor (↑/↓); the other side stays plain weight (not dimmed or translucent). The tooltip names the target commit and how many rows up or down it is
- [ ] When one end is visible, the thickened treatment runs the **entire** way to that end's circle with no gap or thin segment, including where the connector is a diagonal (into or out of a merge commit)
- [ ] Scrolling (wheel, scrollbar, keyboard) while hovering a long edge without moving the mouse updates the thickened span and direction to the new viewport immediately
- [ ] No stray vertical segments appear elsewhere while hovering. A merge with 3+ parents is the sharpest test: hover each connector individually and confirm nothing appears in an unrelated lane and the thickening stops exactly at a visible end's circle instead of bleeding into whatever edge reuses that lane below or above it
- [ ] Hovering a **faded straight stub** (an unresolved parent not yet loaded) shows a directional-down cursor and a "Parent not loaded yet — click to load" tooltip; clicking it loads and scrolls to reveal that parent, as if you had scrolled to trigger the load
- [ ] **Stub auto-load** (jj-idea-ytvh): with a filter whose recent match has its parent match on a later page (e.g. a sparse author filter) on a large repo, leave the view at the top; the faded stub resolves into a real connector by itself, with no scrolling or clicking, and paging then stops (no further `perf: log-page` lines in idea.log). A row whose parent the filter excludes (wiggly line, or a single-commit revset) stays as it is and triggers no repeated jj calls
- [ ] With a filter that hides an ancestor so its row shows the **wiggly line**, hovering shows a tooltip naming what's hidden but the cursor stays the plain arrow, and clicking does nothing (clearing the filter is the only way to reveal it)
- [ ] A short edge whose ends are both on screen gets no hover treatment. Double-clicking a long edge navigates like a single click, without also triggering the row's Enter-bound action (e.g. Show Diff)
- [ ] The graph column's text indent (where the description starts) looks unchanged on both a linear history and a wide multi-branch view

#### Graph layout under filtering

→ automate: jj-idea-2k2b (layout re-alignment under filtering is a deterministic
`LayoutCalculator` computation, testable without rendering)

Reuse FX-STRESS rather than building another throwaway multi-branch repo.

- [ ] Typing a text filter that hides some rows redraws the graph for the **visible** rows only: lines do not extend to hidden commits, and there are no misaligned passthrough lines across the remaining rows. Clearing it returns the full layout immediately, with no stale passthrough lines
- [ ] An author, date or root filter on a multi-branch repo gives the same result
- [ ] Rapid typing (several characters quickly) converges to a single correct layout within ~250 ms, without flickering per keystroke

### MT-LOG-DETAILS

**Commit details panel**

**Code:** `ui/log/JujutsuCommitDetailsPanel.kt`, `ui/components/HtmlTextCanvas.kt`, `ui/components/TextCanvas.kt`, `ui/log/JujutsuLogContextMenuActions.kt`, `ui/log/LogClickTarget.kt`, `actions/filechange/FileChangeActionGroup.kt`
**Also re-run:** MT-DIFF-PREVIEW (details changes panel shares the preview-tab behavior); MT-LOG-TABLE (change-id link click handling and the file-change context menu are shared with the log table / working copy panel)

- [ ] Selecting a row shows the panel with author, date and change ID. The splitter position persists, and the panel toggles between right and bottom
- [ ] The author line is a single `Name <email>` mailto link, link-coloured with no underline at rest and an underline on hover. When the committer differs, a "committed by Name <email>" line also appears with its own mailto link. Narrowing the panel (or a long bot name/email) wraps `Name <email>` and `· date, time` each as a whole, never mid-email or mid-date, with a visible gap between them
- [ ] The description renders HTML formatting
- [ ] On a commit with several long or hyphenated bookmarks (`hotfix/issue-123`, `feature/long-name-here`), narrowing the panel wraps the bookmark line only between bookmarks, never inside a name or between its icon and text
- [ ] Hovering a bookmark or tag chip shows a grey background and no hand cursor, across the whole chip width including near its right edge. Left-click does nothing. Right-click opens the same menu as the log table (Filter Log to '…' with its checkmark, plus rename/delete/forget/move/track)
- [ ] Right-clicking an author or committer email opens the same menu as the log table (Send Email to …, plus Filter Log by … for the author)
- [ ] Right-clicking a parent or change-id link (e.g. a merge's parent) opens that commit's full log-row menu (Show Diff, New/Edit, Describe, Abandon, Rebase, …) as a highlighted-default popup list; left-click navigates and selects that commit. If the target has become invalid (abandoned in a terminal, link not refreshed) the menu is empty rather than throwing
- [ ] Right-clicking one or more files in the changed-files list of a **historical** commit → **Restore to This…** opens the Restore dialog (MT-WORKINGCOPY "Restore dialog" has the full checklist) listing the files that differ between that commit and `@`, with the right-clicked files pre-checked. Confirming restores exactly the checked files via `jj restore -f <that revision>` (GitHub #84)
- [ ] In a **mutable** historical commit that changed 3 files, multi-select 2 → **Drop Changes from This…** opens the "Drop Changes" dialog with those 2 pre-ticked (summary "Changes to 2 files will be dropped"). Confirming leaves only the third in `jj diff -r <it> --name-only`, rebases descendants, and the "Drop Changes" balloon's **Undo** restores both files in one step. The item is hidden for an immutable commit and for the working copy, and visible but disabled (tooltip "Select a single commit…") when several commits are selected in the log (GitHub #148)

#### Issue-tracker links

Setup and the full link checks are in MT-LOG-TABLE → Issue-tracker links; this covers what differs here.

- [ ] A selected commit's description reference renders link-styled (underlined) and opens the browser on click. A bare `https://…` URL is clickable too. A description with no match, or no patterns configured, is plain text
- [ ] A bookmark or tag name containing a match renders the reference link-coloured within the chip. Hovering just the reference shows a hand cursor and underline with no background highlight (the two hover cues don't overlap); left-click opens the browser; right-click on the reference shows the single "Open <url>" action, and elsewhere on the chip shows the usual bookmark/tag menu. A name with no match shows the grey chip highlight on hover and the usual menu, with no link

#### Icons and chips at non-default zoom

Repeat at **View → Appearance → Zoom IDE** 85%, 150% and 200%, then reset to 100%. Use a commit with a local and a remote bookmark (one divergent, e.g. ↑1), a tag, an immutable parent and an author/committer email. Compare each surface with the same commit's row in the log table.

- [ ] In the details panel the repo line's icon, the chips' icons and the immutable/conflict icons are the same size as in the log table, centred on their text line, with no overlap and no extra gap before the following text. Chip names and the ↑/↓ divergence scale with the zoom like the surrounding text (slightly smaller, as in the log table)
- [ ] The "+N" overflow chip, the `@` marker and the dangling-head marker line up the same way; the row tooltip and the bookmarks panel (tree and its tooltips) show the same alignment
- [ ] Hovering a chip highlights exactly the chip; hovering the author's `Name <email>` underlines exactly it; an issue-link substring inside a chip name underlines only itself

#### Copying text

Use the **keyboard** shortcut (Cmd/Ctrl+C) throughout: the IDE routes it through its own Copy action, not Swing's, so that is the path that must carry the chip text (a context menu has no Copy here).

- [ ] Drag-select from plain text before the author line to past the date (start on plain text, not on a chip), copy, and paste into a plain text editor: the text includes `Name <email>` and the date
- [ ] Select All (Ctrl/Cmd+A), copy and paste: bookmark and tag chip names (with any ↑/↓ divergence) appear, and icons contribute nothing. (The on-screen highlight skips chips and the author line, whose selection highlight has zero width — a known limitation — but the clipboard has them)
- [ ] A commit with an issue reference in its description and bookmark name (`Fixes JIRA-123`, `jira-123-fix-thing`) copies as plain text, with no URL or markup. The description pastes with ordinary spaces; the only non-breaking spaces are the deliberate gaps between chips and around the `·` before the date
- [ ] Row tooltip: select text with the mouse (not from a corner of the tooltip, which dismisses it), then copy: the text includes its chips' text. The tooltip closes on the key press (it dismisses on any key, so Cmd/Ctrl+A in it just closes it); the copy still happens

#### Changes tree

- [ ] The tree shows the commit's changed files. Preview-tab behaviour (double-click, Enter, tab-swap, Escape, Cmd/Ctrl+D, F4) is MT-DIFF-PREVIEW
- [ ] Open File on a historical commit opens that **revision's** content (before and after selecting files in the tree); on the working copy it opens the editable file
- [ ] Right-clicking a file of a historical commit shows "Compare Before with Another Commit…", hidden for the working-copy entry and for a root commit
- [ ] **Show History** appears in a file's right-click menu and opens that file's history tab. It works from the details panel, the Working Copy tool window's tree and a compare-changes panel (e.g. "Compare with Another Commit…"), and for a **deleted** file (it uses the last-known path). Selecting several files of a historical (non-`@`) commit disables or hides it rather than acting on just one
- [ ] With `@` selected in the log, selecting files in the details tree makes the IDE's Reformat Code (Ctrl/Cmd+Alt+L) and Optimize Imports (Ctrl/Cmd+Alt+O) act on them on disk, Jujutsu → **Annotate** opens the gutter annotations, and F4 opens the file via **Jujutsu.OpenChangeFile**. With a historical commit selected, Reformat Code, Optimize Imports and Annotate do nothing, or act on the editor's current file, never silently on the historical revision or the working-copy file

### MT-LOG-FILTER

**Toolbar, filters, and reference filter**

**Code:** `ui/log/JujutsuFilterComponent.kt`, `ui/log/JujutsuAuthorFilterComponent.kt`, `ui/log/JujutsuDateFilterComponent.kt`, `ui/log/JujutsuReferenceFilterComponent.kt`, `ui/log/JujutsuRootFilterComponent.kt`, `ui/log/JujutsuPathsFilterComponent.kt`, `ui/log/JujutsuRevsetFilterComponent.kt`, `ui/log/RevsetFilter.kt`, `ui/log/LogFilterRevset.kt`, `ui/log/FilterPageCursor.kt`, `ui/log/LogFilterMatcher.kt`, `ui/common/FilterPriorityLayoutStrategy.kt`, `ui/common/CommitTablePanel.kt`, `ui/log/UnifiedJujutsuLogPanel.kt` (primaryActions), `actions/change/DescribeChangeAction.kt`, `actions/change/RebaseChangeAction.kt`

#### Toolbar & filters

- [ ] Refresh reloads data; text search filters in real time; the regex and case-sensitivity toggles both work
- [ ] The Author dropdown lists all authors and restricts visible entries when applied; the bookmark/reference and date filters each restrict correctly
- [ ] Clear filters (X) resets all filters; multiple active filters combine with AND

#### Toolbar buttons and shortcuts

- [ ] **New**, **Edit**, **Rebase** and **Describe** icon buttons sit at the left of the main log toolbar, in that order and before Refresh, each with a tooltip. **Edit** (Ctrl/Cmd+Shift+E), **Rebase** and **Describe** have default shortcuts (Settings → Keymap: "Jujutsu.EditChange", "Jujutsu.RebaseChangeToolbar", "Jujutsu.DescribeChangeToolbar"); with a log row selected and the table focused, pressing them does what clicking does. (Ctrl/Cmd+Shift+E soft-conflicts with the IDE's "Recent Locations" outside the log table; a keymap conflict warning is expected there, not a bug)
- [ ] **Edit** on a selected mutable non-working-copy change moves the working copy to it (it becomes `@`) and the log reselects it; it is disabled for the working-copy change and for immutable commits
- [ ] **New** on a selected change creates a new empty change on top of it and it becomes `@`; with no selection it stacks on the working copy. Clearing the selection (Ctrl/Cmd-click the selected row) greys **New** out rather than removing it from the toolbar
- [ ] **Describe** on a selected mutable change opens the same dialog as the right-click Describe entry; it is disabled for an immutable change or no selection (greyed out, still in place)
- [ ] **Rebase** on one or more mutable changes in the same repo opens the same dialog as the right-click Rebase entry, pre-populated with the selection as source. It is disabled for only-immutable selections and for a multi-selection across two repos (no arbitrary repo is picked)
- [ ] Right-clicking a log row shows the keyboard shortcut hint next to **Rebase** and **Describe** (e.g. "Ctrl+Shift+R"), matching the toolbar. Multi-selecting several mutable rows (same repo) enables **Rebase** with all of them as source (disabled across two repos) and disables **Describe**
- [ ] Right-clicking a change-id link (e.g. a parent reference in the details panel) gives a menu that acts on the link's target, not the log selection (select a *different* row first to confirm): **New Change**, **Edit**, **Describe** and **Rebase** show their shortcut hints, and **Move Up**/**Move Down** are shown disabled
- [ ] A file's history tab (right-click a file → Show History) shows only Refresh and search in its toolbar: no New, Edit, Rebase or Describe buttons

#### Narrow-width toolbar

- [ ] Shrinking the log tool window narrower and narrower keeps New, Edit, Refresh, Fetch, **Push**, Columns and Details-position buttons visible and clickable at every width, never pushed off-screen. The search field shrinks to a minimum width and stops (it doesn't shrink to zero or overlap the buttons)
- [ ] Narrowing further hides filter chips (Reference, Author, Date, Root) one at a time and shows a "»" overflow chevron; clicking it lists all filters, including the hidden ones, and they are fully usable from there (clicking one opens its dropdown). With no filters applied, filters hide in trailing (rightmost) order first
- [ ] A filter with a value applied (e.g. a bookmark selected in Reference) stays visible while unapplied filters hide ahead of it, even if it isn't leftmost; whatever remains keeps its original left-to-right order. Widening brings hidden filters back and removes the chevron once everything fits

#### Search box revset mode

**Code:** `ui/components/LogSearchField.kt` (`revsetMode`, `setError`, collapse chip), `ui/log/RevsetFilter.kt` (`conciseRevsetError`), `ui/log/UnifiedJujutsuLogPanel.kt`
(`applyFilterNow`), `settings/LogWindowConfig.kt` (`searchRevsetMode`)

Use FX-STRESS.

- [ ] The log tab's search box has a 4th toggle (revset) after Regex / Match Case / Words; turning it on greys out those three and changes the placeholder to a revset example. Turning it off searches the same text as plain text again, with any red outline gone. The toggle state survives an IDE restart, per tab
- [ ] With it on, `heads(::@)` shows only the matching rows (after the "Filtering…" pause); Enter applies at once. The revset ANDs with the Scope chip and the other chips (add an Author chip and only that author's matches remain)
- [ ] Typing `heads(` gives the whole box a red outline and a one-line hover message (e.g. "Failed to parse revset: Syntax error: expected … (column 7)") with no `|` snippet and none of the scope/chip terms. The status bar shows the same short text as "Search revset error", and the previous rows stay (the log is not emptied). Fixing the text clears the outline and tooltip
- [ ] With a bad *scope* and a valid search, the search box is not outlined (the status bar names the error)
- [ ] Narrowing the tool window until the box would leave only a few characters to type collapses it to a compact chip (search icon + text, red text on an error). Clicking the chip pops out the full box with the toggles; Enter applies and closes it, Esc closes it, and the text and toggles are kept. Widening brings the inline box back
- [ ] The Rebase, Squash Into… and Move Bookmark to Change pickers and file history have no revset toggle

#### Search by Git commit hash

→ automate: jj-idea-4u7j (hash-prefix matching, filter AND-combination, and regex/case
toggles are pure `LogFilterMatcher` logic, testable without rendering)

- [ ] Pasting the full 40-character Git commit hash of a visible commit (e.g. from `jj log -T commit_id`) into the search field leaves that row as the only result. An abbreviated prefix still matches, and so does a different case (case-insensitive by default)
- [ ] A hash for a commit not currently loaded appears as the only row after a brief "Filtering…", with no Enter needed (see Whole-history filtering below)

#### Whole-history filtering

**Code:** `jj/LogSearchRevset.kt`, `ui/log/LogFilterRevset.kt` (`compileLogFilterRevset`),
`ui/log/FilterPageCursor.kt`, `ui/log/UnifiedJujutsuLogDataLoader.kt` (`applyFilter`,
`loadMoreFilter`, `rewalkFilters`), `ui/log/UnifiedJujutsuLogPanel.kt` (`scheduleFilterApply`),
`ui/log/JujutsuLogTable.kt` (`revsetDriven`, `filterState`), `ui/common/CommitTablePanel.kt`
(status bar), `ui/log/LogFilterMatcher.kt` (file history only)

In the main log tab every filter except Root (search text, Reference, Author, Date and the Scope chip) is compiled into one revset per repo and resolved by jj, so it covers the whole history, not just the loaded window. Matches arrive a page at a time. File history keeps client-side filtering of the loaded rows (nothing here applies to it).

→ automate: `LogFilterRevsetTest`, `RevsetLogFilterLoaderTest`, `FilterPageCursorTest` and the
real-jj `FilterPageCursorContractTest` cover compilation, paging and the table model; this section is
for the end-to-end wiring that only shows up when jj actually runs.

Setup: `scripts/fixtures/fx-stress.sh`'s ~1084-commit repo (`jj-stress-test`); the first page is only 500 rows, so most commits are off-window.

- [ ] Pasting a hash or change-id well past the first page (e.g. from `jj log -r 'all()' -T commit_id --no-graph --limit 2000`) shows it as the only visible row after a brief "Filtering…", with no Enter needed
- [ ] A word that appears only in an old commit's description **body** (not its first line) finds that commit (full-body matching). Gibberish that matches nothing empties the table and the "Filtering…" hint goes away
- [ ] While typing a longer word the table holds its previous rows until you pause (~300 ms); **Enter** applies immediately
- [ ] Free text with punctuation (`fix: the . thing`) does not error (check `idea.log`); it searches description/author as usual
- [ ] A search matching more than one page of commits (e.g. a single letter): scrolling to the bottom loads more matches with no repeated rows and none skipped (compare the count with `jj log -r 'description(substring-i:"x")' --no-graph -T commit_id | wc -l` once fully scrolled)
- [ ] Search + Author + Date together show exactly the commits matching all three
- [ ] Refresh, or a change made in a terminal, re-fetches the filtered view and keeps its scroll depth; clearing the search returns to the configured revset/limit
- [ ] In a multi-repo project, a hash from a second repo merges results from both roots with the root gutter correct, and the **Root** filter still hides and shows roots instantly
- [ ] A match whose parent is not itself a match (sparse results) shows a faded "not loaded" stub edge rather than a wrong connection; clicking the stub loads its target
- [ ] A file's history tab filters the already-loaded rows instantly, with no "Filtering…" hint

#### Reference filter (bookmark/tag dropdown)

Use a repo where the log limit is **smaller** than total history, with at least one bookmark and one tag **beyond** the limit. FX-STRESS at Log Limit 100 works: `main`, `release-1.0`/`release-2.0` and the `v1.0` tag all sit deep in history, and `main`'s ancestry is immutable.

- [ ] The dropdown lists **all** local bookmarks (gold bookmark icon, narrower than the tag icon) and **all** tags (green tag icon), including those beyond the log limit. The bookmark, tag and "@" icons are visibly distinct and coloured like the log table's
- [ ] Opening the dropdown very early after opening the project shows a single disabled "Loading bookmarks and tags…" entry instead of looking empty; reopening after loading shows the real list. For a repo with no bookmarks or tags it ends up empty rather than stuck on the placeholder
- [ ] With a remote bookmark left **untracked** (clone, push a bookmark from another clone, don't `jj bookmark track`; `jj bookmark list --all-remotes` shows it untracked), the dropdown lists it as `name@remote` with an icon distinct from local bookmarks. A local bookmark whose remote is synced appears only once, as the plain name
- [ ] The currently-selected reference shows a checkmark next to its icon and no other row does; hovering or moving the keyboard selection does not move it
- [ ] Creating or deleting a bookmark or tag in a terminal updates the dropdown after the auto-refresh (see MT-LOG-REFRESH), without Refresh or saving a file
- [ ] Selecting a reference on a loaded row filters the log to that commit and its ancestors and closes the dropdown. Selecting one whose target is **outside** the first page (including a remote-only bookmark) shows that commit and its **complete** ancestry after a brief "Filtering…" (the true `::ref`, resolved by jj, nothing expanded first); scrolling loads more of it. Never a silent empty result. Selecting "@" filters to the working copy and its ancestors
- [ ] Reopening the dropdown while a filter is active scrolls to and highlights the selected reference. Arrow up/down moves the highlight; Enter applies it and closes the dropdown. Clearing the filter restores the full (limited) log

#### Multi-repo scoping

Open a multi-root project with at least two independent (non-colocated) jj repos; FX-STRESS alongside one other repo works.

- [ ] Filtering to a bookmark that exists in only one repo shows **only** that repo's ancestry, with no other repo's root commit ("zzzzzzzz", "no description", empty). The graph draws **no** connector between rows from different repos, even though every repo's root shares the same change id
- [ ] Filtering to "@" shows **every** repo's working copy, each with its own ancestry

#### Save a revset as an alias

**Code:** `jj/cli/RevsetAliases.kt`, `ui/log/SaveRevsetAliasDialog.kt`, `ui/log/JujutsuRevsetFilterComponent.kt`
(`saveAsAlias`), `ui/components/LogSearchField.kt` (`SaveRevsetAliasAction`), `ui/log/UnifiedJujutsuLogPanel.kt`
(`onSaveRevsetAliasRequested`)

Needs a throwaway user config (`JJ_CONFIG`) or remove `revset-aliases.mine` afterwards. Check results in a terminal with `jj config list revset-aliases` and `jj log -r mine`.

- [ ] The Scope popup has a **Save as alias…** link beside "Revset language"; with the field empty it does nothing
- [ ] Typing `author(exact:"you@example.com") & mutable()` in the Scope popup and clicking the link closes the popup and opens a "Save Revset as Alias" dialog with the revset (read-only), a name field, and **User config** (selected) / **Repository config**
- [ ] Name `1bad` or `a-b`: inline error, and OK does nothing. Name `mine`, User config, OK: the dialog closes, the Scope chip reads `mine`, the rows are unchanged, and `jj config list revset-aliases` shows the expression with its quotes intact. After an IDE restart the tab's scope is still `mine`
- [ ] Saving `mine` again shows an "already exists" confirmation: **No** keeps the dialog open, **Yes** overwrites
- [ ] An invalid revset (`ancestors(trunk(), 2) )` with a stray `)`) entered in the Scope popup with Enter turns the field red with a wrapped error balloon (not a truncated label). Via **Save as alias…** + OK the dialog refuses with "Not a valid revset, alias not saved: …" and no new entry appears in `jj config list revset-aliases`. The dialog's Revset field is editable: fix the expression there and OK saves it
- [ ] **Repository config** in a multi-repo project writes the alias to each repo's own config only
- [ ] Editing the alias in the config file, then changing another filter, makes the tab follow the new definition
- [ ] In the search box with revset mode on, a **Save as alias…** toolbar button appears, disabled while the box is empty and when revset mode is off. With text it opens the same dialog, and after saving the box holds the alias name and is filtered by it. The Rebase, Squash Into…, Move Bookmark to Change pickers and file history have no such button

#### Revset highlighting, completion and history

**Code:** `ui/components/RevsetTextField.kt`, `ui/components/RevsetCompletionProvider.kt`, `jj/revset/` (`RevsetLexer`, `RevsetSyntaxHighlighter`,
`RevsetFunctions`, `RevsetCompletion`), `jj/cli/RevsetAliases.kt` (`listRevsetAliases`), `ui/components/LogSearchField.kt`,
`ui/log/JujutsuRevsetFilterComponent.kt`

The Scope popup and the search box (in revset mode) are editor-based fields: they colour the revset, complete it, and underline the column where jj reports a syntax error. jj remains the only validator.

- [ ] In the Scope popup, typing `anc` offers `ancestors` (with its `(x, [depth])` signature as tail text). Accepting with Enter or Tab gives `ancestors()` with the caret **between** the parentheses and the list reopens; a no-argument function such as `mine` ends with the caret **after** `mine()`
- [ ] The first letters of a bookmark and of a tag are both offered, labelled `bookmark` / `tag`. A configured revset alias (e.g. from "Save as alias…") is offered, labelled `alias`; one added in a terminal is offered after clicking out of the field and back in
- [ ] While the list is showing, **Enter** accepts the item and does **not** apply the scope; with no list showing, Enter applies it. **Esc** closes the list first, and a second Esc closes the popup
- [ ] Functions, strings (`"…"`), pattern kinds (`exact:`), operators (`&`, `|`, `::`) and parentheses are coloured differently, following the IDE theme (try Light and Dark)
- [ ] `heads(` + Enter keeps the popup open, shows jj's message in a validation balloon by the field, gives it a red outline and underlines the character at the reported column; editing the text removes the underline. A revset that is valid on its own but fails only because of the Settings log revset shows the message with no underline
- [ ] The × at the right of the field clears the text. The Settings revset fields, the Set Diff Base → Custom revset dialog and the pickers have no history icon
- [ ] Scope history: after applying two different valid scopes, reopening the Scope popup and clicking the history icon at the left of the field lists both, most recent first, and choosing one fills the field. An invalid scope (Enter with an error) is **not** added, re-applying an older one moves it to the top, and after 6 distinct scopes only 5 remain. The history is shared: a second log tab's Scope popup lists the same scopes, and they survive an IDE restart. Saving a revset as an alias (**Save as alias…**) adds the alias name to the history
- [ ] Saved aliases in the drop-down: with revset aliases configured (one in user config, one in the repo's `.jj/repo/config.toml`, one function alias `f(x)`), the history drop-down shows the recent revsets, then a **Saved aliases** header and the symbol aliases sorted by name; the repo one is labelled `(repo)` and the function alias is absent. Choosing an alias puts just its name in the field. The same drop-down appears in the search box in revset mode, where the recent revsets are that box's own history. An alias added in a terminal appears after clicking out of the field and back in, and with no aliases configured there is no header
- [ ] Search box history keeps switches: searching `fo+` with **Regex** and **Words** on (Enter), then `mine()` with **Revset** on (Enter), then turning every switch off and opening the history shows the rows `mine()   [Revset]` and `fo+   [Regex, Words]`. Choosing the second turns Regex and Words on and Revset off; choosing the first turns Revset on. A plain search with no switches restores them all to off
- [ ] Search box with revset mode **off**: `foo(` is plain text, with no colours and no completion list. Turning revset mode on colours it and offers completion; turning it off makes it plain again
- [ ] Search box in revset mode: an invalid revset gets the red outline and the underline at jj's column (relative to the search text alone, not the scope), the border is red exactly once (no second outline inside it), and half-formed revsets while typing log no `CliExecutor … failed` WARN lines. Accepting a completion changes the text, so the log re-filters a moment later, which is expected
- [ ] Search box wide enough to be inline: clicking into it (or Tab), in text mode and revset mode alike, pops it out into the wide popup under a chip showing the same text, with the caret in the field; typing (and in revset mode, completion and history) work there. Toggling revset mode inside the popup keeps it popped out. Clicking away or pressing Esc closes the popup and returns to the inline box with the text intact; after Esc, focus returning to the box does **not** pop it out again, while clicking the box does
- [ ] Squeezing the log until the box collapses to its chip, then clicking the chip pops out the full field with the toggles; completion and history work inside the popup (the history list opens below the popup, not behind it) and Esc closes it
- [ ] The Rebase, Squash Into… and Move Bookmark to Change pickers and file history keep their plain search box (no colours, no completion)

#### Tab scope (Scope chip)

**Code:** `ui/log/JujutsuRevsetFilterComponent.kt`, `ui/log/RevsetFilter.kt`,
`ui/log/JujutsuLogTable.kt` (`setScope`), `ui/log/UnifiedJujutsuLogPanel.kt`
(`createScopeComponent`, `onSelectionHiddenByFilter`), `ui/common/CommitTablePanel.kt` (`createScopeComponent`, `resetAllFilters`), `settings/LogWindowConfig.kt` (`revsetFilter`)

The typed revset is validated against jj (one single-row `jj log` call per repo) and becomes the tab's scope: the base the search box and the other chips narrow within. jj resolves it together with every other filter (see Whole-history filtering), ANDed with the repo-level revset from Settings. Matches anywhere in history show up, a page at a time. It never changes what jj loads for any other tab or dialog.

- [ ] The **Scope** chip (with a scope icon) is the first thing in the toolbar, left of the search box and separated from it by a thin divider; it is not among the Root/Reference/Author/Date chips and stays visible when the other chips collapse into "»"
- [ ] The unset chip reads `Scope: all()` in grey; a set scope is shown in the monospace editor font. Hovering explains it is the tab's scope; after a failing re-apply the tooltip shows jj's error instead, and returns to the explanation once the error clears
- [ ] Clicking the chip opens a popup under it titled "Scope Revset" with a text field, an "Enter to apply · Esc to close" hint, and a "Revset language" link that opens jj's revset docs
- [ ] Typing `ancestors(@, 5)` + **Enter** closes the popup, narrows the graph to that revset and shows the revset text on the chip. Adding the **Author** filter and search text shows the intersection of all of them. Clearing the field and pressing Enter returns the full log
- [ ] An invalid revset (`foo(`) + Enter gives the field a red outline and jj's own parse error under it, and the popup stays open to fix it. **Esc** closes the popup and leaves the previous filter state unchanged
- [ ] A single letter such as `t` in the search box matches commits with no "prefix is ambiguous" error (same in the New Change picker after pressing Enter)
- [ ] With a scope set and an Author filter or search text active, navigating to a change those hide (e.g. from the bookmarks panel) shows a balloon saying it is "hidden by the current log filters"; **Reset Filters** clears search and chips, **keeps the scope**, and selects the change if the scope allows it
- [ ] With a scope set and nothing else active, navigating to a change outside the scope shows a balloon saying it "is outside this tab's scope (…)"; **Clear Scope** clears the scope and selects it
- [ ] **Clear Scope** or **Reset Filters** shows **at most one** follow-up balloon, and none when the change becomes visible (never fired from stale, pre-refilter rows). With scope `ancestors(@, 10)`, scrolling to the last row and clicking its parent link shows exactly **one** "outside this tab's scope" balloon, also with several log tabs open
- [ ] When jj fails on a re-apply (e.g. make the workspace stale from another workspace, then change the Author filter), the strip below the log shows one truncated line, hovering shows the full jj error, and the log tool window can still be dragged narrow (GitHub #142)
- [ ] Setting a repo-level Log Revset in Settings → Version Control → Jujutsu (e.g. `::@`) and the chip to `all()` stays within `::@` (the repo-level setting always wins). Changing the repo-level Log Revset or Log Limit and clicking OK/Apply updates the log window immediately, with no need to click Refresh first
- [ ] A scope that references a bookmark (`my-bookmark::`), after deleting that bookmark and changing another filter, shows jj's error in the status bar while the graph keeps the last successfully resolved set rather than going blank
- [ ] The chip's revset persists per log tab across an IDE restart
- [ ] With a filter active, scrolling to the bottom loads the next page of matches (one page and one remainder query per scroll; check `idea.log`), and once every match is loaded further scrolling makes no `jj log` calls. A revset matching commits only deep in history (outside the first 500 rows) shows them
- [ ] A second log tab with no scope still shows the full log, and the New Change picker or any dialog that reads the log cache is unaffected by the first tab's scope. A new change that falls inside the active scope appears in the filtered graph after a refresh

### MT-LOG-REFRESH

**Auto-refresh**

**Code:** `ui/log/UnifiedJujutsuLogDataLoader.kt`, `ui/common/BackgroundDataLoader.kt`

- [ ] Editing and saving a **tracked** file in the working copy updates the `@` row (no longer empty) within ~1 s, with a single `Refreshing log entries` in `idea.log` (via the snapshot `jj status` records, not the file event itself)
- [ ] Saving an **ignored** file causes no log refresh: `touch .idea/workspace.xml` twice (repo with `/workspace.xml` in `.idea/.gitignore`) leaves no `Refreshing log entries` and no `perf: graph-layout` in `idea.log`
- [ ] With `JAVA_TOOL_OPTIONS=-Didea.filewatcher.disabled=true ./gradlew runIde`, editing and saving a tracked file and then refocusing the IDE still updates the `@` row (the `Operation heads of … changed` fallback in `idea.log`)
- [ ] The log refreshes after VCS operations (describe, new, edit), keeping the working copy (`@`) selected, with no flicker
- [ ] The log refreshes after an **external** jj operation run in a terminal (e.g. `jj new`, `jj bookmark create`) within ~300 ms, without saving a file (op-heads watch). This is the canonical auto-refresh check; other sections (bookmark widget, reference filter) reference it rather than repeating it
- [ ] In a **secondary workspace** (`jj workspace add ../ws2` from the sandbox repo, open `ws2` as the project), `jj new` in a terminal in `ws2`, then `jj new` in the primary workspace, refreshes the log within ~300 ms both times, without saving a file
- [ ] Stale workspace: add a second workspace (`jj workspace add ../ws2`), run `jj describe -m x` in `ws2`, then open the original in the IDE (also try with the project already open and the log tab visible). No "Uncaught exception in background task" dialog appears; exactly one "stale workspace" balloon appears (not one per refresh); `idea.log` shows INFO "working copy is stale", not WARN or "jj may not be installed"; **Update stale workspace** loads the log without pressing Refresh
- [ ] On a large repo scrolled well past the first page, `jj new` (then `jj new` on an empty `@`, then `jj abandon @`) leaves the graph at the top and far below correct (no shifted or missing lanes, long-edge arrows intact), further scrolling still loads cleanly, and `idea.log` shows `perf: graph-layout-replace` with an `operations` count in the thousands, not tens of thousands (one-off, not per loaded row)
- [ ] Opening a large repo's log, clicking Refresh and closing the project while it is still loading closes the project promptly (no multi-minute stall); `idea.log` shows the load cancelled rather than run to completion

#### Divergent-change write protection (GitHub #130)

**Code:** `jj/DivergentChange.kt`, `jj/CommandExecutor.kt` (`Failure.Divergent`, `WithRepo.fireFailure`)

- [ ] Make `@` divergent behind the IDE's back: `op=$(jj op log --no-graph --limit 1 -T 'id.short()')`, then `jj describe -r @ -m left --at-op $op` and `jj describe -r @ -m right --at-op $op`. Describing the (now stale) `@` row *before* it auto-refreshes shows a "Change Has Become Divergent" dialog, the log refreshes to show both versions, the description is **not** applied, and the action is not retried
- [ ] Edit, Abandon and a hunk-level Split/Squash Into (raw write paths) give the same dialog, with no raw stderr
- [ ] After the refresh, Describe on one of the divergent rows (`/0`, `/1` offset) succeeds

#### Manual refresh (GitHub #115)

**Code:** `ui/common/CommitTablePanel.kt` (`manualRefresh`), `jj/JujutsuStateModel.kt`
(`invalidateRepositoryState`)

The op_heads watch normally refreshes bookmarks and working-copy state before you'd ever click Refresh, so first kill it: launch with `JAVA_TOOL_OPTIONS=-Didea.filewatcher.disabled=true ./gradlew runIde` (no `.jj/repo/op_heads` events are delivered).

- [ ] With the watcher disabled, `jj bookmark create refresh-check` (or delete/move one) in a terminal, **without** refocusing the IDE window (frame activation is a separate fallback), leaves the bookmark chip, reference filter dropdown and status-bar widget stale; clicking toolbar **Refresh** updates all three
- [ ] On FX-STRESS with `SCALE=6`, clicking toolbar **Refresh** and immediately cancelling the "Loading commits" progress (✕) leaves no process in `ps aux | grep 'jj log'`, produces no error report, and the next Refresh loads normally

#### Paged log loading (GitHub #69)

**Code:** `ui/log/PagedLogWindow.kt`, `ui/log/UnifiedJujutsuLogDataLoader.kt`,
`ui/common/CommitTablePanel.kt`, `ui/log/graph/LayoutCalculator.kt`

See docs/design/jj-idea-2c8k-paged-log-loading.md for the mechanism and its validated (and not-yet-validated) boundaries. Paged loading is always on, with no setting needed. Use FX-STRESS's `SCALE=6 WITH_REMOTE=1` fixture (`jj-stress-test`, ~5,952 commits) for a repo large enough that paging is perceptible.

- [ ] Opening the log loads fast, showing the first page (500 rows, independent of the "Changes to show" setting); the status strip below the table stays hidden throughout (no "Showing N changes" message in this mode; the scrollbar already says there's more)
- [ ] On a wide multi-branch repo (e.g. FX-STRESS), rows whose parent didn't make it into any loaded page show a **faded straight line** down from the commit circle instead of nothing (which would look like a true root), distinct from the wiggle used for filtering. Clicking it loads and reveals the missing parent, scrolling to it once it arrives, and it stays in view while the idle trickle keeps loading rows between it and the old window (see MT-LOG-GRAPH "Long-edge navigation" for the full hover/click behaviour)
- [ ] Idle trickle: on FX-STRESS (`SCALE=6 WITH_REMOTE=1`), open the log and *leave it alone*. Older history keeps loading at a gentle pace (the scrollbar thumb slowly shrinks), faded stubs on screen connect to their parents without any scrolling or clicking, no old row ever appears ahead of the rows between, and the IDE stays responsive. It stops when the whole log is loaded or ~10,000 rows are in memory, after which stubs remain clickable and scrolling to the bottom still loads more, uncapped. In Activity Monitor the IDE and jj stay well under a core while it trickles. Closing the log window stops it. (Page size: a fixed 500 rows for the first page, then sized so each fetch takes about 0.7 s, up to 8,000)
- [ ] Scrolling to the bottom of the loaded rows loads more history in automatically before you reach the literal end (eager one-page-ahead prefetch); scrolling repeatedly keeps loading further pages at a consistent, flat pace, and the viewport stays where you scrolled to (does not jump back to the selected/`@` row) each time a page loads
- [ ] Landing granularity: on FX-SCALE-SET `r80k-git` (or `r36k-keycloak`), scroll steadily to the end (~86k rows). In `idea.log`, `perf: log-page` lines show `limit` growing from 500 (at most doubling per page) up to the 8,000 ceiling, about 13 pages at the ceiling for a full load, with `took` well under ~700 ms (on a fast machine the target never binds; to exercise the adaptive path run with `-Djj.log.pageTargetMs=150`, where `limit` stays at the 500-row floor and grows only after a page that took under 150 ms; also `-Djj.log.pageMaxRows=`). `perf: log-apply` lines stay at one per page (25-80 ms at depth), `log-edt-wait` stays under ~15 ms after the first page, `graph-layout-append` does not grow with depth, the first paint is not delayed, and scrolling stays smooth with no new freezes. Landings arriving within ~100 ms apply once, as the latest; that needs pages that close together, which normal paging on a fast machine does not produce (2026-10-23 run: 19 pages, 19 applies, so coalescing was not observed)
- [ ] Right-click → **New Change** (or **Describe**, **Squash**, **Abandon**) appears and updates at the top effectively immediately, regardless of how many pages you've scrolled through; the previously scrolled-to deeper pages stay visible and unaffected
- [ ] After scrolling several pages deep, a write that adds a commit at the top (`jj new` in a terminal, or the right-click actions) leaves the rows under the viewport in place (no slide by a row). Scrolled to the very top, the new commit appears at row 0 and is visible, instead of the viewport holding its old pixel position and hiding it
- [ ] Ctrl/Cmd+click three rows (and Shift+click a range), then let the idle trickle run ~10 s, and separately run a write such as `jj describe` on an unselected commit from a terminal: all selected rows stay selected, the lead row (focus ring) is unchanged, the viewport doesn't move, and a diff opened for the selection doesn't blank. Abandoning one selected row leaves the others selected. An author filter that hides one selected row leaves the rest selected (GitHub #145)
- [ ] A write whose effect lands on a commit deep in history you haven't scrolled to (e.g. moving a bookmark via a picker to an off-screen commit) still finds and selects it correctly (via the existing loadContext mechanism, GitHub #76), slower than the common case
- [ ] After scrolling several pages deep, toolbar **Refresh** preserves the scroll depth and total loaded row count (not collapsed back to one page), and total time is proportional to pages loaded, not instant (deliberate: an explicit Refresh re-verifies everything currently loaded)
- [ ] Clicking a bookmark/reference filter entry that is currently off-screen (explicit navigation, not a data refresh) still scrolls the viewport to make it visible, unlike the loadMore case above
- [ ] Settings → Version Control → Jujutsu → Log: the limit field reads **Fallback change limit** and its comment says it only applies when paging is unavailable. Raising it to a large value (e.g. 10,000) changes nothing: the first paint and every page are still 500 rows, and a write near `@` stays fast

### MT-CTXMENU

**Log row context menu actions**

**Code:** `actions/change/`, `ui/duplicate/DuplicateDialog.kt`, `ui/duplicate/DuplicateImmutabilityGuard.kt`, `ui/newchange/NewChangeDialog.kt`, `ui/rebase/RebasePreviewPanel.kt`, `ui/rebase/RebaseSimulator.kt`, `ui/common/JujutsuCompareChangesPanel.kt`, `ui/components/RevisionSelectorPopup.kt`, `actions/change/compareWithRevisionAction.kt`
**Also re-run:** MT-SQUASH, MT-SPLIT (share the commit picker); MT-DIFF (Compare with Working Copy / Show Diff in New Tab reuse the RevisionSelectorPopup and Changes-pane view); MT-WORKINGCOPY (its "New Change" button shares `CommandExecutor.new`)

#### Basic actions

- [ ] Right-clicking a row opens the context menu. **Copy Change ID**, **Copy Commit ID** (the full 40-char hex id, GitHub #137) and **Copy Description** copy to the clipboard
- [ ] **Copy Change ID** and **Copy Commit ID** appear in Settings → Keymap (search "Copy Change ID"), unbound by default. With a shortcut assigned and a log row selected, the shortcut copies the right id (and does nothing outside the log), and the menu items show the shortcut hint
- [ ] **New Change From This** (primary, no dialog) creates the new change directly and refreshes. **New Change...** (secondary) opens the New Change dialog and creates the change. **Edit** changes the working copy
- [ ] **Describe** opens a dialog and updates the description. The description field is focused on open and is a real commit-message editor (spellcheck, subject-length inspection, Ctrl+E history popup): Enter inserts a newline, Ctrl+Enter accepts (GitHub #46). The prompt reads "Enter description for change \<short-id\>:" using the short id shown in the log row, not the full 64-character id
- [ ] **Abandon** removes the change after confirmation. Abandoning an empty, undescribed change that carries a local bookmark asks for confirmation naming the bookmark and warning it will be deleted; cancelling keeps both the change and the bookmark. A change with only a remote-tracking bookmark (`name@remote`) abandons without prompting (GitHub #129)
- [ ] Multi-selecting 3 mutable changes (Ctrl/Cmd-click) → **Abandon Changes** asks once, listing counts ("2 with file modifications", …) and the union of local bookmarks. Confirming removes all three and selects a surviving parent (the working copy if `@` was among them), and a single **Undo** restores all three. Two empty, undescribed, unbookmarked changes abandon without a prompt; selecting 10 or more always prompts. A selection that includes an immutable change, or spans two repos, shows **Abandon** disabled
- [ ] **Duplicate Change** creates an identical copy in place, with a new change ID and the same description; `@` does not move. **Duplicate Onto...** opens a dialog to pick a destination and placement (onto/after/before), then creates the copy there
- [ ] With ~20 rows selected, **Rebase…** and **Duplicate Onto…** show the source list scrolling within about 5 rows with the destination picker still visible (the source list helper is shared with Squash Into…, GitHub #125)
- [ ] Hovering a commit row in the Duplicate Onto... destination picker, and in the Rebase destination picker / preview, shows real bookmark/tag chips and status icons in the tooltip, not a broken-image glyph
- [ ] **Set Tag Here...** shows a distinct tag-plus badge icon (not the platform's generic + icon); entering a name creates the tag at that commit and the log updates

#### Undo

**Code:** `ui/services/UndoBalloon.kt`, `ui/services/JujutsuUndoService.kt`,
`vcs/JujutsuRollbackEnvironment.kt` (via `offerUndo`), `actions/undo/UndoLastOperationAction.kt`, `actions/change/abandonChangeAction.kt`,
`ui/restore/RestoreDialog.kt`

Stage 1 of the undo roadmap (docs/design/undo-support-roadmap.md). Wired to **Abandon**, **Duplicate Onto**, **Move Bookmark**, **Rebase** (drag-and-drop), **Set Tag** and **Restore** / **Restore to This**; the rest of jj-idea-t0iy's rollout is still pending.

- [ ] Create a change, `jj new`, then **edit a file so the working copy is dirty** (exercises the snapshot-op exclusion) → right-click → **Abandon** → confirm. The change disappears **and** a Jujutsu balloon appears with an inline **Undo** link. Clicking **Undo** brings the change back, refreshes the working-copy files and expires the balloon. Repeat with a **clean** working copy (no snapshot op written): identical behaviour
- [ ] Abandon again and **dismiss the balloon without clicking Undo**: the **VCS** main menu has an entry reading **"Undo Abandon"** that brings the change back. Invoking it a second time (nothing left to undo) shows it disabled, reading "Undo Last Jujutsu Operation" (visible, not hidden; hover shows why)
- [ ] While a balloon is showing, run `jj new` in a terminal in the same repo, *then* click the balloon's Undo: it undoes the abandon specifically, not the terminal command. `jj op log` shows a **new** "revert" operation added, not an earlier one removed
- [ ] With Settings → Version Control → Jujutsu → JJ executable path pointing at a pinned older jj (`scripts/jj-install-version.sh 0.37.0`, then `~/.local/bin/jj-0.37`), the first three checks above still work (the mechanism holds at `JjVersion.MINIMUM`)
- [ ] Settings → Keymap → search "Jujutsu" → **Undo Last Jujutsu Operation** shows that name, not the raw action id `Jujutsu.UndoLastOperation`
- [ ] Modify a file, right-click → **Restore…**, tick it in the dialog, confirm: a balloon reading "Restore" with an inline **Undo** appears, and clicking it brings the discarded content back and refreshes the working-copy tree. **Restore to This…** from a historical commit's changed files (see MT-LOG-DETAILS) shows a balloon reading "Restore to This"
- [ ] The Restore and Drop dialogs' summary lines ("N files will be restored" / "Changes to N files will be dropped") count the pre-ticked files immediately on open, not only after toggling a checkbox (GitHub #148)

#### Duplicate Change

- [ ] Right-clicking an **immutable** change still offers both Duplicate actions (unlike Abandon/Describe/Edit). Multi-selecting several changes (same repo) and choosing **Duplicate Change** copies each in place; across two repos in a multi-root project both Duplicate actions are disabled or hidden
- [ ] **Duplicate Onto...**: "Onto (-d)" places the copy as a child of the destination; "Insert after (-A)" / "Insert before (-B)" place it relative to the destination accordingly. With no destination selected a validation error shows and the dialog stays open

#### Duplicate Onto... immutability guard

In a repo with an immutable trunk (e.g. `main` tracked as immutable, with mutable commits on top):

- [ ] An immutable **head** (no children) as destination: "Insert after" stays enabled and "Insert before" greys out. An immutable **non-head** commit (has a child): both grey out and only "Onto" is selectable
- [ ] With "Insert before" selected, immutable commits don't appear in the destination picker at all. With "Insert after" selected, only immutable commits that have an immutable child are hidden; an immutable head remains selectable. Switching back to "Onto" makes every commit reappear
- [ ] A permitted "Insert after" on an immutable head succeeds when you click Duplicate. Normal "Onto" duplicates and the quick in-place **Duplicate Change** are unaffected by the guard

#### Dialog commit-picker ordering

→ automate: jj-idea-pa05 (cold-cache ordering is a `logCache`/`RepoLogCache` invariant,
testable without rendering)

Restart the IDE (or open a repo the log tool window hasn't loaded yet) so `logCache` starts cold for it, then, **without** opening the main log tab for that repo first, open a dialog with a commit picker (Rebase, Squash Into..., Duplicate Onto..., Move Bookmark to Change):

- [ ] The picker's order matches what the main log shows (newest first, root/oldest last), not reversed or arbitrary; the graph connector lines render correctly (no crossed or backwards lines); and opening the main log tab afterwards shows the same order as the dialog did

In a **multi-repo** project (multiple `.jj` roots open together):

- [ ] After opening the main log tab (which loads and merges all repos), a commit picker for each repo in turn shows that repo's own commits newest-first with the root last, none out of place. The ordering holds consistently across a few IDE restarts, for every repo

#### Dialog commit-picker search

**Code:** `ui/components/LogSearchField.kt`, `ui/components/CommitPickerPanel.kt`,
`jj/LogSearchRevset.kt`, `ui/log/LogFilterMatcher.kt`, `ui/rebase/RebaseDialog.kt`,
`ui/duplicate/DuplicateDialog.kt`, `ui/squash/SquashIntoDialog.kt`,
`actions/bookmark/MoveBookmarkToChangeDialog.kt`

→ automate: `CommitPickerPanelTest` covers predicate/search combination, selection
preservation, and the one-`jj log`-call-per-Enter contract without rendering a dialog; this
section is for the toggles' visible behavior and the end-to-end off-window search in each
dialog.

- [ ] **Rebase**, **Squash Into…** and **Duplicate Onto…** destination pickers show regex / match-case / whole-words toggle icons on the search field, as the main log's does. Toggling regex and typing `fix.*bug` filters by regex; match case makes the filter case-sensitive; whole words restricts matches to whole-word boundaries
- [ ] In each picker (and Move Bookmark to Change), typing part of a bookmark name filters to commits carrying that bookmark
- [ ] **Squash Into…**'s **parent mode** (right-click → Squash Into Parent) still shows its small fixed candidate list with **no** search field

Setup for the off-window checks: lower "Number of changes to show" (Settings → Version Control → Jujutsu) to something small (e.g. 100) against a repo with several hundred commits (e.g. `scripts/fixtures/fx-stress.sh`'s `jj-stress-test`) so most commits are off-window. Find a change ID well past the window (e.g. `jj log -r 'all()' -T change_id --no-graph --limit 2000`).

- [ ] **Rebase**: pasting the off-window change ID into the destination search shows no rows while typing; pressing **Enter** fetches and shows it as a selectable destination, a status line below the table reports it was found, and selecting it and clicking Rebase succeeds
- [ ] **Squash Into…** (picking a destination and picking sources) and **Duplicate Onto…**: the same paste-then-Enter check; the found commit is selectable and the operation completes
- [ ] **Move Bookmark to Change**: pasting the off-window change ID and pressing Enter shows it under the correct Forward/Backward section (the same classification the initially-loaded rows use), and moving the bookmark to it succeeds
- [ ] In **Rebase**/**Duplicate Onto…**, searching for a change ID that matches nothing loaded *and* would be invalid as a destination (e.g. a descendant): Enter still finds it via the whole-repo query, and selecting it and confirming shows jj's own rejection rather than silently succeeding (the documented ancestry-completeness limitation, not a crash)
- [ ] Gibberish plus Enter leaves the picker unchanged and the status line reports no results, in every dialog above. Cancelling a dialog immediately after pressing Enter (before the result appears) throws nothing in `idea.log` and does not reopen or flash the dialog

#### New Change quick action

- [ ] With the log focused and a commit selected (or nothing but the default `@` selection), Cmd/Ctrl+Shift+N creates a new change on top of it with no dialog, and the log reselects the new change
- [ ] With the **editor** focused (not the log), Cmd/Ctrl+Shift+N still triggers the IDE's normal Go to File / New Scratch File action; it is not intercepted
- [ ] Multi-selecting two commits (same repo) and choosing **New Change From These** (or the shortcut) creates a merge change with both as parents; across two repos in a multi-root project it is disabled or hidden (no arbitrary repo is picked)

#### New Change... dialog (GitHub #83)

Build a small stack `A → B → C` (three plain changes) for this section.

- [ ] Right-click B → **New Change...** opens a dialog showing B as the target, an empty description field, placement radios (Onto/Insert after/Insert before, Onto selected by default), a "Switch working copy to the new change" checkbox (checked), and a live preview graph
- [ ] **Onto** + a description + Ctrl+Enter (Cmd+Enter) creates a plain child of B with that description and `@` moves to it. Plain Enter inserts a newline (the field is a commit-message editor, GitHub #46)
- [ ] **Insert after**: the preview shows the new change between B and C; Create does so, C is rebased onto it, `@` moves to it and the log reselects it. **Insert before**: the preview shows it between A and B; Create does so, B (and C) are rebased onto it and `@` moves to it. For each placement mode the preview graph matches what the log shows immediately after
- [ ] Unchecking "Switch working copy to the new change" before Create inserts the change but leaves `@` on its prior commit (`jj new --no-edit`)
- [ ] Multi-selecting B and another head, then **Insert after**, creates a merge change with both as parents and relocates both targets' children onto it
- [ ] On an **immutable** change, **Insert before** is disabled (it would rewrite the immutable target) and **Insert after** stays enabled unless the target has an immutable child, matching the Duplicate Onto... guard above
- [ ] Cross-repo multi-select in a multi-root project disables or hides the action, as for **New Change From This/These**. Right-clicking a `jjc://` change-navigation link (e.g. a parent reference in the details panel) still offers **New Change...** and it acts on the link's target

**Merge description prefill (GitHub #103)**: needs two sibling changes with bookmarks `feat` (newer) and `main` (older).

- [ ] Selecting both → **New Change...** (the dialog, not the quick **New Change From These**) prefills the description `Merge branch 'feat' into main` (older tip = destination), with an **Other phrasings** link below the editor listing the primary, the flipped form, and `Merge branches 'feat' and 'main'`; picking one replaces the text. After typing extra text, picking a phrasing asks for confirmation before replacing, and No keeps your text
- [ ] One change, three changes, or a change with no bookmark (or two different bookmarks): description empty, no link. Two bookmarks that exist only as remotes (`main@origin`) prefill using the local names. The same flow works from the bookmarks panel (multi-select two bookmarks)

#### Move Up / Move Down (GitHub #93)

Move Up/Down swap the selected commit with its single child / single parent **in the commit graph**, not whatever row is adjacent on screen. Build a small stack `A → B → C → D` (four plain changes, `A` immutable, e.g. `jj config set --repo user.email a; jj new; jj new; jj new` then `jj bookmark create -r A base` and advance `main`/trunk over `A`) for the linear checks, plus a small merge (`A → B`, `A → C`, `B,C → D`) for the branch/merge checks.

- [ ] On the linear stack, selecting `B` or `C` and pressing Ctrl+Shift+Up (same on macOS; Cmd+Shift+Up is deliberately **not** bound, see the plugin.xml comment on `Jujutsu.MoveChangeUp`) swaps it with its single child, the log reselects it in its new position, and `jj log` confirms the order. Pressing again keeps climbing one position per press; Ctrl+Shift+Down moves it back down
- [ ] The undo balloon after a move reads "Move" (not "Rebase"); **Undo** restores the previous order
- [ ] `D` (no child): Move Up is disabled; `A` (no parent): Move Down is disabled. `B` (single immutable parent `A`): Move Down, which would rewrite the immutable commit, is disabled and Move Up stays enabled. Multi-selecting two rows disables both
- [ ] Right-clicking a row shows **Move Up**/**Move Down** near **Rebase...**, each with its keyboard-shortcut hint
- [ ] With the **editor** focused, Ctrl+Shift+Up/Down still fires the editor's own "Move Statement Up/Down". On **macOS**, Cmd+Shift+Up/Down in the Commit/Local Changes file list still extends the selection to every change above/below (the platform's `EditorTextStartWithSelection`/`EditorTextEndWithSelection`)
- [ ] On the merge shape, `A` (two children) disables Move Up (ambiguous) and `D` (two parents) disables Move Down. `B` (single child `D`, single parent `A`) enables both, and each swap lands where the graph predicts, confirmed via `jj log`
- [ ] In a multi-root project with two repos' commits interleaved so one repo's row sits directly above/below the other's, moving a commit acts on its own repo's child/parent (or is disabled if it has none) and never touches the adjacent row from the other repo. With a log filter that hides the selected commit's real single child while leaving an unrelated commit visually adjacent, Move Up still targets the real (hidden) child

#### Compare with Working Copy

- [ ] **Compare with Working Copy** is shown on a non-working-copy commit and **not visible** on the working-copy entry
- [ ] Invoking it on a commit that differs from `@` opens the **VcsChanges** tool window with a Changes tree listing every changed file (added, modified, deleted and renamed), and the first file's diff open in the editor. Selecting other files updates the diff in the same reusable editor tab
- [ ] The right (working-copy) side of the diff is editable and edits write through to the real file on disk; the left (commit) side is read-only
- [ ] Right-clicking a file in the Changes tree shows the jj file-change context menu (Show Diff, Restore, etc.). On a commit identical to `@` a "No Differences" notification appears instead of an empty pane

#### Compare with Another Commit / Compare Before with Another Commit

- [ ] Right-clicking any historical commit shows **Compare with Working Copy**, **Compare with Another Commit...** and **Compare Before with Another Commit...** together, in that order. On the **working-copy** entry, Compare with Working Copy is not visible but **Compare with Another Commit...** is still shown and enabled. On a **root commit** (no parents), **Compare Before with Another Commit...** is disabled
- [ ] **Compare with Another Commit...** opens the same revision-picker popup as **Compare with Branch...** (bookmark/change ID/revision search); picking one opens a Changes-pane tab titled `<selected> vs <picked>`, the **selected commit's content on the left** and the **picked revision's on the right**, both read-only
- [ ] **Compare Before with Another Commit...** compares the commit's **parent** (not itself) against the picked revision, same left/right convention; on a **merge commit** the left side is the auto-merged parent content (matching Compare with Working Copy's merge-parent handling)
- [ ] Picking a revision identical in content to the base shows a "No Differences" notification and opens no tab. Typing an unresolvable revision shows a "Compare Failed" error dialog. Both actions appear and work identically from a `jjc://` change-navigation link's menu
- [ ] Hidden commits (GitHub #130): note `jj log -r @ -T commit_id`, edit a tracked file so `@` is amended (the noted commit is now hidden), then **Compare with Another Commit...** on any other commit and type the **old full commit id**: the right side shows the file content **as of the old commit**, not the current working-copy content. Picker rows show the short commit id after the change id, and the hidden commit's row is marked `(hidden)`. The same holds for the per-file actions: from a file's history (or a commit's changed files), **Compare with Another Commit...** and **Compare Before with Another Commit...** with the old full commit id show the file as of the old commit

#### Show Diff in New Tab, multi-file

- [ ] Multi-selecting files (in the log's file list or a commit's changes) and choosing **Show Diff in New Tab** opens the same VcsChanges Changes-pane view as Compare with Working Copy, with the first file's diff open. A single file shows a title with just that file's name; several show "N files"

### MT-SQUASH

**Squash Into…**

**Code:** `ui/squash/SquashIntoDialog.kt`, `ui/squash/SquashSimulator.kt`, `ui/rebase/RebasePreviewPanel.kt`, `ui/squash/SquashFilePreview.kt`, `ui/common/FileDiffPreviewPanel.kt`, `ui/common/HunkPickPreviewController.kt`, `ui/common/HunkSelectionModel.kt`, `diffedit/HunkPickerDialog.kt`, `diffedit/HunkArrowDiffExtension.kt`, `diffedit/DiffEditTool.kt`, `actions/change/squashIntoAction.kt`, `actions/change/squashFromAction.kt`, `actions/filechange/SquashIntoFilesAction.kt`, `actions/change/squashIntoImmutableParentAction.kt`, `jj/ImmutableWorkingCopyTracker.kt`
**Fixture:** FX-STACK
**Also re-run:** MT-CTXMENU (shares the commit picker); MT-SPLIT (shares `ui/common/FileDiffPreviewPanel.kt`, `ui/common/HunkPickPreviewController.kt`, the hunk picker, and the staging protocol); MT-DIFF, MT-DIFF-PREVIEW (the hunk picker registers a plugin-wide `diff.DiffExtension` — confirm it stays a no-op on every other diff viewer)

#### Availability and destination picker

- [ ] "Squash Into..." is present in the context menu for a single mutable change and for 2+ mutable changes, and **disabled** when any selected change is immutable or when the selection spans multiple repos. "Squash into Parent..." still works for single-parent mutable changes
- [ ] Selecting ~20 mutable changes → **Squash Into…** makes the source list at the top **scroll within about 5 rows** with the destination picker below it still visible and usable; with 2–3 selected the list shows in full with no scrollbar (GitHub #125)
- [ ] The dialog opens with the source change(s) listed at the top. The source itself is **not** selectable as destination, immutable changes are **not** shown, and **descendants of the source ARE** shown as valid destinations
- [ ] Typing in the search field filters by change ID, description and bookmark name; clearing it restores the full filtered list. Selecting a destination populates the description field if you haven't typed. Hovering a commit row with bookmarks/tags shows real chip icons in the tooltip, not a broken-image glyph

#### Description editor

The description field is a real commit-message editor (`ui/components/DescriptionEditor.kt`, wrapping the platform's `CommitMessage`), not a plain text area (GitHub #46).

- [ ] A long first line shows the subject-length inspection highlight, and a misspelled word shows a spellcheck squiggle
- [ ] The toolbar's history button (Ctrl+E / Cmd+E) opens a popup of recently-used descriptions from other dialogs/changes; picking one previews it, Escape reverts, and it doesn't close the dialog
- [ ] Enter inserts a newline (does not submit); Ctrl+Enter (Cmd+Enter) submits

#### Graph preview

The top of the right-hand side shows the log graph as it will look after the squash (source green, destination blue), with a one-line summary underneath.

- [ ] Right-click a mutable change → **Squash Into…** shows a "Result preview" graph above the file diff; before a destination is picked it shows the unchanged graph. Picking a destination highlights it blue and the summary reads "<source> → squash into <dest> (source kept)" (checkbox unticked by default)
- [ ] Ticking **Delete empty source change…** removes the source row, hangs its children off its parent, and the summary says "source abandoned"; unticking returns the row. With it ticked, unticking one file (or hunk-picking a file) greys the checkbox out and brings the source row back (a partial squash can't empty it). Squashing the working copy with it ticked moves the `@` marker to the destination
- [ ] **Squash Into Here from…** (multi-select sources): selecting and deselecting sources updates the preview, and the summary says "N changes". **Squash into Parent…** shows the source and its parent
- [ ] Clicking OK leaves the real log matching the preview. The dialog still fits at its default size (graph, file diff and Pick Hunks… button all visible)

#### Per-file diff preview

The preview always shows **Before** (the source's own pre-change content, fixed) next to **Destination** (that same content plus this file's change if it's ticked, i.e. what the destination ends up with). This is deliberately anchored to the source's own before/after, not the real destination's content.

- [ ] Right-click a mutable change with 2+ changed files → **Squash into Parent…** (or **Squash from Here into…**) shows a preview pane to the right of the file list, with a wider dialog overall
- [ ] Clicking a file shows a syntax-highlighted, read-only diff of the file's full change, titled "Before" / "After (tick hunks to move to Destination)", with a **checkbox in the right gutter of every hunk** (all ticked by default, since all files are ticked) and a summary line under the file name reading "All hunks move to Destination"
- [ ] Unticking that file in the list clears every gutter checkbox, the summary reads "No hunks move to Destination", and the diff **stays** (same hunks, no scroll jump); re-ticking ticks all boxes again
- [ ] On a multi-hunk file, clicking one hunk's checkbox toggles it in place (no flicker or scroll reset), the summary reads "1 of N hunks move to Destination", and the file turns **half-checked** in the list without opening Pick Hunks…. Ticking or unticking hunks until all/none are ticked makes the file fully ticked/unticked (no override)
- [ ] After a line-level edit in **Pick Hunks…** (not a whole-hunk subset) the file shows **no gutter checkboxes** and the summary reads "Custom pick moves to Destination (use Pick Hunks… to edit)"; ticking the file in the list resets it
- [ ] Clicking a second file switches the preview to it, and clicking back to the first switches back immediately (no visible reload). In **Squash Into Here from…**, changing the source selection in the picker table resets the preview to the placeholder and repopulates the file tree. Multi-selecting two changes → **Squash Into…** previews the combined file list. A binary or deleted file degrades gracefully (no exception)
- [ ] An ordinary diff elsewhere (e.g. MT-DIFF) shows **no** checkboxes

#### Hunk picking

Single-source only: jj's diff editor is one before/after pair, so hunk-level squashing across multiple sources isn't well-defined. It reuses Split's 3-pane arrow picker (`diffedit/HunkPickerDialog.kt`) with squash wording: Before (fixed) | destination (live) | full source change (fixed). A right-hand arrow squashes a hunk into the destination, a left-hand arrow reverts it to unsquashed. There is no "resolved" concept, so no merge-conflict confirmation dialogs.

- [ ] Right-click a mutable change with 2+ changed files → **Squash into Parent…** → selecting a changed file enables **Pick Hunks…** below the preview. With **two or more sources** selected in **Squash Into…**/**Squash Into Here from…** it is **not offered** (hidden), and selecting down to exactly one source shows it
- [ ] **Pick Hunks…** opens a 3-pane diff: Before | destination (live) | full source change, with arrows at each hunk's divider. Moving one hunk's arrow so only part of the file is squashed and clicking **Apply** closes the dialog with **no confirmation dialog of any kind**
- [ ] The file then shows **half-checked** in the file list, the preview's gutter checkboxes match the hunks you moved, and the summary reads "K of N hunks move to Destination". The file's own tick state is **unchanged** by a partial pick (not force-ticked or unticked). Reopening **Pick Hunks…** on that file resumes the exact prior partial selection
- [ ] **Apply** with every hunk moved to the destination side fully ticks the file; with every hunk left unsquashed it fully unticks; either way the override clears (reopening starts fresh from the tick-derived default, not a stale partial)
- [ ] With a partial pick present, the **"Delete empty source and move working copy"** checkbox is disabled (a partial squash can never empty the source) and the description field shows only the destination's own description (no merge)
- [ ] Clicking **Squash** leaves `jj diff` on the destination showing only the picked hunk, and the source still has the rest of that file's change. With a change that **deletes a file**, ticking the deletion (or hunk-picking to fully squash it) alongside a partial pick elsewhere lands the deletion in the destination too, not left behind as an empty file
- [ ] Any ordinary diff elsewhere in the IDE shows no gutter arrows (the `DiffExtension` stays a no-op outside the picker)
- [ ] **Split regression pass** (the preview cache and hunk picker are shared with Split): run MT-SPLIT's "Basic hunk selection" and "Hunk picking with the 3-pane arrow picker" checks; the wording there must read "Parent"/"Child", not the squash wording above

#### Description auto-population and validation

→ automate: jj-idea-ikr6 (description auto-population + validation logic below is pure
string/state logic, no rendering dependency)

- [ ] Full squash (all files selected): the field pre-fills `<dest desc>\n\n<source desc>` when both are non-empty, the source only when dest is empty, the dest only when source is empty, and stays empty when both are; multi-source appends all non-empty source descriptions after dest. Editing the field prevents further auto-updates on destination change
- [ ] Partial squash (some files unchecked): the field shows **only the destination description**, whatever the source description is. Switching back to all files selected restores the combined pre-fill (if you haven't edited)
- [ ] The "Squash" button is active initially (if the destination is pre-selected after load). Clicking it with no destination shows the inline error "Select a destination"; unchecking all files shows "Select at least one file"

#### "Delete empty source and move working copy" checkbox

- [ ] The checkbox is **enabled** when all files are selected (full squash), **disabled** (greyed out) when any file is unchecked (the source won't be empty), unchecked by default, and its last-used state is remembered across dialog opens
- [ ] Full squash, checkbox unchecked: select "change A" → "Squash Into..." → pick "change B", leave all files, click "Squash". "change A" is kept (now empty), `@` does not move to B, and the log selection stays on "change A"
- [ ] Full squash, checkbox checked: same, but check the box. "change A" disappears (abandoned), "change B" now contains `a.txt`, and if "change A" was the working copy (`@`), the working copy moves to "change B"
- [ ] Partial squash: add two files (`jj new -m "multi" && echo "x" > x.txt && echo "y" > y.txt`), select that change → "Squash Into..." → pick any destination, uncheck `y.txt`: the checkbox is greyed out and can't be checked. Clicking "Squash" moves only `x.txt` to the destination, the source still exists containing only `y.txt`, and neither description changes (no `--message` sent)

#### Other source and target shapes

- [ ] Squashing a parent into a child: "change A" → "Squash Into..." → pick "change C", which appears in the destination picker; with all files selected and the checkbox unchecked the squash completes without error and "change A"'s content is now in "change C"
- [ ] Multi-source: Ctrl/Cmd+click "change A" and "change B" → "Squash Into..." shows the files from both combined; picking "change C" and clicking "Squash" puts both A's and B's files in "change C", and A and B disappear (or are kept if the checkbox is unchecked; check either way)
- [ ] Working copy as source: with `@` on "change C" holding some content, select `@` → "Squash Into..." → pick "change B". Unchecked: `@` stays on "change C" (now empty or partial) and "change B" has the squashed content. Checked: "change C" (old `@`) is abandoned, the working copy moves to "change B" (now `@`), and no stranded empty change is left behind
- [ ] Merge commit target: after `jj new -m "merge" change_a change_b`, selecting the merge commit → "Squash Into..." lists the merge commit as a valid destination and squashing into it succeeds

#### Edited immutable working copy (GitHub #143)

jj never edits an immutable `@` in place: the first snapshot with edits moves them into a new child. The plugin lets jj do that, says so, and offers to squash the child back.

**Setup:** FX-STACK with an immutable base, e.g. `jj config set --repo 'revset-aliases."immutable_heads()"' 'bookmarks()'`, then in a terminal `jj edit --ignore-immutable <bookmarked change>`.

- [ ] Editing and saving a tracked file in the IDE shows a sticky **Immutable Commit Left Unchanged** notification once, naming the immutable commit and the repo, with a **Squash into Immutable Commit...** action. The log shows `@` as a new change on top of the immutable commit, which itself is unchanged. Continuing to edit, or running `jj status` in a terminal, shows no second notification for the same child
- [ ] Clicking **Squash into Immutable Commit...** shows a confirmation naming the immutable commit and warning it may be shared; **No** changes nothing. **Yes** squashes the edits into the immutable commit (`jj diff -r <it>` shows them), leaves `@` as an empty change on top, removes the notification and refreshes the log. Undo (Operations tab / `jj op undo`) reverts the squash
- [ ] After repeating the first edit and dismissing the notification, right-clicking the new child in the log shows **Squash into Immutable Parent...**, and only on that child (not on `@` after the squash, nor on ordinary changes above trunk)
- [ ] After the edit, `jj abandon @` (or `jj undo`) in a terminal expires the notification once the child is gone. With the IDE idle, editing a file on disk and running `jj status` in a terminal still produces the notification once the IDE refreshes (detected from repo state)
- [ ] No false positives: from an immutable `@`, using the status-bar switcher's **New on Top**, or pushing `@` so a fetch/push makes it immutable, does not show the notification

### MT-SPLIT

**Split, hunk-level selection**

**Code:** `ui/split/SplitDialog.kt`, `ui/common/HunkSelectionModel.kt`, `ui/common/HunkPickPreviewController.kt`, `ui/common/FileDiffPreviewPanel.kt`, `diffedit/HunkPickerDialog.kt`, `diffedit/HunkArrowDiffExtension.kt`, `diffedit/DiffEditTool.kt`, `actions/change/splitAction.kt`, `actions/filechange/SplitFilesAction.kt` (also `SplitIntoNewParentFilesAction`)
**Also re-run:** MT-CTXMENU (shares the commit picker in some flows); MT-DIFF, MT-DIFF-PREVIEW (the hunk picker registers a plugin-wide `diff.DiffExtension` — confirm it stays a no-op on every other diff viewer); MT-SQUASH (shares `ui/common/FileDiffPreviewPanel.kt`, `ui/common/HunkPickPreviewController.kt`, the hunk picker, and the staging protocol)

Setup: a scratch jj repo with a file that has at least **two separate** hunks of changes (so partial selection is meaningful).

Model (GitHub #101, identity-first wording shared by every mode): **ticking a file moves it to a brand-new commit**; unticked files **stay on the source's own change ID and position**. Nothing is ticked by default. Only the new commit's *position* varies with mode: a child (default), a sibling (`--parallel`), or a parent (`-B` / "Split into New Parent"). "Pick Hunks…" opens a native **3-pane** diff, Before (fixed) | Stays (live) | New commit (fixed), with a directional arrow at each hunk's divider instead of a checkbox: a right arrow at a Before|Stays divider moves that hunk to the new commit, and a left arrow at a Stays|New-commit divider moves it back. The Stays pane updates in place as you click, so you always see the actual resulting content, and it is view-only (arrow clicks only), so the result is always a well-formed composition of Before/New-commit hunks. There is no "resolved" concept, so no merge-conflict confirmation dialogs anywhere in this picker.

#### Basic hunk selection (main dialog preview)

- [ ] Right-click a mutable change → **Split into New Child…** shows the changed-files list on the left (nothing ticked) and a native read-only diff preview on the right. Under "Source", a muted note states the mode's shape; in the default mode: "The existing commit (&lt;shortid&gt;) keeps its change ID and position; ticked files move to a new child commit created on top of it."
- [ ] The note **word-wraps across multiple lines** and does **not** widen the left column or push the diff preview narrower; the splitter stays at its usual ~40/60 proportion regardless of note length. The change id in the note and in both description labels uses the same bold-prefix/grey-remainder styling as everywhere else in the plugin, not plain equal-weight text
- [ ] Clicking a file shows a native syntax-highlighted diff of the file's **full change**, titled "Before" / "After (tick hunks to move to new commit)", with an **unticked checkbox in the gutter of every hunk** and a summary under the file name reading "No hunks move to new commit" ("new commit" is the same noun used in the summary line, the hunk picker and the "Description for new commit …" label)
- [ ] Ticking the file in the list ticks every hunk checkbox and the summary reads "All hunks move to new commit" (the diff does not change or scroll); unticking returns to empty boxes
- [ ] On a multi-hunk file, clicking one hunk's gutter checkbox ticks it in place (no flicker, scroll position kept), the summary reads "1 of N hunks move to new commit", and the file (and its directory nodes) turn **half-checked** in the list and count as partial in the dialog summary, **without** opening Pick Hunks…. Ticking every hunk one by one makes the file **fully ticked** (filled box, no partial); unticking them all makes it fully unticked
- [ ] Ticking one hunk inline and then opening **Pick Hunks…** opens it already showing that exact partial; applying a different whole-hunk selection makes the gutter boxes match it. After a **line-level** edit in Pick Hunks…, the file shows **no gutter boxes** and the summary reads "Custom pick moves to new commit (use Pick Hunks… to edit)"
- [ ] Hovering a checkbox shows the tooltip "Move this hunk to new commit"; in `-B` (new parent) and "Create parallel commits" modes, ticked hunks still end up in the **new** commit (`jj show` after Split)
- [ ] In light and dark themes and at a larger UI zoom, checkboxes are crisp and aligned with the first line of each hunk (a pure-deletion hunk is anchored at the line after the deletion)

#### Entry points

- [ ] Selecting one or more files in the working-copy / commit-details file list and right-clicking → **Split into New Child** opens the dialog with exactly those files **ticked**; Split leaves the new child commit with only the selected files and the parent with the rest
- [ ] Right-clicking a **mutable** log row shows **Split into New Parent…** directly below **Split into New Child…**. It opens the "Split into New Parent" dialog with **nothing ticked**, no "Create parallel commits" checkbox but with "Pick Hunks…" shown. Ticking a file and splitting shows in `jj log` a new commit inserted as the parent holding that file, while the original row keeps its own change ID. The entry is disabled on an **immutable** row and when **multiple** rows are selected (the same gating as "Split into New Child…"). The Working Copy toolbar's Split button is unchanged (still child mode) (GitHub #132)
- [ ] Selecting one or more files and right-clicking shows **Split into New Parent…** alongside **Split into New Child…** (GitHub #74). It opens the dialog with exactly the selected files **ticked**, the same starting tick state as "Split into New Child", not inverted. This uses `jj split -B`: the ticked fileset becomes a **new** commit inserted before the source, and everything else stays on the source's own change ID and location

#### Split into New Parent

- [ ] The dialog title reads "Split into New Parent" and the note under "Source" reads "The existing commit (&lt;shortid&gt;) keeps its change ID and position; ticked files move to a new commit inserted below it." The ticked description editor is labelled **"Description for new commit (parent of &lt;shortid&gt;)"** and the unticked one **"Description for existing commit (&lt;shortid&gt;)"**
- [ ] The **"Description for existing commit …"** block is on top and **"Description for new commit …"** below, the reverse of "Split into New Child" (new-commit block on top), matching each side's position in the log: the existing commit keeps the more recent position, the new commit becomes the older parent one row further down. The "Create parallel commits" checkbox is **not shown** (mutually exclusive with `-B`)
- [ ] "Pick Hunks…" **is shown** (GitHub #139, #132). On a file with two separate hunks, clicking it opens a 3-pane dialog with **New commit (live) in the middle** and "Stays in &lt;existing&gt;" on the right; moving one hunk into the middle pane (right-gutter arrow, tooltip "Move this hunk to New commit") and Apply shows the file half-checked, and Split leaves the **new parent** holding only that hunk and the original change ID keeping the other (`jj show`/`jj diff -r`). Picking *all* hunks into the middle pane ticks the file and moving them all back out unticks it
- [ ] A hunks-only `-B` split with **nothing ticked** but one file partially picked enables OK. Splitting a revision where a **ticked file is a deletion** and another file is partially picked leaves the new parent showing the deletion (`D`), not an empty file
- [ ] Splitting via `jj log`/`jj show`: the ticked files land in a **new commit inserted as the parent** of the original, and the original commit (unticked files) keeps its **own change ID** with the new commit as its parent. Repeating on the working copy `@` leaves `@` on the original change ID with the remaining hunk
- [ ] Editing the new-commit description field and splitting gives the new commit that description (passed as `-m`); an unedited existing-commit field leaves the original commit's description untouched. Editing the existing-commit field updates the original commit's description after the split (chained via a follow-up `jj describe` on the same, unchanged change ID)
- [ ] **Splitting the working copy itself**: an info line under the source commit reads "The working copy (@) stays on the existing commit (&lt;shortid&gt;); the new commit becomes its parent", and after splitting `@` is still on the original change ID (not the new parent). For comparison, "Split into New Child" on the working copy reads "The working copy (@) moves to the new commit" and `@` has genuinely moved after splitting
- [ ] Selecting **every** changed file opens the dialog with every file ticked, which trips the "at least one file must stay here" validation (nothing would be left at the original change ID); selecting **no** files trips "move at least one file to the new commit". Both are expected, not bugs

#### Hunk picking with the 3-pane arrow picker

- [ ] **Pick Hunks…** opens a dialog titled "Pick Hunks — &lt;filename&gt;" with **three** panes: Before | Existing commit (live) | Moves to New commit (the same identity-first labels as the main dialog, not "Parent"/"Child"). On a freshly-opened **unticked** file the Existing-commit text equals the New-commit pane's and every hunk shows a Before|Existing-commit divider bar with a **right arrow**
- [ ] Clicking a right arrow flips that hunk's Existing-commit content to Before's text, the Before|Existing-commit bar for that hunk disappears, and a **new** Existing-commit|New-commit bar with a **left arrow** appears in its place. Clicking the left arrow reverses it; this is reversible any number of times, in either direction, independently per hunk
- [ ] Typing directly into the Existing-commit pane is rejected (view-only; arrow clicks are the only way to change it, so the result is never a hand-edited hybrid)
- [ ] **Apply** with a mix of moved and unmoved hunks closes the dialog immediately with **no confirmation dialog of any kind** (no "Save changes and mark the conflict resolved anyway?" prompt). The file then shows **half-checked** in the file list and the summary shows "(N partial)", and its tick state is **unchanged** by a partial pick (unticked before means unticked after)
- [ ] Moving every hunk to the new commit and Apply fully ticks the file (no half-check), same as ticking it directly. Moving none (or reversing back to none) and Apply leaves the file fully ticked or unticked to match its starting state, with no partial override left over
- [ ] **Cancel** closes immediately with **no confirmation dialog**, and file state (tick plus any prior override) is unchanged. Reopening "Pick Hunks…" on a file with an existing partial selection opens already showing the exact prior split
- [ ] Split (linear) leaves the new commit with only the hunks left pointing at it and the existing commit with the rest; the log refreshes selecting the newly created change
- [ ] With a file changed partially and a **second file deleted** in the same change, picking hunks for the first file only and leaving the deletion **unticked** makes the first commit delete the file (log details show it as deleted, not as an empty file); ticking the deleted file instead puts the deletion in the new commit and not the first
- [ ] Opening any ordinary diff elsewhere (log → Show Diff, a working-copy file diff) shows **no arrows** and behaves as usual (the arrow overlay is a plugin-wide `diff.DiffExtension` gated to fire only inside this picker)

#### Descriptions

- [ ] Both description fields are pre-populated with the source commit's description. Each editor has exactly **one** label above it ("Description for new commit (child of &lt;shortid&gt;)" / "Description for existing commit (&lt;shortid&gt;)" in the default mode), not two stacked lines. "Existing commit" is the same name used for that side everywhere in the dialog (preview pane title, summary line, hunk picker)
- [ ] The new-commit description field appears **above** the existing-commit field (matching the new commit's position above the source in the log in the default, child mode). Editing the new-commit field updates the new commit; editing the existing-commit field updates the source commit
- [ ] Both fields are real commit-message editors, not plain text areas: a long subject line is highlighted, misspellings get a spellcheck squiggle, and Enter inserts a newline (GitHub #46)

#### Parallel split

- [ ] Checking "Create parallel commits" switches the ticked-side description label from "Description for new commit (child of &lt;shortid&gt;)" to "Description for new commit (sibling of &lt;shortid&gt;)" and the mode note under "Source" to "The existing commit (&lt;shortid&gt;) keeps its change ID and position; ticked files move to a new commit created beside it. Any existing children become merges of both.", and (when splitting `@`) updates the working-copy note, all **immediately** on toggling, with no separate event needed. The unticked-side label stays "Description for existing commit (&lt;shortid&gt;)". Unchecking reverts the labels and note live to the child wording
- [ ] In parallel mode a fully-ticked file's preview still shows every hunk ticked and the summary "All hunks move to new commit", unaffected by the mode (the preview always says "new commit", never "child"/"parent")
- [ ] Splitting in parallel mode a target that has an existing child leaves that child with **two parents** (both new siblings) per `jj log`, i.e. it has become a merge (contract test: `MutatingCommandsContractCliTest` "split --parallel makes an existing child a merge of both new siblings"). Two sibling commits are created, not parent/child
- [ ] Splitting the working copy itself in parallel mode: the working-copy note ("The working copy (@) moves to the new commit") is accurate, and after splitting `@` is on the new **sibling**, not on the side that kept the original change ID (contract test: "split --parallel on the working copy…")

→ automate: jj-idea-ygtw (validation, whole-file fast path, and binary gating below are
state/routing logic, not rendering)

#### Validation, whole-file fast path and binary files

- [ ] With nothing ticked, OK is disabled with a message to move at least one file to the new commit; with everything ticked (no overrides), OK is disabled with a message that at least one file must stay here. Both messages are the same in every mode (default, parallel, "Split into New Parent")
- [ ] With nothing ticked but one file partially picked via "Pick Hunks…", OK is **enabled** and splitting produces a new commit with just those hunks (GitHub #117)
- [ ] With no partial hunk selection (all files fully ticked or unticked), the split completes via file-level `jj split` (no diff-editor overhead); both commits have the expected files in the log
- [ ] A binary file in the changed list shows no "Pick Hunks…" button (whole-file only)

### MT-BOOKMARK

**Bookmark widget**

**Code:** `ui/toolbar/JujutsuBookmarkToolbarWidget.kt`, `ui/statusbar/JujutsuBookmarkStatusBarWidget.kt` + `JujutsuBookmarkStatusBarWidgetFactory.kt`, `actions/bookmark/BookmarkMenu.kt`, `actions/bookmark/`, `actions/bookmark/pushBookmarkAction.kt`, `jj/ClosestBookmarks.kt`, `jj/JjFeature.kt`, `ui/workingcopy/WorkingCopyControlsPanel.kt` (Advance Bookmark toolbar button)
**Also re-run:** MT-LOG-REFRESH (label reactivity relies on the same auto-refresh path); MT-CROSS (multi-repo dropdown structure); MT-WORKINGCOPY (Advance Bookmark toolbar button); MT-GIT (push confirmation dialogs triggered from this action); MT-LOG-FILTER (the bookmarks panel's "Filter Log to Bookmark" action); MT-LOG-TABLE (log tab layout — the bookmarks panel adds a splitter); MT-SETTINGS (persisted per-window panel-visibility state)

The bookmark widget lives in the main IDE toolbar (next to where Git's branch widget would sit), not the log window's filter row (GitHub #62). A status-bar fallback takes over when the main toolbar is hidden or unavailable.

#### Single-repo project

- [ ] A "\<name\>" label appears in the **main IDE toolbar** (not the log toolbar) when `@` has a local bookmark. Settings → Keymap → search "Jujutsu" shows **Jujutsu Bookmark** by that name, not the raw action id `Jujutsu.MainToolbarBookmarks`. The log window's filter row (Root/Reference/Author/Date) has no Bookmark chip
- [ ] After `jj new` off a bookmarked change with nothing left ahead of it, the label shows "\<name\> +1", where `<name>` is the nearest ancestor bookmark and `+1` the number of changes since it (GitHub #62). Two bookmarks equally close to `@` (e.g. either side of a merge) are listed comma-separated before the shared `+N`. The label is blank only when `@` has no bookmark anywhere in its ancestry
- [ ] The label updates reactively: `jj bookmark create foo` in the terminal changes it to "foo" within ~300 ms, with no file save or restart (see MT-LOG-REFRESH), and a following `jj new` updates it to "foo +1" the same way
- [ ] Clicking the widget opens a dropdown with "Create Bookmark Here…", then "Advance Bookmark" at the top, then **all** local bookmarks in the repo (not just those on `@`, and including bookmarks beyond the log limit), each as a sub-menu. A bookmark **on `@`** has Advance, Rename…, Delete, Forget (no Move Here); one **not on `@`** has Move…, Advance, Rename…, Delete, Forget. Remote bookmarks (e.g. `master@origin`) are folded into the matching local bookmark's sub-menu as Track/Untrack, not shown as separate top-level items
- [ ] "Create Bookmark Here…" (enter a name, confirm) creates the bookmark at `@` and the label and log decorations update; Rename… renames it in the log and label; Delete removes it (the label reverts to blank if it was on `@`); Forget (remote entry) removes remote tracking
- [ ] Create/Delete/Forget menu items show distinct bookmark-pennant icons (plus/cross/minus overlay), not the platform's generic +/trash/- icons. A bookmark deleted locally but not yet pushed (`jj bookmark delete X`) shows a dashed/hollow pennant in the log chip in addition to the strikethrough text, and a conflicted bookmark (e.g. after a divergent fetch) shows a red forked-tail pennant instead of the generic red warning triangle. Both stay legible at 125%/150% IDE text size and in Light and Dark themes

#### Multi-repo project

- [ ] The widget is present in the main toolbar, and its label is blank regardless of which bookmarks exist (the "name +N" fallback applies to a single-repo project only; another repo's ancestry must never leak in). Clicking it shows one sub-menu **per repo**, named by repo display name, each with the single-repo structure: "Create Bookmark Here…", "Advance Bookmark", then that repo's bookmark sub-menus
- [ ] "Create Bookmark Here…" inside repo-a's sub-menu creates the bookmark at **repo-a's** working copy, not repo-b's (check `jj bookmark list` in each). Rename/Delete/Forget in repo-b's sub-menu affects only repo-b
- [ ] After `jj new` past every bookmark in repo-a only (repo-b still has one on `@`), repo-a's "Advance Bookmark" is enabled and targets repo-a's nearest bookmark, while repo-b's advances repo-b's bookmark, unaffected by repo-a
- [ ] After `jj config set --repo revsets.bookmark-advance-to '@-'`, advancing a bookmark lands it on `@-` (not `@`) and the notification reads "Advanced 'name'" with no destination; after unsetting the config it lands on `@` (GitHub #146)

#### Status-bar fallback

**Code:** `ui/statusbar/JujutsuBookmarkStatusBarWidget.kt`, `JujutsuBookmarkStatusBarWidgetFactory.kt`

- [ ] In the New UI, unchecking Settings → Appearance & Behavior → "Show main toolbar" removes the main-toolbar bookmark widget and shows an equivalent widget in the status bar (bottom of the IDE window), live, without restarting; re-checking reverses it. In the Classic UI (no main toolbar at all) the status-bar widget is present. With the main toolbar visible (New UI default) the status-bar widget is **not** shown
- [ ] The status-bar widget shows the same text as the main-toolbar widget would (bookmark on `@`, or nearest-ancestor "name +N") and opens the identical dropdown (Create, Advance, per-bookmark Move/Rename/Delete/Forget/Track)
- [ ] In a non-jj project neither widget appears; running **Jujutsu → Init** there makes the main-toolbar widget (or its status-bar fallback, per the toolbar-visibility setting) appear immediately, without restarting the IDE (GitHub #100)
- [ ] The status-bar fallback's padding, text colour, hover fill and pressed fill match a stock status-bar widget (e.g. the encoding widget) at rest, on hover and while the mouse button is held down, in both a light and a dark theme; dark-theme hover goes **lighter**, not darker (GitHub #95)

#### Advance Bookmark (GitHub #61)

- [ ] With exactly one bookmark closest to `@`, "Advance Bookmark" moves it directly to `@` with no dialog (confirm via `jj bookmark list` or the log decoration). With two or more equidistant closest bookmarks (e.g. `jj new` off a merge of two bookmarked branches) it opens a picker listing all of them, pre-checked, and unchecking one advances only the checked ones
- [ ] The per-bookmark "Advance …" action (in a bookmark's own sub-menu, or via right-click on its chip in the log) moves that specific bookmark to `@` regardless of distance, without a picker. Advancing a bookmark already at `@` is a no-op with no error
- [ ] With no bookmark anywhere in `@`'s ancestry, "Advance Bookmark" is visible but disabled, with a tooltip explaining there is nothing to advance, and "(nothing to advance)" appended to the item's own text
- [ ] **Version gating**: with a jj executable below 0.39 configured (Settings → Version Control → Jujutsu → jj executable path), both "Advance Bookmark" and the per-bookmark Advance action are visible but disabled, with a tooltip naming the required version and your current one. The reason is also appended to the item's text, since menus don't reliably show tooltips (e.g. "Advance Bookmark (needs jj 0.39+)", "Advance 'main' (needs jj 0.39+)"). Settings → Version Control → Jujutsu → Install/Upgrade shows the correct upgrade command for your detected install method (see also MT-WORKINGCOPY "Version-Gated Feature Upgrade Nudge" for the startup balloon this gating also surfaces)
- [ ] The Working Copy tool window's toolbar has an **Advance Bookmark** icon button alongside New Change/Split/Squash/Abandon/Create Bookmark/Set Tag (see MT-WORKINGCOPY) that behaves like the widget's menu item, including the picker for equidistant bookmarks and version gating; switching the bound repository via the panel's dropdown in a multi-root project re-evaluates the button against the newly selected repo (GitHub #61)
- [ ] The tooltip names the actual bookmark(s) it would move: "Move 'main' forward to the working copy (jj bookmark advance)" for one candidate, "Move 'main', 'feature' forward…" (each name individually quoted) for two or more, from both the widget's menu item and the Working Copy toolbar button
- [ ] After a successful advance (the direct single-bookmark path **and** after confirming the picker), a balloon reads "Bookmark Advanced" / "Advanced '\<name\>' to \<shortid\>", where \<shortid\> matches `jj log -r @`'s change id. This is deliberately the *only* Working Copy toolbar action with this treatment, since it has no dialog on its common path and no other visible effect in that panel
- [ ] The Working Copy toolbar's Advance button with exactly one nearest bookmark first shows an "Advance Bookmark" Yes/No confirmation naming the bookmark: Yes advances (and shows the balloon above), No/Escape leaves the bookmark untouched. The same entry from the widget's dropdown or the log's right-click menu advances immediately with **no** confirmation (more deliberate two-step clicks than an icon-only button). The equidistant-candidates picker is its own confirmation

#### Move direction (forward / backward-sideways / resolve)

Covers `actions/bookmark/MoveBookmarkDialog.kt`, `MoveBookmarkToChangeDialog.kt`, `BookmarkClassifier.kt`.

**Divergent-bookmark recipe** (also used by the Bookmarks panel below): create the bookmark on a **third** revision, a common ancestor of the two you are about to diverge it to. If it already targets `<rev-a>` when you capture `$OP`, the first `set` is a no-op ("Nothing changed."), jj records no operation, and nothing diverges.

```sh
jj bookmark create conflicted-bm -r <common-ancestor-rev>
OP=$(jj op log --no-graph --limit 1 -T 'id.short()')
jj bookmark set conflicted-bm -r <rev-a> --allow-backwards --at-op "$OP"
jj bookmark set conflicted-bm -r <rev-b> --allow-backwards --at-op "$OP"
```

`jj bookmark list` should print `conflicted-bm (conflicted):` with two `+` targets (`<rev-a>` and `<rev-b>`) before you check anything.

- [ ] Right-clicking a commit that is a **descendant** of an existing bookmark → "Move Bookmark Here…" shows the bookmark row with the forward (move-up) icon at full opacity, selectable, and OK enables **without** ticking "Allow backward or sideways move". Right-clicking an **ancestor** instead greys the row out with the warning icon, selectable only after ticking the checkbox (OK stays disabled otherwise)
- [ ] Right-clicking a bookmark → "Move '\<bookmark\>' to Change…" shows descendants of the bookmark's current position as forward/selectable and ancestors greyed with the warning icon until the checkbox is ticked
- [ ] In a repo containing a divergent change (`jj log` shows `(divergent)` on some commit), the forward/backward classification above still works for bookmarks unrelated to the divergent change, and does not blank out to "everything backward". Confirming a forward move without the checkbox runs `jj bookmark set` without `-B` (check `jj op log`), with no "backwards or sideways" retry prompt
- [ ] Confirming a move from **both** dialogs shows an undo balloon reading "Move bookmark", and Undo puts the bookmark back where it was
- [ ] Resolving a divergent bookmark onto one of its own targets (GitHub #121; use the recipe): right-clicking `<rev-a>` → "Move Bookmark Here…" lists `conflicted-bm` under a **"Resolve conflict"** section header, with the red conflict glyph instead of the forward/backward icon, at full opacity, and OK enables **without** ticking "Allow backward or sideways move"; confirming leaves a single target in `jj bookmark list`. Right-clicking `<rev-b>` (after re-diverging) behaves the same, reading as a resolve rather than an ordinary backward move
- [ ] Right-clicking `conflicted-bm` itself → "Move 'conflicted-bm' to Change…" lists both `<rev-a>` and `<rev-b>` together under **"Resolve conflict (bookmark is divergent)"**; every other commit sits under Backward/Sideways behind the checkbox and none under Forward (a conflicted bookmark never classifies FORWARD). A **non-divergent** bookmark's own current change is still absent from both dialogs, and ordinary forward/backward sectioning is unchanged

#### Pushing a bookmark (GitHub #81)

`pushBookmarkAction` opens the same Push dialog as MT-GIT, pre-selected to "Specific bookmark" scope with this bookmark (and the chosen remote) already selected, but still requires an OK click before anything is pushed (pushing mutates a shared remote). It is reachable wherever a bookmark's other actions (Rename…, Delete, Forget) are: its sub-menu in this widget, the log row's Bookmark submenu, and a right-click on its chip in the log.

Whether there is anything to push is evaluated **per remote**, using that remote's own `name@remote` tracking entry, deliberately not the local bookmark's aggregate ahead/behind count, which in a **colocated** repo (this plugin always colocates) is always `0` because jj auto-tracks a same-commit `@git` remote alongside any real ones. Getting this wrong makes every bookmark look permanently up to date; the checks below exist to catch that.

Setup: a repository with a local bookmark tracked against exactly one Git remote and, for the multi-remote cases, a second remote (`jj git remote add <name> <url>`). The `/tmp/jj-idea-push-test` scratch repo has all three states: `main` (tracked, in sync on both remotes), `feature` (pending deletion, still present on both remotes), `new-thing` (never tracked anywhere).

- [ ] Right-clicking a bookmark's chip (or opening its sub-menu from this widget) shows a Push entry alongside Rename…/Delete/Forget, with the same push icon as the toolbar Push action. With exactly one Git remote it is a single "Push '\<name\>' to \<remote\>..." entry (not a submenu); clicking it opens the Push dialog with "Specific bookmark" scope, this bookmark and this remote already selected, so OK is enough. Cancelling the pre-populated dialog pushes nothing
- [ ] With two or more remotes the entry becomes a "Push '\<name\>' to ▸" submenu with one item per remote, each independently labelled and enabled, and a leading "Push '\<name\>' to all remotes" entry plus a separator. The "all remotes" entry skips the Push dialog and goes straight to a dry-run push per remote that has something to push, so the usual force-push / deletion / untracked-bookmark confirmations still fire, one per affected remote. With one remote there is no "all remotes" entry
- [ ] A bookmark up to date on `origin` (`main@origin` at the same commit as local `main`) shows that remote's entry **disabled** with "(up to date)" appended, **even though** the same bookmark is also tracked by the colocated `@git` remote at the same commit (the regression case: it must not become "always enabled because `@git` matches"). If it is ahead on a second remote (`main@github` behind local `main`), **that** remote's entry is enabled while `origin`'s stays disabled
- [ ] A bookmark never tracked or pushed to a given remote still shows an **enabled** entry for it despite having nothing to compare against; confirming creates it there with the usual "will create a new remote bookmark" confirmation from MT-GIT
- [ ] "All remotes": a bookmark up to date everywhere shows the entry **disabled** with "(up to date)"; one ahead on only one of several remotes shows it **enabled** and pushes only that remote (verify via `jj git remote list`/the remote's log), silently skipping the up-to-date one; one moved backwards/sideways on a remote it would push to shows the same force-push confirmation as a per-remote push, scoped to that remote
- [ ] A pending-deletion bookmark (`jj bookmark delete <name>`, still shown strikethrough in the log) that is still present on a remote shows that remote's entry **enabled** (deletions don't show up in `aheadCount`) and opens the dialog with `<name> (deleted)` pre-selected; confirming shows the same "will be deleted from the remote" warning as MT-GIT, then removes the bookmark from that remote. A remote the deletion was already pushed to (or that never had the bookmark) shows its entry **disabled**

#### Bookmarks panel (GitHub #48)

**Code:** `ui/log/bookmarks/JujutsuBookmarksPanel.kt`, `ui/log/bookmarks/BookmarkTreeModel.kt`, `ui/log/bookmarks/BookmarkNodeTooltip.kt`, `ui/log/bookmarks/BookmarksStripeButton.kt`, `actions/bookmark/bookmarkLogActions.kt`, `actions/bookmark/deleteBookmarkAction.kt`, `actions/bookmark/forgetBookmarkAction.kt`, `actions/bookmark/renameBookmarkAction.kt`, `actions/bookmark/advanceBookmarkAction.kt`, `actions/bookmark/toggleTrackBookmarkAction.kt`, `actions/bookmark/pushBookmarkAction.kt` (registered, keymap-assignable counterparts), `actions/EnterBoundAction.kt`, `actions/JujutsuDataKeys.kt` (`BOOKMARK_TARGET`/`BOOKMARK_TARGETS`), `ui/common/CommitTablePanel.kt` (`installLeftComponent`), `settings/LogWindowConfig.kt` (`bookmarkNodeExpanded`), `jj/cli/CliLogService.kt` (`bookmarkListTemplate`, `localBookmarkTemplate`, `remoteBookmarkTemplate`), `jj/BookmarkDivergence.kt` (`withDerivedDivergence`), `jj/Revset.kt` (`Bookmark.withDivergenceFrom`), `ui/log/UnifiedJujutsuLogDataLoader.kt` (`enrichBookmarks`), `jj/ClosestBookmarks.kt` (`danglingHeads`)
**Also re-run:** MT-LOG-TABLE, MT-LOG-DETAILS (the commit-row tooltip and details panel also show a dangling-head's "N commits ahead of..." status, via `ui/log/JujutsuGraphAndDescriptionRenderer.kt`/`ui/log/JujutsuCommitDetailsPanel.kt` reading `JujutsuStateModel.danglingHeads`)

A tree of bookmarks/tags to the left of the log table in the Jujutsu log tab, modelled on git4idea's Branches dashboard. Expanded by default (matching the root gutter's default). A narrow always-visible strip with a bookmark icon sits at the far left, outside the panel's own splitter; clicking it toggles the panel even while collapsed, so something is always on screen to bring it back. Plain selection does nothing; right-click for actions.

- [ ] The panel starts expanded on a fresh log tab. Clicking the bookmark-icon strip collapses it while the strip stays visible (tooltip switches to "Expand Bookmarks Panel"), and clicking again re-expands. "Show Bookmarks Panel" in the toolbar's View Options popup (below the Details Position toggles) reflects and controls the same state, and the expanded/collapsed state is restored per log window after an IDE restart
- [ ] With bookmarks `feature/A`, `feature/B`, `fix/C`, a "Local" group contains a `feature` group (with `A`, `B`) and `fix` (with `C`), not three flat entries. A tracked `main@origin` appears under an `origin` group, `/`-grouped the same way, and tags appear under their own "Tags" group, also `/`-grouped
- [ ] An "@" node at the top shows the same text as the main-toolbar bookmark widget (e.g. "main" or "main +3"); creating or deleting a bookmark updates both together. Its label is bookmark-coloured followed by a bold "@" glyph in the log's own working-copy colour (compare side by side with the "@" the log table appends after a working-copy row's chips)
- [ ] Local/remote bookmark leaves and their "Local"/remote-name folder groups render in the same brownish colour as bookmark chips in the log table, and tag leaves and the "Tags" group in the same greenish colour as tag chips. A bookmark sitting on `@` renders **bold** (still bookmark-coloured)
- [ ] In a **colocated** repo no `git` group appears among the remote groups (only real remotes such as `origin`/`github`), and a bookmark tracked by both `@git` and a real remote still appears correctly under the real remote
- [ ] An **untracked** remote bookmark shows the untracked icon (not the tracked one), and right-clicking it offers **Track**, not Untrack. A bookmark ahead/behind its remote shows `↑n`/`↓m` on its leaf, including a **local** bookmark ahead of its tracked remote on its own leaf under Local (advance the local bookmark past its remote with `jj bookmark set <name> -r <newer-rev>` without pushing, and confirm the Local leaf picks up `↑1`; GitHub #110)
- [ ] A conflicted/divergent local bookmark (use the recipe in "Move direction") still appears under Local with the same red conflict icon the log table shows for it, as a **single** Local node (not two). Its right-click actions are unaffected, except Move Bookmark Here…/Move '\<bookmark\>' to Change…, whose own divergent-resolve behaviour is covered under "Move direction"; every target is reachable by drag. Side by side with the log table, its `↑n`/`↓m` on the Local leaf are **identical** in both and read as the counts a non-divergent bookmark sitting at `<rev-a>` would show relative to `<rev-b>` (real bidirectional divergence, not jj's one-directional `tracking_ahead_count`/`tracking_behind_count` hint)
- [ ] With `[remotes.origin] auto-track-created-bookmarks = "*"` set (`jj config edit --repo`), a brand-new local bookmark that is not pushed shows no arrow or number on its Local leaf or its `@origin` leaf, collapsed or expanded, in either the panel or the log (the "↑1000+" bug repro), while a normal ahead/behind bookmark elsewhere in the repo is unaffected
- [ ] With a tracked bookmark whose remote moves outside the IDE (e.g. `git fetch` in a colocated repo, or a push from another clone then `jj git fetch` in a terminal), clicking the log toolbar's Refresh **once** updates its `↑n`/`↓m` in the bookmarks panel **and** on the log table's chip, without a second Refresh (GitHub #128)
- [ ] Renaming/creating/deleting a bookmark in the terminal updates the tree reactively, without a manual refresh (the auto-refresh path from MT-LOG-REFRESH). In a multi-repo project there is one top-level group per repository (with its icon), each with its own Local/remote/Tags structure; a single-repo project has no such wrapper level. Typing while the tree has focus speed-searches (jumps to or filters matching names)
- [ ] With 2+ real remotes each remote group starts **collapsed** while "Local" and "Tags" start expanded. Manually collapsing "Local" and expanding a remote group persists across an IDE restart (or closing and reopening the log tab), per log window. The panel's toolbar **Expand All** / **Collapse All** buttons expand or collapse every group at once (including remote groups) and persist the same way
- [ ] A collapsed remote group containing an ahead/behind or untracked bookmark shows a roll-up `↑n`/`↓m` (or a plain dot if only untracked) on its own row, in the same divergence colour as the leaf chips; expanding the group hides the roll-up and shows the per-bookmark indicators instead

Actions:

- [ ] Right-clicking a local bookmark shows the same actions as its dropdown sub-menu (Move…/Advance/Rename…/Delete/Forget, minus Move… when it is on `@`), plus "Filter Log to Bookmark" and "Navigate Log to Bookmark" at the bottom, plus New Change, New Change..., Edit Change, Rebase and Duplicate (reusing the log toolbar's action instances, shortcut hints included). "Filter Log to Bookmark" narrows the log the same way the reference filter does; "Navigate Log to Bookmark" scrolls and selects that bookmark's change, including one outside the loaded window (an expanding load, as clicking a bookmark chip does)
- [ ] Right-clicking a remote bookmark shows Track/Untrack plus Filter/Navigate (and the change actions); a tag shows Delete plus Navigate (no Filter, since tags aren't a log filter reference here) plus the change actions; the "@" node shows Create Bookmark Here…, Advance Bookmark
- [ ] Selecting a bookmark or tag whose change is **not** in the currently loaded log window (e.g. a far-back bookmark in a filtered or paginated log) and right-clicking still shows New Change/Edit/Rebase/Duplicate acting on that change (the menu may open a moment late while it is fetched); an immutable target still has Edit/Rebase disabled, and a hidden (amended-away) remote target offers none of them. With a different row selected in the log (e.g. `@`), these actions and their keymap shortcuts never act on that log selection while the bookmark row is focused: before the fetch completes they are disabled, not run against the log's row (jj-idea-2cuy)
- [ ] Ctrl+Click on two bookmark rows whose changes are both loaded, then "New Change From These", creates a merge change with both as parents. Ctrl+Click two bookmark rows and right-clicking **inside** that selection preserves the multi-selection (not collapsed to the row under the cursor)
- [ ] Settings → Keymap → "Jujutsu" lists Navigate to Bookmark's Change, Filter Log to Bookmark, Delete/Forget/Rename/Advance/Push Bookmark to All Remotes and Track/Untrack Bookmark, all rebindable, and right-click menus show the keymap shortcut hint next to any that are bound, as New Change/Edit/Rebase do in the log (GitHub #48). Double-clicking a bookmark row navigates the log to its change (same as "Navigate Log to Bookmark"); rebinding Enter in Keymap settings to a different bound action makes double-click follow the new binding
- [ ] With an Issue Navigation pattern configured and a bookmark named `JIRA-123-fix-thing`, the `JIRA-123` portion of its label renders as a link (same styling as the log table/description) and the rest does not; hovering it shows a hand cursor and clicking opens the issue URL in the browser (full checks: MT-LOG-TABLE "Issue-tracker links"). Same for a tag named that way and for the "@" node when the bookmark on `@` has such a name. Clicking elsewhere on the row (icon or non-linked text) opens nothing, and double-clicking the linked portion opens the browser, not the Enter-bound action

#### Unbookmarked heads (GitHub #107)

- [ ] After `jj new` off a bookmarked change twice without moving the bookmark (two unbookmarked heads), an "Unbookmarked heads" group appears right after the "@" node and before "Local", listing both, each as "\<closest bookmark\> +n \<change id\>" (bookmark-coloured label, change id in the log's bold-prefix/grey-remainder style). A dangling head with no ancestor bookmark at all (e.g. a root-adjacent disconnected change) shows "(no bookmark) \<change id\>" instead of a `+n` count. `jj bookmark set \<name\> -r \<that head\>` in a terminal removes its row after the auto-refresh
- [ ] Double-clicking an unbookmarked-head row scrolls and selects that change in the log, including one outside the loaded window (the same expanding load as "Navigate Log to Bookmark"). Right-clicking it offers "Navigate Log to Bookmark", "Create Bookmark Here…" (bookmarking it removes it from the group) and the same New Change/Edit/Rebase/Duplicate actions as a bookmark row
- [ ] The group is collapsible via the toolbar's Expand All/Collapse All and persists its collapsed state across an IDE restart, like Local/Tags/a remote group. With more than 10 unbookmarked heads in the repo (`scripts/fixtures/fx-stress.sh` has many concurrent unbookmarked branch tips) it caps at 10 rows instead of growing unbounded
- [ ] After `jj bookmark create` with a ~50-char name and `jj new` twice off it without moving the bookmark, the main-toolbar widget truncates the name with `…` but still shows the trailing `+n`, while the panel's "@" row and "Unbookmarked heads" row show the **full, untruncated** name plus `+n` (the panel lays out by available width, not the toolbar's fixed char cap)

#### Bookmark-state tooltips (GitHub #110)

Hovering any row shows a compact tooltip, rendered by the same icon-aware pane the log table's row tooltip uses (see [Hover tooltips](#hover-tooltips) for its dismissal and scroll behaviour, which applies here too): a `[GroupLabel, ...]` bracket line (with an icon), the bookmark's full `/`-qualified path, and (as in the commit tooltip) the target commit's change id/commit id/author/date/description.

- [ ] Hovering the Local leaf of a nested bookmark (e.g. `branches/foo/bar`) shows `[Local]`, then the full `branches/foo/bar` path (not just `bar`) with the same chip icon/arrows the row shows, then change id/commit id/author/date/description matching the log row's own tooltip for that change. Hovering the "foo" prefix node above it shows `[Local]` (not `[foo]`/`[branches]`), then `branches/foo`, then its own leaf count (e.g. "1 bookmark"), with no commit info (a folder isn't a single change). Hovering the matching `@origin` leaf shows `[origin]`; hovering a tag shows `[Tags]`, the full `/`-qualified tag path, no "Tag" text, plus its target commit's info
- [ ] Collapsing "Local"/a remote/"Tags" and hovering the folder shows a bracket naming the group alone when nothing is notable; with a divergent or untracked bookmark inside, the bracket adds `↑n↓m`/`N unsynced`, matching the in-row collapsed badge exactly. Below it a leaf-count line ("N bookmarks"/"N tags", singular for exactly one) counts every leaf transitively, including inside collapsed or nested sub-groups
- [ ] Tracking a local bookmark to two remotes in different states (e.g. `origin` in sync, `github` behind after moving the bookmark backwards there with `--allow-backwards`) and hovering the Local leaf shows below the chip one bracket line per remote: `[origin, in sync]`, `[github, ↓1, force-push required]`. A remote with no ahead/behind shows "in sync", a remote the local is behind (whether or not also ahead) adds "force-push required", and a bookmark tracked by no remote shows no such lines. Hovering the `github` leaf itself under its remote category also flags "force-push required" when behind
- [ ] Hovering the "@" row on a bookmark directly shows the bookmark name(s) with no distance bracket; after `jj new` past it, it shows a compact `[N commit(s) ahead of <bookmark>]` bracket instead (singular "1 commit", otherwise "commits"; no comma before a single name, comma-separated for several). Both cases include `@`'s own commit info with a `[@ Working Copy]` tag matching the log row tooltip's own status tag. Right-click shows **Navigate Log to Commit** (not "...to Bookmark") and double-clicking the row navigates there too
- [ ] With the "Unbookmarked heads" setup above, the category's own tooltip reads "N heads without bookmarks" (singular "1 head without a bookmark"), not a bracket. Each row in the tree, and the category header, shows the same slashed-bookmark icon the log table uses for a dangling head. A child row's tooltip uses the compact `[N commits ahead of ...]` form with that slashed-bookmark icon leading the bracket (the "@" row's own bracket does not have it, since the working copy isn't counted as a dangling head), plus commit info, and right-click shows **Navigate Log to Commit**
- [ ] With that unbookmarked head still visible in the log table, hovering its row shows a `[N commits ahead of <bookmark>]` status tag (with the slashed-bookmark icon) alongside any other status tags (Conflict/Immutable/etc.), and the commit details panel shows the identical tag. Hovering the *working-copy* row, even when it has no bookmark, shows no such tag (it has its own `@ Working Copy` tag and isn't double-counted as a dangling head)
- [ ] A single-repo project shows no repo line in any tooltip; a multi-repo project adds a leading repo icon+name line above the group bracket to every tooltip. Moving the pointer into the tooltip balloon keeps it open, and scrolling the panel without moving the pointer hides it until the pointer moves. Unchecking the log toolbar's **Hover Tooltips** (see [Columns and View Options](#columns-and-view-options)) stops both the log row tooltip and every bookmarks-panel tooltip; re-checking brings both back with no restart

#### Remote bookmark at a hidden commit (GitHub #130)

Setup: in a repo with a git remote, `jj bookmark create cl/X -r @`, add file `f` with `v1`, `jj git push --bookmark cl/X` (`--allow-new` on older jj), then edit `f` to `v2` so `@` is amended in place. `cl/X@origin` is now at a hidden commit that shares its change id with `@`.

- [ ] Hovering the `cl/X@origin` row in the panel shows tooltip commit info for the **old** commit (its commit id, the `v1`-era description), not the working copy
- [ ] Right-clicking the `cl/X@origin` row shows no New Change / Edit / Rebase / Duplicate entries (they write by change id and would hit `@`), while Track/Untrack, Filter and Navigate remain (Navigate disabled); the local `cl/X` row still offers the change actions. **Navigate Log to Bookmark** is disabled on the `cl/X@origin` row (the hidden commit is not in the log) and double-click does not move the log selection to `@`; the local `cl/X` row still navigates normally
- [ ] In the "Compare with Branch…" listing, `cl/X@origin` shows a short **commit id** in parentheses (the local `cl/X` shows its change id), and picking it compares against `v1`. Refreshing with `jj git fetch` / `jj new` keeps rows and tooltips correct

### MT-WORKINGCOPY

**Working copy panel, status bar widget, and tool window behavior**

**Code:** `ui/workingcopy/UnifiedWorkingCopyPanel.kt`, `ui/workingcopy/WorkingCopyControlsPanel.kt`, `ui/workingcopy/WorkingCopyToolWindowFactory.kt`, `ui/statusbar/JujutsuStatusBarWidget.kt`, `ui/statusbar/JujutsuWorkingCopySwitcher.kt`, `ui/services/JujutsuUiEnabler.kt`, `ui/services/WorkingCopySignpost.kt`, `ui/services/SponsorAsk.kt`, `ui/services/FeatureUpgradeNudge.kt`, `ui/services/JujutsuNotifications.kt`, `ui/services/JujutsuStartupActivity.kt`, `vcs/JujutsuHiddenCommitMode.kt` (Standard Commit Tool Window Suppression), `vcs/JujutsuVcsBase.kt`, `vcs/JujutsuRollbackEnvironment.kt`, `actions/top/InitAction.kt`, `ui/common/JujutsuChangesTree.kt`, `ui/common/JujutsuOtherRepositoriesNode.kt`, `ui/common/JujutsuNoChangesNode.kt` (repo-anchoring), `ui/common/JujutsuFilePathIconProvider.kt` (repo-root icon in changes trees), `jj/WorkingCopyRecovery.kt`, `jj/JujutsuRepositoryHealth.kt`, `ui/restore/RestoreDialog.kt`, `actions/file/RestoreSelectionAction.kt`, `actions/filechange/RestoreToChangeAction.kt` (GitHub #84), `actions/filechange/DropChangesAction.kt` (GitHub #148)
**Also re-run:** MT-DIFF-PREVIEW (changed-files tree shares the preview-tab behavior); MT-CROSS (colocated Git / multi-VCS project scoping); MT-CTXMENU, MT-SQUASH, MT-SPLIT (Split/Squash/Abandon/Create Bookmark/Advance Bookmark/Set Tag are shared with the log context menu); MT-BOOKMARK (Advance Bookmark)

#### Working Copy Panel

- [ ] On a **freshly started IDE**, before touching anything, the description box already shows `@`'s description (not just the grey placeholder) and every toolbar button (New Change, Split, Squash, Abandon, Create Bookmark) is enabled rather than greyed out
- [ ] The description area shows the current description. It is a real commit-message editor, not a plain text area: Enter inserts a newline, a long first line highlights the subject-length inspection, a misspelled word gets a spellcheck squiggle, and the toolbar's history button (Ctrl+E / Cmd+E) opens a popup of recently-used descriptions (shared with Git's commit UI and every other description editor in the plugin) (GitHub #46)
- [ ] With IdeaVim installed: describing the working copy, entering Insert mode in the description field and pressing Escape leaves Insert mode and does not close or cancel anything (the requester's headline ask on GitHub #46; if Escape does something else, note what and whether `:set ideavimsupport=dialog` changes it)
- [ ] With an Issue Navigation pattern configured (Settings → Version Control → Issue Navigation, e.g. `[A-Z]+-\d+` → `https://example.com/browse/$0`), an issue reference like `JIRA-123` inside a bookmark name in the current-change summary (above the description area) renders as a clickable link that opens the URL (GitHub #15)
- [ ] "Describe" updates the description via `jj describe`; "New Change" creates a new change via `jj new`
- [ ] The toolbar row also offers Split, Squash, Abandon, a separator, then Create Bookmark, Advance Bookmark and Set Tag, all acting on `@` and icon-only (tooltip-only labels). **Verify every one is actually visible in the row**, not collapsed behind an overflow `>>` chevron or silently missing. Each behaves like its log context-menu counterpart (see MT-CTXMENU, MT-SQUASH, MT-SPLIT, MT-BOOKMARK) and greys out (never disappears) when not applicable to the current `@`, e.g. Squash with no mutable parent, Advance with no ancestor bookmark (GitHub #61)
- [ ] In a multi-root project the repo selector dropdown sits in the same row as this toolbar (top of the tool window), visually adjacent to it. Choosing a different repo there immediately updates the toolbar buttons (each re-evaluated against the newly selected repo) and the description/current-change area below
- [ ] The changed-files tree shows correct status colours and file type icons, and right-click shows the file actions menu, which does **not** contain "Compare Before with Another Commit..." (working-copy context, same as "Compare Before with Local"). Preview-tab behaviour (double-click, Enter, tab-swap, single-click-no-op-when-closed, single-click-swap-when-open, Escape, Cmd/Ctrl+D, F4) is MT-DIFF-PREVIEW
- [ ] Open shows the working copy as editable, and Open for multiple files opens multiple editors. The menu has Open in → remote (see MT-DIFF for "Open in → remote for single parent" and the hidden-when-no-pushed-ancestor case)
- [ ] Selecting files in the tree makes the IDE's **Reformat Code** (Ctrl/Cmd+Alt+L) and **Optimize Imports** (Ctrl/Cmd+Alt+O) act on them, as in the built-in Git/Commit changes view (`JujutsuChangesTree.showsLocalFiles`). Right-clicking a file → Jujutsu submenu → **Annotate** opens the gutter annotations for that file
- [ ] In a repository colocated with Git (mapped to both Jujutsu and Git4Idea), the changed-files tree shows only jj's changes, with no Git-only changes, and "Resolve Conflicts…" (toolbar/menu action and per-file context action) offers only jj-tracked conflicted files
- [ ] In a repository mapped to a non-jj VCS (e.g. Git4Idea), selecting a change belonging to that other VCS in the Changes view and pressing F4 or opening the context menu produces no `VcsException: Not a Jujutsu revision` in the IDE log, and jj's file-change actions (Open, Compare, Restore, etc.) simply don't offer that change

##### Restore dialog (GitHub #84)

- [ ] With 3+ modified files, right-clicking one in the changed-files tree → **Restore…** opens a dialog (not a plain Yes/No confirm) listing every changed file with only the right-clicked file checked. The right-click menu and the editor's Jujutsu submenu both show "Restore…" with the ellipsis. Ticking an additional file before confirming reverts exactly the checked files to `@-` and leaves unchecked files untouched; unticking every file disables OK with a "check at least one file" message
- [ ] A **Restore** toolbar button sits on the changes tree toolbar (after Resolve all conflicts). With one or more files selected, clicking it opens the dialog with exactly those files pre-checked, like right-click Restore. With **nothing** selected it stays enabled and opens the dialog for the panel's current repo with **no** files pre-checked, so it can still be widened to restore anything; Restore from the editor's Jujutsu menu (single file) also opens the dialog pre-checked and can be widened
- [ ] A renamed file that is checked and confirmed restores **both** the old and new path, leaving no orphaned file at either location. Deleting a tracked file (`rm`) and right-clicking **only** that file still offers **Restore** (not hidden), and confirming brings it back. With one file deleted and another edited, selecting **both** → Restore opens the dialog with **both** pre-checked and restores both (GitHub #122)
- [ ] For that same deleted-only selection, **Show File History** still opens history for it (see MT-DIFF's File History section), **Squash Selected Files** / **Split** offer it pre-ticked in their dialogs, and **Open File** stays disabled (there is no file to open)
- [ ] With a modified, an added, a deleted and a renamed file selected in the changed-files tree, the platform **Rollback** (Ctrl+Alt+Z / right-click → Rollback) lists them pre-checked in the "Rollback Changes" dialog. Confirming returns modified/deleted files to their `@-` content, removes the added file, undoes the rename at **both** paths, reloads open editors and empties the tree. Cancelling changes nothing. A "Restore" balloon with an inline **Undo** link appears (one per repo) and clicking it brings the changes back; Operations Log → Undo also recovers them. In a colocated Git project, Rollback of a file under the Git root still routes to Git (MT-CROSS) (GitHub #56, #84)
- [ ] On a merge working copy (two parents) with a conflicted file, resolving it in the editor (status becomes modified) and then Rollback / Restore on that file succeeds with no "Failed to restore selection" error, and the file returns to its conflicted state
- [ ] With a clean working copy, Restore shows a "Nothing to restore" notification instead of opening an empty dialog. After confirming a restore, an undo balloon reading "Restore" appears (see MT-CTXMENU "Undo"), and its Undo link brings the discarded content back. The historical "Restore to This" variant of this dialog is under MT-LOG-DETAILS

#### Changes tree repo-anchoring (multi-repo only)

The changes tree spans every repo, unlike the toolbar and description above it (which only act on the bound repo), so changes belonging to any *other* repo are demoted into one collapsed node (`ui/common/JujutsuChangesTree.kt`'s `currentRepo`, `JujutsuOtherRepositoriesNode`, `JujutsuOtherRepositoryNode`, `JujutsuNoChangesNode`).

- [ ] With a bound repo that **has** changes, its changed files show directly at the top level of the tree (still grouped by directory), not wrapped in a repo-named node. With a bound repo that has **no** changes, the tree still shows a line for it: the repo's name followed by a grey "(no changes)" qualifier, rather than nothing
- [ ] Every *other* repo's changed files are collapsed under a single "Other Repositories" node, sorted below the bound repo's own content and collapsed by default the first time it appears. Expanding it with changes from **two or more** other repos shows each as its **own named sub-node** with the per-repo coloured icon used elsewhere (bookmark widget's multi-repo dropdown, the "(no changes)" line), directly followed by its changed files, with **no** extra node for a shared parent directory or for the repo appearing twice nested
- [ ] A file in a subdirectory of an other-repo (e.g. `src/Main.kt`) shows as just its filename ("Main.kt") directly under that repo's node, not nested under its own directory sub-tree and showing no path in its label (a deliberate trade-off for the demoted area; only the bound repo gets full directory grouping)
- [ ] Switching the bound repo via the selector re-partitions the tree immediately (files move between the top level and "Other Repositories", the no-changes line appears or disappears), with no Refresh. A single-repo project never shows an "Other Repositories" node and is otherwise unaffected
- [ ] With conflicts present (`groupConflicts`), the Merge Conflicts node (top, bold) and the Other Repositories node (bottom) coexist correctly: a conflicted file in another repo appears under Merge Conflicts, not duplicated under Other Repositories

#### Repository Initialization

- [ ] For a directory that is VCS-mapped to Jujutsu but has no `.jj` (the "uninitialized root" notification), clicking **Initialize** on the notification, picking the directory and confirming populates the Working copy tool window with the repo's changes immediately, with no further unrelated action (e.g. no `jj split` first). The same works via **VCS → Jujutsu → Initialize** on an already-mapped-but-uninitialized directory
- [ ] The Log tool window also shows the initial commit(s) immediately after Initialize, and adding or editing a file in the new repo shows the correct added/modified colour in the editor gutter and Project view immediately, without touching Settings, restarting, or triggering an unrelated VCS refresh

#### Unreadable Repository

- [ ] With a working jj repo open, break its store while the IDE runs (`rm -rf .jj/repo/store` leaves `.jj` present, so it still passes the "is this a jj repo" check, but `jj log` fails) and trigger a VCS re-scan (touch a file, or reopen the project). Expected: no red "IDE Internal Error" balloon; a single WARNING notification "Jujutsu Repository Could Not Be Read" appears with jj's error detail and **Retry** and **Configure VCS Mappings…** actions
- [ ] The Working copy tool window's empty state shows **"Jujutsu could not read the repository '\<name\>'..."** (not the generic "No Jujutsu repositories configured") with its own **Retry** link, and Settings → Version Control → Directory Mappings shows the broken repo's row in red, like an otherwise-invalid mapping
- [ ] Triggering another refresh does not repeat the toast, but the tool window's empty state and the red mapping row persist. **Retry** (on the notification or the empty-state link) re-checks immediately, and while still broken the same messages reappear (a fresh notification can fire again since Retry re-arms it)
- [ ] Restoring the store (e.g. `jj git init --colocate .`) **without** touching any other file makes the tool window, Log and Directory Mappings row recover on their own within ~1 s, with no manual Retry/re-scan (the plugin watches `.jj/repo/` for exactly this)
- [ ] In a multi-repo project, breaking only one repo's store leaves the other repo's Working copy/Log data unaffected, and the empty-state message mentions the broken repo only when *all* repos are unreadable (with more than one readable, the broken one is just absent from the dropdown, as for uninitialized repos). Breaking two repos' stores pluralizes the notification ("N Jujutsu repositories could not be read")

#### Stale Workspace

Genuinely staling a workspace takes more than advancing the op head from another workspace, since jj's own working-copy auto-recovery silently absorbs that case. Force it by corrupting the workspace's local operation pointer:

```bash
jj workspace add ../ws2   # from the sandbox repo
cd ../ws2
python3 -c '
path = ".jj/working_copy/checkout"
with open(path, "rb") as f:
    data = bytearray(f.read())
data[2] = (data[2] + 1) % 256
with open(path, "wb") as f:
    f.write(data)
'
jj status   # confirm: "Error: Could not read working copy's operation." + the update-stale hint
```
Re-run the same command (safe to repeat) any time you need to re-stale it, including after **Update Stale Workspace** has just repaired it. Open `../ws2` as its own IDE project.

**Known gap:** Annotate still reports a stale workspace as a raw error in the platform's own "Annotate" Messages-tool-window tab, not the notification below; a fix attempt was reverted rather than shipped half-working.

- [ ] Expected: no red "IDE Internal Error" balloon and no "Uncaught exception" background warning; a WARNING notification **"Jujutsu Workspace Is Stale"** appears with **Update Stale Workspace** and **Retry** actions. The Working copy tool window's empty state shows the stale-specific message (not the generic "could not be read" text) with its own **Update Stale Workspace** link
- [ ] While stale, the Working copy tool window's toolbar and context menus and the changes tree show every action simply disabled or hidden, with no uncaught exception. Clicking **Update Stale Workspace** (notification or empty-state link) runs `jj workspace update-stale` and the tool window/Log repopulate on their own within ~1 s, with no manual Retry/re-scan
- [ ] Triggering **Advance Bookmark**, **Rename Bookmark**, **Create Bookmark**, **Move Bookmark**, **Set Tag** or **Git → Push** while stale shows the **Update Stale Workspace**/**Retry** notification (not a crash, and not a wrong message like "bookmark already exists"), and clicking **Update Stale Workspace** both repairs the workspace *and* completes the interrupted action (the bookmark actually advances/renames/moves, the push dialog actually opens)
- [ ] Opening the **Squash**, **Squash Into**, **Rebase**, **Duplicate** or **New Change** dialog while stale shows the stale notification in the commit picker instead of staying silently empty or freezing, and **Update Stale Workspace** repairs it and populates the picker. The **Move Bookmark** dialog shows the stale notification instead of silently mislabelling every candidate "backward/sideways". **Show History** on a file shows the notification, not a silently empty history table
- [ ] In a multi-repo project with only one repo's workspace stale, the other repo's Working copy/Log data is unaffected and repo-root icons in the changes tree still render for both (no freeze scrolling a large changes tree while one repo is stale)
- [ ] With the workspace healthy again, deliberately creating a **real** bookmark name conflict (a bookmark whose name already exists) still shows the correct "already exists" message

#### jj Not Responding

A jj timeout (30 s) is a busy system, not a broken repo. Simulate it with a wrapper script as the jj path (Settings → Version Control → Jujutsu):

```bash
#!/bin/sh
case "$*" in *"-r @"*) sleep 35;; esac
exec jj "$@"
```

- [ ] Opening a project with the wrapper active from a cold start shows, after ~30 s, a WARNING **"Jujutsu Is Not Responding"** balloon with only a **Retry** action (no "Configure VCS Mappings") that does **not** say "broken, moved, or incompatible". During the first ~30 s the Working copy tool window says **"Loading Jujutsu repositories, please wait..."** with no link, not "No Jujutsu repositories configured"
- [ ] Closing the project window and reopening the same project **without restarting the IDE** shows the balloon again (it must not be suppressed by the earlier one)
- [ ] The tool window's empty state then says jj did not respond within 30 seconds / the system may be busy, with a **Retry** link, and Settings → Directory Mappings does **not** show the root in red. Restoring the real jj path and clicking **Retry** populates the tool window and Log
- [ ] With data already loaded, re-activating the wrapper and triggering a refresh (e.g. editing a file) keeps the Working copy and Log data visible and shows only the balloon. A genuinely broken store still shows the original "could not be read" balloon (see Unreadable Repository above)

#### Standard Commit Tool Window Suppression

- [ ] In a **jj-only** project (default setting), the standard **Commit** tool window and the **Local Changes** tab are not shown; the **Working copy** tool window is the only changes UI, and Ctrl/Cmd+K still opens Describe. Unchecking Settings → Version Control → Jujutsu → "Hide the standard Commit tool window" brings the Commit tool window / Local Changes tab back immediately, without reopening the project, and re-checking hides it again
- [ ] With the setting on, editor-tab and Project-view file colours for added/modified files still render, editor gutter change markers still show, Annotate still works, and the Working copy panel's changed-files list still updates live
- [ ] In a **mixed jj + Git** project (both VCSes mapped), the standard Commit tool window is shown regardless of the setting (jj is not the single active VCS), and Git's own commit workflow is unaffected

#### Working Copy Tool Window Signpost

- [ ] With a fresh sandbox config (no prior `jujutsu.xml` app or project settings), opening a jj project opens the **Working copy** tool window on the left automatically while keyboard focus stays where it was (e.g. the editor). A sticky balloon explains the Working copy panel and its "Open Working Copy" action; clicking it activates and focuses the tool window
- [ ] Closing and reopening the same project neither force-reopens the tool window nor shows the balloon (both are one-shot per project/install). A **second, different** jj project in the same sandbox opens the tool window automatically again (per-project) but shows no balloon (per-install, already shown). A non-jj project shows neither

#### Sponsor Ask

- [ ] With a fresh sandbox config, opening a jj project shows no sponsor balloon on first run. After quitting, backdating `firstRunEpochMillis` in the sandbox `jujutsu.xml` (under `JujutsuApplicationSettings`) to more than 14 days ago and restarting, a sticky balloon fires once, and clicking **Sponsor** opens `https://github.com/sponsors/kkkev` in the default browser. Restarting again does not show it again
- [ ] Repeating the backdate in a fresh sandbox and clicking **Don't show again** instead means the balloon never reappears after a restart. The Working Copy signpost above still fires independently, unaffected by the sponsor ask (they use disjoint fields in `JujutsuApplicationSettingsState`)

#### Version-Gated Feature Upgrade Nudge

Requires the multi-version harness (see [Test Tooling](#test-tooling)) to have a jj executable older than 0.39 to point the plugin at.

- [ ] With a fresh sandbox config and the jj executable path (Settings → Version Control → Jujutsu) pointed at a pinned jj **below 0.39** (e.g. `~/.local/bin/jj-0.38`), opening a jj project fires a balloon once naming "Advance Bookmark (needs jj 0.39)" and your current version. Clicking **Update jj...** opens Settings → Version Control → Jujutsu. Restarting the same sandbox does not show it again, and clicking **Don't show again** (in a fresh sandbox) means it never reappears after a restart
- [ ] Changing the configured path to a **different** old version (e.g. 0.37 instead of 0.38) fires the balloon again (it re-nudges per jj version)
- [ ] Pointing the path at a jj **0.39 or newer**, or clearing it back to default resolution, shows no balloon regardless of prior dismissal state. Pointing it **below the plugin's hard minimum** (0.37) shows only the existing "jj version too old" warning (MT-CROSS's Error Handling), never this nudge. Opening a **non-jj** project with an old jj configured shows no balloon

#### Feature-gated upgrade prompt in Installation Help

The passive counterpart to the nudge balloon above, in Settings; it needs the same multi-version harness. `JjVersion.MINIMUM` is currently 0.37.0: a pinned jj at or above that but below 0.39.0 (e.g. 0.37.x/0.38.x) is Scenario B (gated on a feature), and anything below 0.37.0 is Scenario A (below the plugin's hard minimum).

- [ ] With a current jj configured, Settings → Version Control → Jujutsu → "Installation Help" reads "If jj is not installed, use one of these commands:" with no feature list
- [ ] Pointing the executable path at a pinned jj **below 0.39 but ≥ 0.37.0** (e.g. `~/.local/bin/jj-0.38`) and clicking Apply **without closing Settings** auto-expands "Installation Help" live (the same Apply reruns availability detection). Its content is a one-shot snapshot at panel-open time, so closing and reopening Settings with the same path shows: "Your current jj version is too old. Upgrade now to unlock these features:" followed by an **indented bullet line** "• Advance Bookmark *(needs jj 0.39.0)*" (the parenthetical in grey secondary text, not the same weight as the feature name), then "Upgrade using:" and the **upgrade** commands (`brew upgrade jj`, not `brew install jj`). There is no separate group and no "now expanded" or similar filler phrasing
- [ ] Pointing the path **below the plugin's hard minimum** (e.g. 0.36), Apply and reopening Settings reads "Your current jj version is too old. Upgrade using:" with upgrade commands but **no feature list** (Scenario A already has its own balloon and JjNotInstalledPanel, see MT-CROSS's Error Handling, so feature gating is not double-reported). Clearing the path back to default (a current jj) leaves Installation Help **expanded** (the plugin never auto-collapses a group once opened), though its content needs a Settings reopen to drop back to the "if jj is not installed" wording

#### Status Bar Widget (Switch Working Copy)

- [ ] In a plain Git or no-VCS project (no `.jj` root) the Jujutsu working-copy status-bar widget does not appear, and running **Jujutsu → Init** there makes it appear immediately, without restarting the IDE (GitHub #100)
- [ ] Clicking the widget in a repo with at least one local bookmark and one tag opens the "Switch Working Copy" popup. Hovering a bookmark or tag row shows a tooltip with the chip (icon + name, correct accent colour) followed by `(changeid)` and **no broken-image glyph**; hovering a change row shows change id, commit id, author (as a mailto link), timestamp and description, each on its own line. Moving the pointer between rows without leaving the list updates the tooltip to the newly hovered row
- [ ] Hovering a row in a list long enough to scroll, then scrolling (wheel or scrollbar) without moving the pointer, hides the tooltip and it does not reappear until the pointer moves
- [ ] `jj describe` on `@` with a very long single-line description makes the widget's label ellipsize while other status bar widgets (line/column, encoding, memory, notifications) stay visible, both at full window width and after shrinking the IDE window to ~900px; the widget's tooltip still shows the full, untruncated description
- [ ] The widget's padding, text colour, hover fill and pressed fill match a stock status-bar widget (e.g. the encoding widget) at rest, on hover and while the mouse button is held down, in both a light and a dark theme; dark-theme hover goes **lighter**, not darker (GitHub #95)

### MT-DIFF

**Diff viewing across file surfaces**

**Code:** `vcs/diff/JujutsuDiffProvider.kt`, `actions/filechange/`, `vcs/annotate/`, `vcs/history/JujutsuHistoryProvider.kt`, `actions/file/OpenInRemoteFromEditorGroup.kt`, `actions/filechange/OpenFileInRemoteGroup.kt`
**Also re-run:** MT-DIFF-PREVIEW; MT-DIFFBASE (a configured diff base changes what `Annotate` below annotates against); see [Known gaps](#known-gaps) for jj-idea-7d9p/zvzk, which recur across every surface in this section

#### Hidden-commit reads (GitHub #130)

**Code:** `jj/LogEntry.kt` (`contentLocator`/`readRevision`/`parentContentLocator`), `jj/cli/CliLogService.kt` (`getFileChanges`), `vcs/history/JujutsuFileRevision.kt`, `vcs/annotate/`, `vcs/diffbase/DiffbaseService.kt`

Setup: in a scratch repo, `echo v1 > f; jj describe -m A; echo v2 > f` (the change is amended, so the v1 commit is hidden but still referenced, e.g. by a remote bookmark or by copying its commit id from `jj log -r 'all()' --hidden` / the op log).

- [ ] Selecting the amended change in the log, the details pane and Show Diff show the *current* content, and Compare with Another Commit... with the old (hidden) commit id shows v1 → v2 (not identical)
- [ ] File History of `f`: opening the revision's content shows that commit's content, and the row for the visible commit shows v2
- [ ] Annotate `f`, then Annotate Previous Revision on a line: previous content is read from the parent *commit*, not whatever now carries the parent's change id
- [ ] Setting the diff base (MT-DIFFBASE) to a revset resolving to a commit, rewriting that commit in a terminal (`jj describe`) and refocusing the IDE makes the gutter base follow the refreshed resolution (cache cleared on log refresh), with no stale content

#### Diffs

- [ ] Show diff for a directory with changed files shows diffs for each file in it; for a directory with no changed files it does nothing (no crash). A diff for an unchanged file shows no changes (both sides identical)
- [ ] A diff for a single-parent file shows the correct before/current pairing for each change type, identically in the Project Tool Window, Editors for Current Files and Editors for Historical Versions: modified (before=parent, current=selected), deleted (before=parent, current=empty), added (before=empty, current=`@`), renamed (before=`@-` with the previous filepath, current=`@`). A diff from the working copy shows before = parent, current from the working copy
- [ ] Right-clicking inside a diff viewer's editor pane shows "Annotate" exactly once (not at top level and again under "Jujutsu"). The right-hand diff pane is editable when it holds the working copy and read-only when it holds a historical version
- [ ] Binary/image diffs: committing a change to a `.png`, selecting that commit in the log and opening the file's diff (and again with a later commit selected, so both sides are historical) shows IntelliJ's image diff viewer with both versions as images, not garbled text; an ordinary text file in the same commit still shows a syntax-highlighted text diff
- [ ] "Open in → remote": for a single parent it opens that parent (resolving to the pushed ancestor); it is hidden when no pushed ancestor exists; for an unpushed historical version it resolves to the nearest pushed ancestor. Verify in the Working Copy Panel, Project Tool Window, Editors for Current Files and Editors for Historical Versions
- [ ] Right-clicking a file (editor or Working Copy panel) to open "Open in Remote" immediately after opening the project (first menu open, cold cache) still lists the remotes correctly, with no `Synchronous execution under ReadAction` warning in Help → Show Log

#### Project Tool Window

- [ ] A file in the tool window has a Jujutsu menu with "Show Diff" and "Compare with Another Commit...", plus "Compare Before with Another Commit..." for a historical selection (hidden for the working-copy entry and a root commit). Show diff for multiple files opens multiple editors. The menu has Open in → remote (see Diffs above)

#### Editors for Current Files

- [ ] The Jujutsu menu has "Show Diff", "Compare with Another Commit" and "Annotate", plus Open in → remote (see Diffs above). Annotate fetches annotations for the correct revision
- [ ] Clicking an annotation's change id / author selects that change in the Jujutsu log, including a change older than the log's change limit (FX-STRESS with `SCALE=6`). With the log's date (or author) filter excluding that change, the same click shows a "hidden by the current log filters" balloon, and **Reset Filters** clears every filter chip and the search text and selects the change. With the Jujutsu log tab closed, clicking an annotation reopens the tab and selects the change (not a silent no-op) (GitHub #140)
- [ ] "Annotate Previous Revision" on a line owned by a single-parent commit re-annotates at that commit's parent; on a line owned by a merge commit it is unavailable or a no-op (no incorrect ancestor shown); on a line whose own change added the file (no earlier version exists) it declines gracefully, with no raw error and no logged ReadAction-violation warning
- [ ] Annotate on a file whose working copy is a merge commit succeeds (no "resolved to more than one revision" error). On a merge commit with a resolved conflict it shows no "line count" warning and attributes lines inherited from each parent, plus the conflict-resolution line(s), to the merge commit itself. On a merge commit where the file exists in only some parents (e.g. a criss-cross merge) it succeeds with no "No such path" error, attributing blame from whichever parents have the file
- [ ] On a repo where annotate is slow (FX-STRESS with `SCALE=6`, or GitHub #64's repo), starting Annotate and clicking the ✕ on the "Retrieving annotations" progress leaves, within about a second, no process in `ps aux | grep 'jj file annotate'`, and `idea.log` shows `Cancelled in … jj file annotate` at INFO level with no error report. A fresh Annotate afterwards still works. → automate: `CliExecutorCancellationPlatformTest` covers the executor; this checks the platform task really cancels
- [ ] On a **light** theme the change-id column in the annotation gutter is readable, not washed out

#### Editors for Historical Versions

- [ ] The editor has a title including the change id and a Jujutsu menu with diff and "compare with another commit" (and Open in → remote, including the unpushed-historical-version case in Diffs above). Compare with another commit opens that commit on the left and the editor's version on the right
- [ ] The Jujutsu menu also has "Compare Before with Another Commit...", which opens the parent of the editor's revision on the left and the chosen commit on the right; it is hidden for the working-copy entry and for a root commit
- [ ] "Annotate" is enabled (via right-click and the Jujutsu menu) for a file opened from a historical commit, and fetches annotations for the correct revision

#### File History

- [ ] Opening a file from the File History panel (right-click a file → "Show File History", pick an older revision, open the file) enables "Annotate", which produces a blame gutter for that revision's content
- [ ] Opening a `.png`'s platform history tab across a few commits and showing the diff between two revisions renders an image diff, not text
- [ ] In a file's platform history tab (editor's Jujutsu submenu → "Show History"), the Date, Author and Committer columns are populated for every revision, and clicking the "Commit Time" column header twice orders rows chronologically (oldest→newest, then newest→oldest), not alphabetically by committer name
- [ ] Show Diff on a single history row diffs it against its **real parent**, not the row below it (jj-idea-d0bp). In `../jj-test/jj-only`, history of `test-file-1.txt`: Show Diff on `caed470d` shows only `this is change cl/X` and `v2` added (parent `kzxrvruo`), none of the rows below; on the conflicted merge `4f745109` it diffs against the auto-merged parents. Selecting two rows and Show Diff still diffs those two rows against each other
- [ ] Deleting a tracked file (`rm`) and right-clicking it alone in the Working Copy panel's changed-files tree offers **Show File History** and opens history for it (GitHub #122; see MT-WORKINGCOPY "Restore dialog")

### MT-DIFF-PREVIEW

**Diff preview-tab behavior**

**Code:** `ui/common/JujutsuEditorTabDiffPreview.kt`
**Referenced by:** MT-LOG-DETAILS (Details Changes Panel), MT-WORKINGCOPY (Working Copy Panel),
MT-DIFF

This is the canonical diff preview-tab check, deduplicated from the three surfaces above: verify it once per surface it's referenced from, not three times independently.

- [ ] Double-clicking a file, or pressing Enter on a selected file, opens its diff in a single editor tab (preview tab). Clicking a different file while the diff tab is open swaps its content and the tab count stays at 1; a single click does nothing if no diff tab is open
- [ ] Escape inside the diff tab closes it. Cmd/Ctrl+D opens the same diff preview tab (routing through the preview when available), and F4 still opens the file in a regular editor tab (no "Synchronous execution on EDT" error in the IDE log)
- [ ] With the diff tab open and a different (regular) editor tab focused, editing and saving that file leaves the editor on it; it does not switch to the diff tab (GitHub #67)
- [ ] With the diff tab open on a long file scrolled away from the top, editing and saving a *different* tracked file from outside the IDE (e.g. a terminal) leaves the diff scrolled where it was once the background refresh lands. With `@` selected and its diff tab open on the working-copy side, the right-hand pane title reads "Current" (not the change id) and is editable; on a long file scrolled well away from the top, editing and saving *that same file* updates the content in place with the scroll position unchanged (GitHub #67)

### MT-DIFFBASE

**Custom diff base for gutter markers and Annotate (GitHub #43)**

**Code:** `vcs/diffbase/`, `settings/DiffbaseStrategy.kt`, `settings/JujutsuConfigurable.kt` (Diff Base group + per-repo override), `actions/diffbase/SetDiffbaseAction.kt` (quick action), `actions/JujutsuMainMenuGroup.kt` (VCS-menu submenu)
**Also re-run:** MT-DIFF (Annotate is the other consumer of the diff base); MT-SETTINGS (the settings group itself); MT-CTXMENU (the quick action's "Pin to Revision..." shares `RevisionSelectorPopup`); MT-WORKINGCOPY (gutter markers); MT-CROSS (multi-repo submenu)

Requires a repo with at least one immutable ancestor and a few mutable commits above it (e.g. `jj new trunk()` a couple of times) so "latest immutable ancestor" differs visibly from `@-`.

- [ ] Settings → Version Control → Jujutsu → **Diff Base** defaults to "Working copy parent (default)" on a fresh install, with gutter markers and Annotate comparing against `@-`. The "Working copy parent", "Latest immutable ancestor" and "Previous commit" radio buttons show a "(?)" icon after the label whose hover/focus text is the underlying revset (`@-` / `latest(ancestors(@-) & immutable())` / `latest(@-- | latest(ancestors(@-) & immutable()))`); the labels stay short, with no raw revset text visible without hovering
- [ ] Selecting "Latest immutable ancestor (trunk)" and clicking Apply, without touching the editor, expands every open file's gutter markers to the full diff vs trunk (not just vs `@-`). Selecting "Previous commit (grandparent)" narrows them to the last two commits' worth of change; after `jj edit` onto a change directly on trunk (no mutable grandparent) it falls back to trunk, not past it into immutable history
- [ ] After adding a file in a mutable ancestor (`echo foo>foo.rs; jj commit`) and then `echo bar>>foo.rs`, with base "Latest immutable ancestor" or "Previous commit" every line of `foo.rs` shows an *added* marker (the file doesn't exist at the base), while with "Working copy parent" only the new line is marked (GitHub #133)
- [ ] With that same setup and base "Latest immutable ancestor" or "Previous commit", the editor's right-click **Jujutsu** submenu has no Annotate entry (it is present for files that exist at the base and with "Working copy parent"), while the platform's other Annotate entry points (main menu, gutter) still work and show a "Nothing to annotate" balloon with no strip and the item unticked
- [ ] **Live update with an editor already open**: with a file's Annotate gutter showing, switching the base (any of the four strategies) and clicking Apply, *without* closing the editor, removes the annotation columns and unticks the Annotate menu item. No stale blame stays on any line, no balloon appears, and no "Number of lines annotated by Jujutsu is not equal to number of lines in the file" banner appears at the top of the editor; Annotating again gives blame against the new base
- [ ] Annotating the same file (Jujutsu → Annotate) after each switch lines up every blame line with the correct source line; lines changed anywhere in the stack read as unattributed, not shifted onto the wrong line (the gutter base and the Annotate base must never disagree)
- [ ] Selecting "Custom revset", typing `trunk()` and clicking **Test** reports success per repo, and the gutter and Annotate match the "Latest immutable ancestor" case. An invalid revset (e.g. `zz(`) makes **Test** show the raw jj error wrapped instead of widening the panel. A revset resolving to more than one revision (e.g. `heads(mutable())` with concurrent branches) is reported as an error ("resolves to N revisions"), and Apply is blocked as for an unresolvable revset. "Custom revset" selected with an empty field shows a validation error on Apply
- [ ] In a multi-repo project, a per-repo override (Repository Settings → the repo's group → "Override diff base") on one repo affects only that repo's files, in both the gutter and Annotate; the other repo keeps the project default. The per-repo "Override diff base" combo box shows short strategy names, not raw revset text, with a grey comment line explaining what `@-` and "Latest immutable ancestor" resolve to (the automated `JujutsuConfigurablePanelTest` fixture has no repo, so it can't cover this). Settings at its default size shows no horizontal scrollbar with this group expanded (see MT-SETTINGS "Settings panel width")
- [ ] Edge cases: a repo with no immutable ancestor falls back to `@-` (no error dialog, no crash); an ignored or unversioned file gets no gutter markers and no error; a file opened from the log or File History (a historical version) is unaffected; the **Local Changes** / **Working copy** panel keeps showing changes vs `@-` whatever this setting is

#### Quick action (GitHub #43)

The quick action is a fast, task-driven way to switch the diff base in addition to the settings row. It writes the same per-repo setting, always as an explicit per-repo override (even in a single-repo project), so it and the settings row can never disagree; the project-level radios in Settings can read "Working copy parent" while a repo's real base is something else, and only the repo's own "Repository Settings" group shows the truth (jj-idea-4tcf would add a live indicator).

The popup splits "pick a revision" into two entries: **"Custom revset..."** saves a typed expression (e.g. `trunk()`) verbatim, re-resolved on every future diff base change, while **"Pin to Revision..."** freezes to whatever concrete commit the picked bookmark/tag/change resolves to right now.

- [ ] The **VCS main menu** has "Initialise Jujutsu Repository" as a loose top-level entry (via the platform's `Vcs.Import` group, like Git/Mercurial/Subversion's init actions), appearing exactly once. It also has a **"Jujutsu"** submenu (pin icon) containing "Undo `<last action>`" and "Set Diff Base", sitting in the **same section as the "Git" submenu** (both are `VcsGlobalGroup` entries anchored after the `Vcs.Specific` marker): with a Git-colocated or separate Git project open, "Jujutsu" and "Git" appear adjacent. "Initialise Jujutsu Repository" is deliberately **not** inside this submenu, as Git.Init is not inside Git.Menu
- [ ] In a project with **no jj repo yet**, the "Jujutsu" submenu is entirely absent, while "Initialise Jujutsu Repository" stays reachable via the VCS menu, the VCS Operations popup and the tool window's "Create Repository" button
- [ ] "Set Diff Base" opens a popup listing "Working copy parent", "Latest immutable ancestor", "Previous commit", "Custom revset...", "Pin to Revision..." and "Configure in Settings...", with a checkmark on the repo's current strategy. Picking "Latest immutable ancestor" **closes the popup immediately** (not a checkbox that stays open), widens open editors' gutter markers to the full diff vs trunk right away, and reopening the popup shows the checkmark moved to it. "Configure in Settings..." opens Settings → Version Control → Jujutsu
- [ ] "Custom revset..." with no override active opens an input dialog (a coloured, completing revset field; Enter accepts unless a completion list is showing) with an empty field. Typing `trunk()` and OK rebases gutters/Annotate onto trunk; reopening the popup shows a trailing "Custom: `trunk()`" row, checked, and Settings → Version Control → Jujutsu shows the repo's "Override diff base" row checked with "Custom revset" and `trunk()` **verbatim** (not a resolved change id). Repeat after letting the field sit idle for a couple of seconds before confirming, to catch the old frozen-commit-id behaviour. Reopening "Custom revset..." pre-fills the active revset (`trunk()`)
- [ ] An invalid revset (e.g. `zz(`) in "Custom revset..." shows an error dialog and leaves the diff base unchanged; one resolving to more than one revision (e.g. `heads(mutable())`) shows a distinct "resolves to N revisions" error, also unchanged
- [ ] "Pin to Revision..." opens the familiar revision picker (search, bookmarks/tags, free-form revset); picking a bookmark rebases the gutters onto its **current** resolved commit, and the popup then shows "Custom: `<bookmark>`" checked. Annotate after each switch keeps blame lines aligned with source lines
- [ ] The editor context menu → Jujutsu → "Set Diff Base" reaches the same popup. In a **multi-repo project**, VCS menu → Jujutsu → "Set Diff Base" with no file/editor in context shows one submenu per repository (named by repo), and setting one repo's base leaves the other's untouched

### MT-CONFLICT

**Conflict resolution**

**Code:** `jj/conflict/`, `ui/editor/conflict/`, `vcs/merge/JujutsuConflictResolver.kt`, `vcs/merge/JujutsuMergeProvider.kt`, `vcs/diff/JujutsuConflictDiffRequestProvider.kt`, `ui/common/JujutsuConflictsNode.kt`, `actions/file/ResolveSelectedConflictsAction.kt`, `actions/file/ResolveAllConflictsAction.kt`, `actions/change/resolveConflictsAction.kt`, `actions/change/workingCopyConflicts.kt`, `actions/change/resolveConflictsAvailability.kt`
**Fixture:** FX-CONFLICT (content conflicts), FX-MD-CONFLICT (modify/delete conflicts)
**Also re-run:** MT-CROSS (multi-repo scoping); MT-DIFF-PREVIEW, MT-LOG-DETAILS, MT-WORKINGCOPY (`JujutsuConflictDiffRequestProvider` is consumed via the shared preview-tab helper those sections cover); MT-DIFF, MT-DIFFBASE (the in-editor gutter's plugin-wide `editorFactoryListener` — see Shared Surfaces)

#### Detection

- [ ] `file.txt` appears in the Working Copy panel with red (MERGED_WITH_CONFLICTS) status under all three marker styles (git, snapshot, diff), and under each style "Resolve Conflicts…" shows correctly oriented, commit-labelled panes (see the next section)
- [ ] A conflicting commit whose description contains angle brackets (`fix <foo@bar> & co`) shows `<foo@bar> & co` literally (nothing dropped) in the pane title of "Resolve Conflicts…" and the conflict diff tab, and in the editor banner's "Accept …" link and tooltip, also unescaped (GitHub #138)

#### Rebase conflict pane orientation and titles (GitHub #112)

A rebase conflict's two sides are asymmetric: one is the commit you rebased onto (jj's "destination"), the other is your own moved commit, and which one jj renders as full content vs. a diff from base varies per conflict, not per operation. "Resolve Conflicts…" reads jj's own per-side label out of the conflict markers to decide which side is "Yours", instead of guessing from marker layout.

Set up: `base` → `my change`; separately, `base` → `modified externally`; then rebase `my change` onto `modified externally` so it conflicts.

- [ ] "Resolve Conflicts…" on the conflicted file shows **your own change on the left** pane and **the commit you rebased onto on the right**, titled with jj's own commit + description text (e.g. `ulmlywnv "my change" (rebased revision)`), not "Yours"/"Theirs". Rebasing a small stack, so that a **descendant** of the rebased commit is also conflicted, gives the same orientation for every conflicted commit in the stack
- [ ] Under `ui.conflict-marker-style` `diff` and `git`, orientation and titles are correct; under `snapshot` (no commit info in the markers) the panes fall back to plain **"Side #1"** / **"Side #2"** titles with content unaffected
- [ ] A **merge** conflict (two arbitrary commits combined, not a rebase) and a **squash** conflict show the unswapped ordering, neither reading backwards
- [ ] In the platform's native multi-file merge dialog (Commit tool window; only on an IDE where the iterative flow is off, otherwise it refuses, see below), bulk **"Accept Yours"** and the interactive dialog's left pane resolve the rebase conflict to the **same content** (confirm with `jj status`/file content after each), and likewise **"Accept Theirs"** and the right pane

#### Modify/delete conflicts

Uses FX-MD-CONFLICT. A modify/delete conflict differs from a content conflict: one side deleted the file entirely, so "accept" on that side must actually remove the file from disk, not leave an empty file behind.

- [ ] `a.txt` appears in the Working Copy panel as MERGED_WITH_CONFLICTS, and opening the merge tool via "Resolve Conflicts…" shows one pane empty (the deleted side)
- [ ] In the platform's native multi-file merge dialog, bulk **"Accept Yours"** (or **"Accept Theirs"**) with the deletion side chosen **removes `a.txt` from disk** (`jj status` shows `D a.txt`, no conflict), not an empty `a.txt`. In the plugin's "Resolve Conflicts…" interactive merge tool, saving with an empty result pane (choosing the deleted side) also **deletes `a.txt`**, while saving a genuinely edited (non-empty) result through either path still writes that content normally
- [ ] Making the repo temporarily unwritable (`chmod -w .jj/working_copy` on the resolve target) and retrying accept-yours/theirs from the native dialog shows an **error notification** (no silent no-op)

#### Merge Conflicts node and toolbar buttons (GitHub #56)

- [ ] With `file.txt` conflicted, a bold **"Merge Conflicts"** node appears at the **top** of the Working Copy changes tree, above the directory/repository grouping, with a file count and a clickable **"Resolve"** link. Toggling **Group By → Directory / Repository / None** keeps the node pinned at the top in all three modes with the chosen grouping nested *inside* it. With no conflicts at all, neither the node nor the toolbar buttons appear
- [ ] Clicking "Resolve" opens the **first** conflicted file (path order) **in the editor**, caret on its first `<<<<<<<` block, with the conflict banner showing, in one tab only, never one per file. The secondary **"Merge Tool"** link opens the three-way merge tool for every file under the node, one after another, and cancelling out of it still **leaves conflict markers intact** (the GitHub #63 invariant; confirm with `jj status`)
- [ ] After resolving the only conflicted file, the node **disappears** on the next automatic refresh, without pressing Refresh. Collapsing the node and restarting the IDE keeps it collapsed, and it stays **collapsed** when a conflict is later created or resolved so the file count changes (the persisted collapse-state key must not embed the count)
- [ ] Right-clicking the node itself → "Resolve Conflicts…" / "Open Merge Tool…" does the same as the inline "Resolve" / "Merge Tool" links. In a multi-repo project with conflicts in two jj roots, a single node contains both roots' conflicted files (grouped by repository underneath if that grouping is active). In a mixed jj + Git project it contains only jj conflicts, never Git-tracked ones from a co-located Git root
- [ ] The changes-tree toolbar has a **"Resolve Conflicts in Editor"** button followed by an **"Open Merge Tool…"** button, both visible only when the working copy has at least one conflict. With a **non-conflicted** file selected and `file.txt` still conflicted elsewhere, both stay **visible and work** (they must not depend on tree selection). "Resolve Conflicts in Editor" opens the first conflicted file in the editor and "Open Merge Tool…" opens the merge tool for every conflicted file, like the node's two links

#### Per-file resolve gestures in the Merge Conflicts node (GitHub #66)

These let a user pick exactly which file(s) to act on, instead of multi-selecting several conflicted files and getting an unspecified queue order.

- [ ] With at least 3 conflicted files, including one **modify/delete** conflict, each row in the node shows jj's own shape text after the file name (e.g. "2-sided conflict" / "2-sided conflict including 1 deletion"), matching `jj resolve --list` verbatim
- [ ] **Double-clicking** one conflicted row (not multi-selected) opens only that file **in the editor** at its first conflict block (the merge tool is the "Open Merge Tool…" context-menu action), not a queue over the whole node. Double-clicking a **non-conflicted** row elsewhere in the tree still opens the normal diff preview
- [ ] Right-clicking a **single** conflicted row shows two accept actions reading **"Accept \<jj's own commit label\>"** for each side (matching the label the editor banner's "Accept …" links show for the same file), not "Accept Yours"/"Accept Theirs". Selecting **two or more** conflicted files from the **same rebase/merge operation** (ctrl/cmd-click) shows the **shared full commit label** verbatim. Selecting files from **unrelated** conflicts (different commits, even if both say "(rebased revision)") falls back to the generic **"Accept Side #1"** / **"Accept Side #2"**, never "Yours"/"Theirs" at any tier. Two files sharing only **one** of their two commits (A/B and B/C) also fall back to the generic labels
- [ ] Clicking an accept action with 2+ files selected resolves all of them in one step with no merge tool and no queue order: **one** "Resolve conflict completed." balloon with an **Undo** link appears, `jj op log` shows **one** new `resolve` op, and a single Undo restores **every** file's markers, not just the last. Afterwards the node's count drops and resolved rows disappear without Refresh (jj-idea-n6fz.2)
- [ ] For a conflict shaped like `ConflictMarkerFixtures.diffFromNamesADistinctSide` (a diff-style side whose own label collides with the `+++++++` side's, e.g. via a divergent commit) in a **single** file, the editor banner's two "Accept …" links and the two context-menu accept actions show **different** text: the diff side falls back to jj's `"from:"` identity and the `+++++++` side still shows **its own true jj label**. The same shape in **two different files**, both selected, resolves exactly the same way (not generic "Side #1"/"Side #2" just because more than one file is selected)
- [ ] Selecting one `diffFromNamesADistinctSide`-shaped file **together with** a `ConflictMarkerFixtures.cleanRebaseConflictNamingSameCommits`-shaped file (a clean rebase conflict naming the same two commits, in *swapped* current/last order relative to the first file) shows **specific labels** (`change B` / `change A`, in the first selected file's order), not the generic labels (jj-idea-0k7k). Clicking **"Accept change B"** leaves **both** files with change B's content (the swapped file is resolved via its opposite side), one balloon per resolve op, and Undo restores both
- [ ] An accept action on a selection that includes the **modify/delete** file actually **deletes** the file (`jj status`) when the deleted side is accepted, not leaving it empty. With a mix of conflicted and non-conflicted files selected, the actions act only on the conflicted ones; with nothing, or only non-conflicted files, selected, the accept actions don't appear in the context menu
- [ ] In the log's commit details pane for a **non-`@`** conflicted commit, double-clicking a conflicted row opens the (read-only) diff preview, not the merge tool (resolving off `@` is out of scope, see jj-idea-cmc3)

#### "Resolve Conflicts" context menu action (selection-scoped)

`Jujutsu.ResolveSelectedConflicts` ("Resolve Conflicts…", opens the first conflicted file in the editor) and its sibling `Jujutsu.OpenMergeToolSelected` ("Open Merge Tool…", the modal) share one scoping rule, wired into the Working Copy panel / commit details pane's file context menu and the Project view / editor "Jujutsu" submenu. They always resolve an explicit selection when there is one, and otherwise fall back to the single focused file (editor/project view) or every conflicted file inherited from the working copy's ancestors (see "Conflicts off the working copy" below). There is no separate file-level "resolve every conflicted file" action (for that, see "Conflicts off the working copy" below).

- [ ] In the Working Copy panel, right-clicking a **non-conflicted** file shows no "Resolve Conflicts…", even when unrelated files elsewhere are conflicted; right-clicking conflicted `file.txt` shows it, and invoking it opens the merge tool for **only** `file.txt`
- [ ] Multi-select: one conflicted + one non-conflicted file opens only the conflicted file's merge tool; two conflicted files open both in turn (the second after the first is resolved); two **non-conflicted** files (with another file elsewhere conflicted) show no "Resolve Conflicts…"
- [ ] In the Project view → Jujutsu submenu, a **directory** or the project root has no "Resolve Conflicts…"; right-clicking `file.txt` itself shows Resolve Conflicts… (opens it in the editor at its first conflict) and Open Merge Tool… (opens the merge tool for it)
- [ ] With `file.txt` open in the editor, right-click → Jujutsu → Resolve Conflicts… opens **only** `file.txt` in the editor and Open Merge Tool… opens the merge tool for **only** `file.txt` (scoped to the focused editor file, not every conflicted file)
- [ ] On IntelliJ/RustRover 2026.2 (build 262), "Open Merge Tool…" opens the merge tool at all (GitHub #55)

#### Three-way merge tool

- [ ] The left pane ("Yours") shows "ours" content (`changed by A`, the rebased change) and the right pane ("Theirs") shows "theirs" content (`changed by B`, the destination); they are **not** identical and conflict regions are highlighted. The center pane is editable and initially shows a proposed merge result (not identical to left or right). Works correctly for all three marker styles
- [ ] Editing the center pane to the desired resolution and clicking Apply Changes leaves `file.txt` on disk with that resolution (no conflict markers). It disappears from the Working Copy panel's conflict list **automatically**, without pressing Refresh, and right-clicking it again shows no "Resolve Conflicts…" (and triggering it anyway does not throw)
- [ ] **Accept Left** (yours) writes "ours" content and **Accept Right** (theirs) writes "theirs" content, with no conflict markers, and `file.txt` leaves conflicted state automatically, without Refresh

#### Cancelling must never discard a side (GitHub #63, critical)

- [ ] Opening the merge tool for `file.txt` and closing it via the window's `x`, or the **Cancel** button, **without** touching anything leaves `file.txt` **conflicted**: its content on disk still has the original conflict markers and it still shows red (MERGED_WITH_CONFLICTS) in the Working Copy panel. Resolving only *some* hunks in the center pane and then closing via `x` (not Apply) also leaves it fully conflicted on disk (no partial write, markers intact). Repeat for each marker style (git, snapshot, diff)
- [ ] With two conflicted files, cancelling the merge tool for the first means the second file's merge tool **never opens** and it remains conflicted untouched

Native Commit tool window (mixed jj + Git project, or jj-only with "Hide the standard Commit tool window" unchecked; **IntelliJ 2026.2+ only**: on 2025.3 and older the dialog is the one-shot flow whose Accept Yours/Theirs buttons work via `jj resolve` and whose cancel restores the file, so there is nothing to refuse):

- [ ] Commit tool window → Merge Conflicts → **Resolve** → select `file.txt` → **Merge…**, and likewise **Resolve automatically** and **Accept Yours** / **Accept Theirs**, show an error dialog saying the dialog can discard a side and pointing to Resolve Conflicts / Open Merge Tool, with `file.txt` still conflicted and its markers intact (`jj status`, file content). Each refusal also shows an info balloon "Use the Working Copy window to resolve conflicts" with an **Open Working Copy** button that activates that tool window. Closing the dialog (`x`/Cancel) leaves the file conflicted
- [ ] Working Copy tool window → Merge Conflicts node → **Resolve** opens the first conflicted file at its first conflict with no exception (2026.2 read-lock regression)

#### Editor notification banner (GitHub #56)

The banner is model-accurate: a live block count parsed from the editor document (not just `ChangeListManager`'s status), an **"Accept \<side label\>"** link per side using jj's own commit+role labels (GitHub #112), and a secondary **"Open Merge Tool"** link. The heading and side labels are kept short (a narrow editor split can't fit jj's full marker-header text plus three links on one line); the full side label and the "edit the markers directly" hint are tooltips.

- [ ] Opening `file.txt` (2 conflict blocks) in the editor shows a warning-coloured banner at the top reading "2 conflicts remaining:" with an **"Accept \<side-1 label\>"** link, an **"Accept \<side-2 label\>"** link and an **"Open Merge Tool"** link; hovering the banner's text shows a tooltip explaining that hand-editing the markers and saving also works
- [ ] Under `ui.conflict-marker-style = "git"` or `"diff"` with a rebase conflict, the accept links read jj's own commit+description labels (e.g. `Accept ulmlywnv "my change" (re…`), matching "Resolve Conflicts…"'s pane titles for the same file. A long jj label makes the link's text **truncate with an ellipsis** rather than overflowing the editor width (narrow the split to confirm the banner never wraps or gets clipped), and hovering it shows the **full** label. Under `snapshot` the links read **"Accept Side #1"** / **"Accept Side #2"**
- [ ] Hand-editing one block's markers away (leaving one block) and saving updates the banner text within ~1 s, with no tab switch or Refresh, to "1 conflict remaining:" (singular). Hand-editing the last block's markers away makes the banner **disappear** on its own, before any jj snapshot
- [ ] After undoing the hand-edits so the file is back to 2 blocks, clicking **"Accept \<side-1 label\>"** writes side #1's content to `file.txt` with no markers, `jj status` shows it resolved, and the banner disappears **without a manual Refresh**; clicking **"Accept \<side-2 label\>"** on a freshly conflicted file resolves to side #2's content. On FX-MD-CONFLICT, accepting the side that is a deletion **removes the file** from disk (`jj status` shows `D`), not an empty file
- [ ] After any accept, a **"Resolve conflict completed."** balloon with an **Undo** link appears. Clicking Undo returns the markers to the file, the banner reappears with the original count and `jj op log` shows an `op revert`. After dismissing the balloon (before any other jj action), the VCS/Jujutsu **"Undo Resolve conflict"** action does the same, including restoring the file after accepting the deletion side of a modify/delete conflict
- [ ] **"Open Merge Tool"** opens the same three-way merge tool as the other entry points, and cancelling out of it still **leaves conflict markers intact** (GitHub #63). Resolving `file.txt` via any accept link, the merge tool or any other entry point while it is open makes the banner **disappear automatically**, without switching tabs or reopening the file
- [ ] No banner appears for a **non-conflicted** jj-tracked file, for a file with a **Git** conflict in a mixed jj + Git project, or for a conflicted file **outside** any jj repo (e.g. an unrelated Git-only root in a multi-root project)

#### In-editor conflict gutter

Each conflict block gets its own in-editor affordance alongside the banner: a background colour per side (`side1`/`base`/`side2`) covering exactly that side's lines, and one gutter icon per side for accepting it directly. It reuses the platform's `DiffColors.DIFF_DELETED`/`DIFF_INSERTED`/`DIFF_MODIFIED` keys, so colours match the built-in 3-way merge viewer. Regions and icons are driven by an incremental, debounced (~300 ms) rescan of the document text, independent of the banner's `ChangeListManager`-based status, so the two can briefly disagree (e.g. right after a manual marker edit, before the next jj snapshot).

- [ ] Opening `file.txt` (git marker style) tints each side's lines a distinct background colour; the marker lines (`<<<<<<<`/`|||||||`/`=======`/`>>>>>>>`) stay uncoloured, reading as separators. One gutter icon (right-aligned, so it doesn't collide with the diffbase gutter's line-status markers, see MT-DIFFBASE) appears on each side's own first line (side1's on side1's first line, base's on base's, side2's on side2's), not one icon on the opening `<<<<<<<` line. Hovering an icon shows a tooltip naming that side (`Accept <label> — right-click for more options`)
- [ ] **Left-clicking** an icon shows a one-item popup (`Accept <label>`) and does **not** edit the document yet; clicking that entry replaces the block's markers with that side's content with no jj process running (`jj op log` unchanged until the file is saved), while dismissing the popup (click elsewhere, Escape) leaves the document **untouched**. Ctrl/Cmd+Z once restores content and gutter icon/highlighting, and Undo reads "Accept `<label>`" (each accept is its own undo unit)
- [ ] **Right-clicking** any of a block's icons shows the full menu (Accept side #1 / side #2 / Both / Base), not the one-item popup; Base is present only for git/snapshot style. In `diff` marker style side1/side2 still get colours and icons but the derived base gets **neither** (its text is synthesized from scattered diff lines); `snapshot` behaves like git style, base included
- [ ] Saving after accepting one block in a multi-block file leaves the *other* block's colour/icons unaffected (offsets shift correctly, no stale highlighting). Hand-deleting a block's closing `>>>>>>>` to merge two adjacent blocks updates colours/icons within ~300 ms, with no stale icons. Typing a stray `<<<<<<<` with no closer shows no icon/colour for it, and the IDE stays responsive (no full-document rescan per keystroke)
- [ ] Splitting the editor on the conflicted file shows colours/icons independently in **both** panes (each pane gets its own copy). A diff viewer, a preview tab, a file with a **Git** conflict, and a conflicted file **outside** any jj repo show **no** jj gutter icons/colours (the plugin-wide `editorFactoryListener` must be a no-op outside a real main editor, see MT-DIFF, MT-DIFF-PREVIEW)
- [ ] On FX-MD-CONFLICT, accepting a side on a block does **not** delete the file even if that side is the deletion: per-block accept only ever edits text, while whole-file deletion stays with the banner's Accept link and the merge tool
- [ ] Hover feedback: hovering a side's own coloured text strengthens that side's background (visibly stronger than its resting tint) while the other two stay at rest; hovering a side's gutter icon strengthens the same side **and** shows a separate rounded highlight behind the icon (the IDE toolbar-button hover colour, not the side's tint) so the icon reads as the clickable element. Hovering a marker line or plain text outside any block, or moving the mouse off the editor (`mouseExited`), shows/clears all hover strengthening immediately
- [ ] A click-drag selection across a hovered block shows the platform's selection colour with precedence, and releasing leaves the editor normal and editable. Typing inside a hovered side, or accepting a different block elsewhere, clears the stale hover (the next mouse move re-establishes it). The hover strengthening reads as stronger than the resting tint in both a light and a dark colour scheme

#### Conflict gutter: marker dividers and DIFF-line tidy

Repeat for each `ui.conflict-marker-style` (`git`, `snapshot`, `diff`), in light and dark themes:

- [ ] With the caret **outside** the block, every run of marker lines (`<<<<<<<`, `|||||||`/`-------`, `=======`/`+++++++`, `%%%%%%%`+`\\\\`, `>>>>>>>`) is a full-width coloured rule labelled with the following side's label; the closing rule is unlabelled. Rule colours match their side's tint (side 1 / base / side 2) and are legible on both themes
- [ ] Moving the caret **into** the block reveals its raw marker lines (other blocks stay folded) and moving it out restores the rules; typing or deleting inside a block never leaves stale or doubled rules. Per-side gutter icons still show and work (Accept side 1 / base / side 2 / both), including for a side or base that is **empty** (its icon must still be visible and clickable; if it vanishes, anchor it to the preceding divider)
- [ ] `diff` style only: the `-`/`+` prefix characters in the `%%%%%%%` section are dimmed and `-` lines are dimmed + struck through; document text is unchanged (copy a line to confirm the prefix is still there) and Accept still produces the right result

#### Conflicts off the working copy

Use the same conflict setup, with a conflicted commit that is **not** the working copy (e.g. `jj new` to create an empty working copy on top of the conflicted change).

- [ ] Selecting the **conflicted historical commit** in the log shows the conflicted file in the details panel with red (MERGED_WITH_CONFLICTS) status, and "Resolve Conflicts… (edit this change first)" is **visible but disabled** in the details panel context menu (resolution requires this commit to become the working copy first). Selecting the **working-copy commit** (empty, inheriting the conflict) shows "Resolve Conflicts…" **visible and enabled**, opening the merge tool for the inherited conflicted files
- [ ] Log row context menu: on the **working copy entry** with conflicts, "Resolve Conflicts…" is enabled and opens the merge tool for all conflicted files one after another; with no conflicts it is **not visible** (hidden, not disabled). On a **non-conflicted, non-working-copy entry** it is not visible. On a **conflicted, non-working-copy entry** (e.g. a merge commit, or a child that only *inherits* the conflict) it reads "Resolve Conflicts… (edit this change first)", **visible but disabled**, with a tooltip prompting the user to `jj edit` the change first
- [ ] Inherited conflicts: with the working copy on the conflicted commit, `jj new` creates an empty child that inherits the conflict. The conflicted parent shows the disabled "(edit this change first)" entry, and the child (working copy, empty diff of its own) shows "Resolve Conflicts…" **enabled** and resolves the inherited conflict correctly. `jj edit` back onto the conflicted parent makes it the working copy with the entry enabled, and the child shows the disabled hint instead (jj-idea-sm1s)

#### Conflict diff for a non-working-copy commit (GitHub #119)

Use FX-CONFLICT with `wc-position=sibling` (`scripts/fixtures/fx-conflict.sh /tmp/fx-conflict git sibling`) so `@` is `change-b`, a clean commit unrelated to the conflict on `change-a`.

- [ ] Selecting `change-a` in the log and double-clicking `file.txt` in the details panel opens a **three-pane** diff showing change A's own conflict, titled with jj's commit labels where available (not "Yours"/"Theirs", and not an error balloon reading "Could not extract conflict data"). The left and right panes are **not** identical and match `jj file show -r change-a file.txt`'s markers, not the (unrelated) working-copy content
- [ ] Re-running the fixture with `wc-position=conflicted` (`@` = `change-a`) shows the same three panes. From there, `jj new change-a` (so `@` inherits the conflict as an empty child) and opening `change-a`'s diff from the log still shows change A's own conflict; giving the working copy its own, different conflict on the same path (e.g. `jj new`, rebase something else onto `@-` to conflict `file.txt` again) and opening `change-a`'s diff from the log shows **change A's** sides, not `@`'s
- [ ] In the Working Copy panel, double-clicking a file conflicted in `@` itself shows three panes as usual. Under all three `ui.conflict-marker-style` values the first check holds; under `snapshot` the panes fall back to plain **"Side #1"**/**"Side #2"** titles with content still correct
- [ ] Scrolling a conflicted file's diff and letting the log auto-refresh (~300 ms debounce) does **not** reset the scroll position. "Resolve Conflicts…" on `change-a` from this state is still visible-but-disabled (resolution remains working-copy-only)

#### Multi-repo scoping

In a project with two jj roots each having conflicts:

- [ ] Right-clicking a conflicted file in root A's Working Copy panel opens the merge tool only for root A's conflicts, and likewise for root B. The global action (VCS menu) opens the merge tool for conflicts from both roots, one after another

### MT-IGNORE

**.gitignore file status and file tracking**

**Code:** `vcs/ignore/GitignoreCache.kt`, `vcs/ignore/JujutsuIgnoreService.kt`, `vcs/ignore/JujutsuIgnoredFilesService.kt`, `vcs/ignore/JujutsuTrackedFilesService.kt`, `vcs/changes/JujutsuIgnoredFileProvider.kt`, `actions/file/TrackedToggleAction.kt`, `actions/file/trackUntrackAvailability.kt`

**Setup**: open this project itself in `./gradlew runIde`; it has a `.gitignore` with `build/`, `.gradle/`, etc.

#### Ignored-file colouring and the Ignored Files node

→ automate: jj-idea-aah2 (.gitignore coloring/propagation checks below are file-classification
logic, testable without rendering)

- [ ] `build/` and `.gradle/` show grayed-out (IGNORED) colour in the Project tree, including nested files (e.g. `build/classes/Main.class`, parent propagation), while `src/` and tracked source files are NOT grayed out
- [ ] Version Control → Local Changes shows an "Ignored Files" group with `build/` and its contents under it. Tracked modified files (e.g. one you just edited) do NOT appear under "Ignored Files"; they appear as changes and stay non-gray
- [ ] Reactive update on `.gitignore` edit (order matters: jj auto-tracks files created before the matching gitignore rule exists, so adding a rule after a file is tracked won't untrack it, as with git): add `*.xyz` to `.gitignore` and save first, then create `test-ignored.xyz` in the repo root. It appears gray (IGNORED) in the Project tree immediately (jj did not auto-track it), and removing `*.xyz` from `.gitignore` and saving turns it the unversioned colour (green/teal). Delete the file when done. Ignored files also update after editing `.gitignore` (the async rescan triggers a refresh that picks up the new set)

#### Jujutsu menu on ignored, untracked files

- [ ] Right-clicking an ignored untracked file (e.g. under `build/`) in the Project view → Jujutsu shows History, Restore, Compare with Branch, Squash/Split and Show Diff visible but disabled (the explanatory description is set on the action, but menus don't reliably surface it for disabled items, so don't fail the test if no text appears), and Annotate absent. The Tracked toggle stays enabled, and ticking it enables the items above on the next menu open
- [ ] A tracked-but-ignored file (tracked via the toggle, then re-open the menu) has all items enabled, and so does a mixed selection (one ordinary tracked file + one ignored untracked file)

#### Ignore-scan watchdog

The watchdog (5 s) aborts the in-progress full ignore scan instead of merely logging. This is mostly covered by `GitignoreScanTest.kt` (code-level scale test); the disable escape hatch remains manually verifiable:

- [ ] Settings / Version Control / Jujutsu → the per-repo "disable ignored-file scanning" checkbox works: enabling it and editing `.gitignore` leaves the Ignored Files node no longer updating. The global "disable ignored-file scanning (all repositories)" default and its interaction with the per-repo override are under MT-SETTINGS ("Escape hatches")
- [ ] On a very large repo (if you have one), a slow scan shows the "ignore scan slow" notification once per repo per session, with working "disable" and "report" actions, and the IDE does not hang waiting for the scan to finish after the watchdog fires

#### Large ignored-file set cap

→ automate: jj-idea-s0ab (the cap itself only needs a large synthetic ignored-file set,
not a real >50k-entry repo)

Ignored files are reported via `ChangelistBuilder.processIgnoredFile` inside the CLM refresh (same cycle as change detection). The async scan runs off the refresh thread; `getChanges` reads the cached set. `IGNORE_REPORT_CAP` (50,000 entries) limits the `processIgnoredFile` calls per refresh. If the cached set exceeds the cap, a one-shot "Jujutsu Ignored-File List Is Very Large" notification appears with a "disable scanning" action.

- [ ] With a repo of >50,000 ignored top-level entries (if you have one), the notification fires once, and its "disable scanning" action disables the setting and empties the Ignored Files node

#### Tracked toggle (GitHub #42)

Right-clicking selected files in the Project view, editor, Working Copy panel or a Commit view shows a single **Tracked** checkbox item (wrapping `jj file track --include-ignored` / `jj file untrack`) when at least one selected file matches an ignore rule. Checked means jj currently tracks the file; unchecked means it doesn't; clicking flips it. Tracked status is always determined via `jj file list` (see `docs/jj-track-untrack-model.md`), never guessed. The item is hidden on ordinary, non-ignored files, on **any selection containing a folder** (IntelliJ's menus have no tri-state checkbox, and folders are out of scope), and in the Commit details panel (historical context; tracking only applies to the working copy).

Tracked status is resolved via a small async cache (`JujutsuTrackedFilesService`), since `update()`/`isSelected()` run under a read action even on background threads and IntelliJ forbids blocking subprocess calls there. **The first time you right-click a previously-unseen file *without clicking the checkbox*, it may briefly show unchecked (a safe default) while the cache populates (~300 ms); right-clicking again shortly after shows the accurate state. This is expected, not a bug.** Once you **click** the checkbox, its state is written immediately and is authoritative from then on.

→ automate: jj-idea-me8m (tracked-toggle cache semantics below are async-cache state logic,
testable without rendering)

- [ ] After adding a pattern to `.gitignore` and creating a matching never-tracked file, right-clicking it in the Project view, Working Copy panel and a Commit view shows the **Tracked** checkbox, unchecked. Checking it flips the checkbox to **checked immediately and it stays checked** (right-clicking the same file again right away is still checked, the cache must not revert it). A brief progress indicator appears in the status bar while the command runs, and a balloon confirms completion ("Track — 'foo.txt'"). The file now appears in the working copy / `jj file list` (Track must not no-op on ignored files, i.e. it passes `--include-ignored`), and the log refreshes
- [ ] Unchecking it flips the checkbox to unchecked immediately, the balloon reads "Untrack — 'foo.txt'", and the file becomes untracked again
- [ ] Multi-selecting one already-tracked-and-ignored file and one untracked-and-ignored file shows the checkbox **unchecked** (a mixed selection reads as "something left to track"); checking it tracks only the untracked one (the notification summarizes the count, e.g. "Track — 1 file") and leaves the tracked one alone. Unchecking an already-untracked file in the same multi-select as one being untracked puts jj's own `Warning: No matching entries for paths: ...` text in the notification (it must not be silently swallowed)
- [ ] Forcing a failure (removing a file's pattern from `.gitignore` so it's no longer ignored, then unchecking it anyway) shows a **non-blocking error notification** (not a modal dialog) with jj's "not ignored" message, and the checkbox **reverts** to its true (checked) state instead of staying on the failed change
- [ ] A normal, non-ignored, tracked file (anywhere: Project view, editor, Working Copy panel, Commit view), a file in the **Commit details panel** (even an ignored one), a **directory** matching an ignore rule (e.g. `build/`) in the Project view and Working Copy panel, and a mixed selection of one file + one directory all show **no Tracked checkbox**
- [ ] Right-clicking an ignored file you've never interacted with before produces no `Synchronous execution under ReadAction` (or similar) error in the IDE log (Help → Show Log)

### MT-GIT

**Git push / fetch dialogs**

**Code:** `actions/git/GitPushDialog.kt`, `actions/git/GitPushAction.kt`, `actions/git/gitRemoteActions.kt`, `actions/git/GitFetchDialog.kt`, `actions/git/GitFetchAction.kt`, `actions/git/RadioScopeBinding.kt`

#### Git Push Dialog

Setup: a local bookmark that has never been pushed to the remote.

- [ ] Push dialog (VCS menu → Push) with "Tracking bookmarks (default)" selected → OK pushes and shows a success notification; if a new bookmark exists, a confirmation dialog asks whether to create a remote bookmark, and confirming pushes successfully. "Specific bookmark" with an untracked bookmark → OK succeeds; "All bookmarks" → OK pushes all bookmarks; Cancel pushes nothing
- [ ] The scope radio group (Default / Specific bookmark / All bookmarks) has the correct option selected when the dialog opens, the bookmark combo enables only when "Specific bookmark" is selected, and clicking between all three options several times before OK always pushes according to the last-selected option (scope selection is hand-wired via action listeners, not the platform's declarative binding)
- [ ] With 2+ Git remotes, switching the **Remote** combo to a different remote (several times) always shows a real bookmark in the bookmark combo (never blank), and "Specific bookmark" → OK closes the dialog and pushes to the newly selected remote/bookmark. Help → Show Log shows no `NullPointerException` from `GitPushDialog` and the Push button is not inert after a remote switch
- [ ] On the **current** jj (0.44+) and on an older pinned jj (0.37/0.39, see contributing.md): pushing a bookmark already on the remote after moving it to a diverging commit (`jj bookmark set main -r <sibling> --allow-backwards`) shows the **force-push confirmation** naming the bookmark (cancel pushes nothing), and pushing a deleted pushed bookmark (`jj bookmark delete main`) shows the **deletion confirmation**. The result is the same on both jj versions
- [ ] Pushing a bookmark already up to date with the remote shows jj's own "Nothing changed." in the notification rather than a bare "Push complete"
- [ ] Every bookmark tracked against the selected remote appears **exactly once** in the "Specific bookmark" dropdown, with no duplicate "(new)" entry
- [ ] After `jj bookmark delete <name>` on a bookmark still tracked on the remote (a pending deletion), right-clicking the **commit** it used to sit on in the log (not the bookmark chip) → Push… → "Specific bookmark" lists `<name> (deleted)` in grey italic, even though the push was opened scoped to that revision. Selecting it → OK shows the "will be deleted from the remote" confirmation, and confirming pushes successfully and removes it from the remote (`jj bookmark list --all-remotes` shows it gone, not just `(deleted)` locally)

#### Push Tag (GitHub #124)

Setup: jj 0.44+; a repo with a Git remote (e.g. a bare repo) and a local tag (`jj tag set v1 -r @-`).

- [ ] Right-clicking the `v1` tag chip in the log shows **Push Tag 'v1' to origin...** next to **Delete Tag**; clicking it shows a confirmation naming the tag and remote, and **Yes** produces a "Push" success notification with `git ls-remote --tags <remote>` listing `v1`. **No** pushes nothing
- [ ] Moving the tag (`jj tag set v1 -r @ --allow-move`) and pushing again from the chip succeeds; pushing again with no changes shows jj's "Nothing changed."
- [ ] The same **Push Tag** item is present and works when right-clicking a tag in the **Bookmarks panel**, and on a commit row that carries a tag (the **Tag** submenu offers **Push Tag** above **Delete Tag**)
- [ ] With 2+ remotes the item becomes a **Push Tag 'v1' to ▸** submenu with one entry per remote; with no remotes it is hidden. With jj older than 0.44 (Settings → Jujutsu → jj path) it stays visible but disabled, reading "requires jj 0.44.0", and Settings → Feature Availability lists **Push Tag**

#### `--change` push scope (GitHub #65)

A fourth scope, "Create bookmark for change \<id\>", runs `jj git push --change <rev>`, auto-generating a `push-<change-id>`-style remote bookmark for the target revision. The dialog resolves the target itself (no revision picker in the dialog). It is available from three entry points: the toolbar/VCS-menu Push button (`GitPushAction`), the log's right-click Push (`gitPushAction()`), and the per-bookmark Push submenu (which never offers this scope; it is always "Specific bookmark"). It reads the **log table's current selection** wherever one exists.

- [ ] Toolbar Push (VCS menu icon or log toolbar button) with **nothing selected in the log** (or invoked from a context with no log) names `@`'s own change id on the fourth radio (or `@-` if `@` is empty, after `jj commit`); OK creates a new `push-<id>` bookmark on the remote (`jj bookmark list --all-remotes`). With a **single commit** selected in the log, the toolbar Push's fourth radio names that commit's change id, not `@`. Right-clicking a **specific commit** → Push… names that commit's change id regardless of where `@` is
- [ ] **Multi-selecting** several commits in one repo and then using right-click → Push… or the toolbar Push button shows "Create bookmarks for N changes" (plural); OK creates one `push-<id>` bookmark per selected commit, all in one confirmation
- [ ] **Multi-repo project**: with commits spanning two repos selected, toolbar Push opens the dialog normally but the fourth radio is **absent** (not just disabled), the other three scopes work via the dialog's Repository selector, and right-click → Push… (context menu) is **entirely disabled** (the context menu's Push always required a single repo)
- [ ] With a single-repo selection and the fourth radio chosen, switching the dialog's **Repository** combo to a different repo removes the fourth radio and silently reverts the scope to "Tracking bookmarks (default)", with no crash and no stale target left selected
- [ ] Pushing the same change twice via this scope does not create a second `push-*` bookmark (same name both times in `jj bookmark list --all-remotes`), and its dry-run does **not** spuriously show the "will create a new remote bookmark" confirmation the default scope shows (jj only refuses new remote bookmarks for that scope, not `--change`)

#### Git Fetch Dialog

Setup: a repository with 2+ remotes (the scope radio group only appears then).

- [ ] The fetch dialog (VCS menu → Fetch) has "Specific remote" selected by default with the remote combo enabled, and OK fetches from the selected remote. "All remotes" disables the combo and OK fetches from every remote; switching back to "Specific remote" re-enables the combo with the previously selected remote, and OK fetches just that one. Cancel fetches nothing
- [ ] With 2+ repositories mapped and the repository selector visible, switching the **Repository** combo to a different repo shows a real remote for that repo immediately (never blank), and OK fetches from the newly selected repo/remote

#### New/untracked bookmark push (GitHub #53)

The plugin no longer uses `jj git push --allow-new` (removed in jj 0.42.0); it tracks the bookmark against the remote first, then pushes without any special flag. Verify on **both** jj < 0.42 (e.g. 0.37–0.41) and jj ≥ 0.42 (e.g. 0.43); previously 0.42+ failed with `error: unexpected argument '--allow-new'`:

- [ ] "Tracking bookmarks (default)" scope with a brand-new untracked bookmark at the working copy shows a confirmation dialog listing the bookmark; confirming pushes successfully and the bookmark is now tracked (`jj bookmark list` shows `@origin`). "Specific bookmark" scope with an untracked bookmark gives the same confirmation and result. Cancelling either confirmation pushes nothing and leaves the bookmark untracked

#### Manage Git Remotes

**Code:** `actions/git/ManageRemotesDialog.kt` (+ `RemoteEditDialog`), `actions/git/ManageRemotesAction.kt`, `actions/git/RemoteValidation.kt`

Setup: **early-access (preview) feature `manageRemotes`**, gated. Mint a code (`./gradlew previewCode --args="mint --features manageRemotes --for me"`), enter it under Settings → Version Control → Jujutsu → Preview features and tick **Manage Git remotes** (or start the IDE with `-Djjidea.preview.manageRemotes=true` in the run configuration's VM options). Have a colocated repo with one remote (`origin`) and a second bare repo to use as a new remote URL.

- [ ] Without the property / access code, **Manage Git Remotes...** is absent from the Jujutsu menu and from the per-repo Settings group
- [ ] **VCS → Jujutsu → Manage Git Remotes...** opens a dialog titled with the repo name, listing `origin` with its URL, with only a **Close** button and a hint that authentication uses the Git credential helper / SSH agent and that changes cannot be undone. **Settings → Version Control → Jujutsu → Repository Settings → (repo) → Manage Git Remotes...** opens the same dialog for that repo
- [ ] **+** → `upstream` and the bare repo's URL → OK shows the row immediately; `jj git remote list` shows it, and the Push and Fetch dialogs offer `upstream` without reopening the project
- [ ] **+** with name `origin` gives an inline "already exists" error with OK disabled; `bad name`, `a..b`, `-x` or blank gives an inline name error; a blank URL or one starting with `-` gives an inline URL error; an scp-like URL (`git@host:a/b.git`) and a path with spaces are accepted
- [ ] **Edit** on `upstream`: changing only the URL updates it in the table; changing only the name renames the row and its remote-tracking bookmarks (bookmarks panel, log chips) follow the new name; changing both applies both; editing to a name that already exists gives an inline error (the rename is never attempted)
- [ ] **-** on a selected remote shows a confirmation naming the remote and URL and warning it cannot be undone: **Cancel** keeps it and **Remove** deletes it, and its `name@remote` bookmarks disappear from the log/bookmarks panel. Removing a remote externally (`jj git remote remove x` in a terminal) and then **-** on its stale row shows an error dialog with jj's message verbatim
- [ ] In a multi-root project the dialog has a **Repository:** drop-down at the top (absent for a single repo); the Settings button preselects that repo and the menu action preselects the first. Switching repos reloads the table (briefly empty, no stale rows), and add/edit/remove after switching affect only the newly selected repo. No Undo balloon appears after any remote change (these are not undoable via `jj op revert`)

**Regression scope:** MT-GIT (push/fetch remote pickers refresh), MT-SETTINGS (new button in the per-repo group), MT-BOOKMARK (remote-aware chips update on remove/rename)

### MT-SETTINGS

**Settings panel**

**Code:** `settings/JujutsuConfigurable.kt`, `settings/JujutsuSettings.kt`, `settings/JujutsuSettingsState.kt`, `settings/JujutsuApplicationSettings.kt`
**Also re-run:** MT-DIFFBASE (its Diff Base group and per-repo override live in this same panel); MT-DND (drag and drop is no longer gated by the Preview features group)

#### General settings

→ automate: jj-idea-ajd0 (settings persistence and column width/visibility persistence,
tracked in MT-LOG-TABLE, are state-serialization logic)

- [ ] The JJ executable path can be configured, including via the file picker. The auto-refresh toggle, change ID format preference (short/long) and log change limit each take effect as expected, and all settings persist across IDE restarts
- [ ] The **Log revset**, the custom **Diff Base** revset, and each repository's override fields for them are coloured and complete functions / bookmarks / tags / aliases as you type; in the multi-line Log revset field Enter inserts a newline. Typing an invalid revset and clicking **Test** shows a short one-line error (e.g. "Failed to parse revset: Syntax error: expected … (column 17)"), not jj's raw framed snippet and hint; the field gets a red outline (also for an error with no column, such as an unknown revision) and the character at jj's column, if any, is underlined; editing the text clears both. A completion list open when you click **Test** closes. The IDE log shows no `Please, install Cell.validationRequestor` warning when Settings opens. Apply/Reset still round-trip the values, and a per-repo field is only editable while its checkbox is on
- [ ] **Merge description:** defaults to `Merge branch '{source}' into {destination}`; changing it (e.g. `Merge {source} into {destination}`) changes the New Change... prefill for a two-bookmark merge, and clearing it or omitting `{source}`/`{destination}` shows an inline error on Apply
- [ ] A **Support** group at the bottom of the panel has a "Sponsor this plugin on GitHub..." link that opens `https://github.com/sponsors/kkkev` in the default browser (the same URL as the in-product sponsor ask under MT-WORKINGCOPY)

#### Default push scope

- [ ] The **General** section's "Default push scope:" combo lists all four Push dialog scopes (Tracking bookmarks / Specific bookmark / All bookmarks / Create bookmark for change) and defaults to "Tracking bookmarks (default)" on a fresh install
- [ ] Setting it to "Create bookmark for change" and clicking Apply makes Push open with the fourth radio already selected from **both** the log's right-click menu and the toolbar/VCS-menu button, and the setting persists across an IDE restart. Right-clicking a **bookmark** → Push… (the per-bookmark entry point) still opens on "Specific bookmark" (the setting only affects the dialog's *unforced* default). With a cross-repo log selection, toolbar Push falls back to "Tracking bookmarks (default)" because the fourth scope is unavailable (see MT-GIT), with no crash and no radio stuck on a hidden option

#### Escape hatches (ignore scanning and context window)

- [ ] **General** → "Disable ignored-file scanning (all repositories)", checked with no per-repo override and Applied, stops scanning in every repo; a repo whose own override is explicitly "off" (see Repository Settings below) still scans despite the global checkbox
- [ ] **Log Settings** → "Context window" set to 0 and Applied: navigating to a revision outside the loaded log (e.g. via file annotation, or jumping to a bookmark outside the current revset) shows exactly one row (the target), with no ancestors/descendants. Setting it back to 10 and repeating returns the ~19-revision window
- [ ] **Repository Settings** (multi-repo project), in a repo's collapsible group: the "Override context window" checkbox + field lets that repo use a different window (e.g. 0) than the project default, so only that repo's out-of-view navigation degenerates. The "Override ignored-file scanning default" checkbox + "Disable ignored-file scanning for this repository" checkbox let a repo explicitly re-enable scanning even when the global default is "disabled everywhere"; the override wins in both directions

#### Installation Help

The settings-side upgrade prompts for old jj versions are under MT-WORKINGCOPY "Feature-gated upgrade prompt in Installation Help".

- [ ] With a healthy, up-to-date jj configured, expanding "Installation Help" reads as a neutral "up to date, update later with:" (not "if jj is not installed…") and the command rows show update commands (e.g. `brew upgrade jj`), not install commands
- [ ] With Settings still open, changing the "JJ executable path:" field to an older jj or a bogus path (e.g. `/bin/ls`) and clicking **Test**, without Apply, updates Installation Help's wording, gated-feature list and command rows in place to preview the just-tested path. Clicking **Apply** on that path keeps Installation Help reflecting it (no flicker back to the previous path's wording), and other jj-availability consumers (e.g. the Working Copy tool window) pick up the change too
- [ ] "Homebrew:"/"Cargo:" (or whichever install methods are detected) have a comfortable, clearly visible gap before their command box, not sitting close to it. The gap comes from `.gap(RightGap.COLUMNS)`; a Border on the label looks fine in a unit test but is invisible on screen

#### Settings panel width

→ automate: `JujutsuConfigurablePanelTest` covers the panel's overall preferred width and a long validation message, both without any repository configured; the checks below cover what that test can't (a live dialog, and the per-repo group, which needs a real project).

- [ ] At the dialog's default size there is no horizontal scrollbar anywhere in the panel, and the **Test** button next to the executable path is fully visible. Narrowing the Settings window/pane as far as the IDE allows never needs horizontal scrolling to keep the "JJ executable path:" row's field + Test button and Installation Help's command rows + Copy buttons fully visible. Widening and narrowing makes the executable path and revset fields grow and shrink with it
- [ ] **Test** with a bogus executable path (e.g. `/bin/ls`) wraps the error message over multiple lines instead of widening the panel, and **Test** on an invalid revset (e.g. `zz(`) in **Log Settings** wraps the raw `jj` error likewise
- [ ] **Log Settings**: the "Revset expression:" box is a ~3-line multi-line field whose left edge lines up with the "Changes to show:" and "Context window:" fields above it, with its guidance text below lined up with that same left edge (not the row's label). An expression longer than the box's width word-wraps inside it instead of scrolling horizontally, and pressing Enter does not submit or misbehave
- [ ] In a **multi-repo** project, expanding a repo under **Repository Settings** (also with "Override diff base for this repository" on and "Custom revset" selected) shows no horizontal scrollbar; the "Override revset expression" and "Override ignored-file scanning default" rows read correctly with their field/checkbox stacked under the label, the Test button is fully visible, and Apply still persists every override (identity, limit, revset, context window, ignore-scan)

#### Scoped user identity (GitHub #89)

→ automate: `CliExecutorConfigTest`, `ConfigResolveTest` cover the provenance parsing;
`ScopedIdentityContractCliTest` covers the real-`jj` resolution and that the "Configure Jujutsu
User" prompt stays quiet; the check below covers what only a live Settings dialog can show (the
row's rendering and its interaction with the global fields and the override checkbox).

This needs a real scoped `~/.config/jj/config.toml` (or an equivalent `--when.repositories` config file), which no automated test can supply.

- [ ] With a `[[--scope]]` block in your jj user config (`jj config edit --user`) whose `--when.repositories = ["<path to a repo open in the IDE>"]` sets a `user.name`/`user.email` different from your normal `[user]` table, Settings → Version Control → Jujutsu → that repo's **Repository Settings** group shows an "In effect: \<scoped name\> \<scoped email\>" line above "Override user identity", with a second, greyed line naming the config file the scope came from. The panel's top-level **User Identity** fields still show your normal, unscoped name/email, and Apply only ever changes that unscoped value
- [ ] Removing the scope block and reopening Settings shows your normal identity in that row (or no row at all if neither is set). With **only** the scope supplying an identity (no `[user]` table, no per-repo override), no "Configure Jujutsu User" notification appears for that repo on IDE startup

#### Preview features

The Preview features group is shown whenever a feature is gated (currently **Operations tab** and **Manage Git remotes**). Once nothing is gated (all graduated), Settings has **no** Preview features group and no Access code field, and the group's behaviour is covered by `JujutsuConfigurablePanelTest` with a test-only feature. The steps below assume a gated feature exists. See MT-DND for drag and drop (no longer gated).

- [ ] A **Preview features** group appears at the bottom of the panel, below **Support**, with only an "Access code:" field and a one-line explanation, with no feature names anywhere. An invalid code (no Apply, no reopening) shows a red "Not a valid access code…" line under the field as soon as the text changes, with no feature list and no crash
- [ ] A valid legacy code turns the line grey with "Code accepted. Choose the features to enable below, then click Apply.", shows a checkbox for every preview feature, and shows a single shared comment (noting the feature is unfinished and that reopening the IDE is needed for a toggle change to take effect) once below the whole list, not per checkbox, all without leaving Settings. Clearing the field removes the status line, checkboxes and shared comment immediately
- [ ] A valid, single-feature `JJP1-...` code (needs a build whose signing key resolves: `PREVIEW_CODE_KEY` set, or the local key at `~/.config/jj-idea/preview-code-key` that `previewCode keygen`/`mint` use by default, e.g. minted via `./gradlew previewCode --args="mint --features <featureId>"`) shows "Code accepted…" and only that feature's checkbox. A `JJP1` code minted with `--expires` in a future month shows "Code accepted, valid until …" above its checkbox(es); one minted with `--expires` last month shows a red "This code expired on …" line and no checkboxes
- [ ] On a build **without** the signing key (CI artifact from a non-release run, or a dev build with no `PREVIEW_CODE_KEY` and no local key file), any `JJP1-…` code shows a red "This build of the plugin can't check codes that start with JJP1…" line (not "Not a valid access code"), while a legacy code still works there. Installed from the JetBrains Marketplace (not a GitHub zip), a valid `JJP1-…` code is accepted
- [ ] With a feature ticked and a code accepted, Apply and reopening Settings keeps the checkbox ticked; clearing the code and Apply leaves the feature off after reopening the IDE

### MT-DND

**Drag and drop**

**Code:** `ui/log/JujutsuLogTableDnD.kt`, `ui/common/JujutsuChangesTreeDnD.kt`, `ui/log/bookmarks/JujutsuBookmarksPanelDnD.kt`, `ui/components/IconAwareHtmlPaneDnD.kt`

Drag and drop is always on (not gated by the Preview features group). The log table's core drag infrastructure is under MT-LOG-TABLE "Drag and drop: core".

#### Drag image

**Code:** `ui/log/JujutsuLogTableDnD.kt` (`dragImage`)

- [ ] Dragging a commit row shows a small semi-transparent label following the cursor for the whole drag, with the commit's id and description (or "N commits" for a multi-selection), as dragging a file in the Project view shows its name/icon. The id is styled as everywhere else in the log (bold unique prefix, grey remainder, coloured offset suffix on a divergent change), not plain text
- [ ] Dragging a bookmark chip shows its name with the bookmark's icon, and dragging a tag chip shows its name with the tag icon
- [ ] With the drag-scope icon group set to "Commit" (default), the commit label has no extra icon. Setting the scope to the tree icon starts the label with a small tree icon before the id (a fixed badge, not a count), and the branch icon starts it with a small branch icon. Dragging a bookmark/tag/`@` chip is unaffected by the scope selection (no badge)

#### Drop hint text next to the cursor

Every drop target shows a small text bubble beside the cursor: the operation a drop would perform (allowed) or the reason it can't (rejected). It is painted by the plugin itself, because the platform's own drop tooltip is off by default (`ide.dnd.textHints`), so this is the only place a rejection's reason is visible. It applies to the log table and the Bookmarks panel; the changes tree and details panel are drag sources only.

**Code:** `ui/dnd/DropHint.kt`, `ui/dnd/RejectOverlay.kt`, `ui/log/JujutsuLogTableDnD.kt`,
`ui/log/bookmarks/JujutsuBookmarksPanelDnD.kt`

- [ ] In a multi-repo project, dragging a commit onto a row from a **different** repo shows a red fill on the row **and** a red bubble beside the cursor reading "Cannot drop across repositories" (or the guard's actual wording), following the cursor while you stay over the row. Dragging onto an immutable commit shows "&lt;id&gt; is immutable"; onto a descendant of the dragged commit shows the cycle reason
- [ ] Dragging onto a valid centre band shows a neutral (tooltip-coloured) bubble "Rebase &lt;id&gt; onto &lt;id&gt;"; the top/bottom band shows the "inserting after/before" wording. The bubble changes text as you move between zones/rows without leaving a stale one behind
- [ ] Change ids in the bubble are styled as the log styles them (bold unique prefix, grey remainder, a coloured `/N` suffix on a divergent change), e.g. an allowed rebase ("Rebase **kk**mpztqr onto **mz**rsnvtu") and an immutable reject ("**ab**cdefgh is immutable"). Bookmark and tag names are icon chips coloured like the log's (bookmark icon + colour, tag icon + colour): dragging a bookmark chip onto a commit ("Move bookmark [chip] to **kk**…"), a tag chip likewise, and a local bookmark onto its `@remote` chip ("Push [chip] to origin"). A deleted bookmark is struck through and a conflicted one shows the conflict icon, as in the log, in the dark theme too. There is a normal gap between the chip's last letter and the following "to"
- [ ] Dragging a bookmark with a **very long** name (~50 characters) onto a commit shows three lines, "Move bookmark", the whole name with its icon on a line of its own, then "to kk", with nothing clipped at the right edge and no word split mid-way. A bookmark with an **absurdly long** name (~150 characters, wider than the bubble) is shortened in the middle with "…" so it fits and never runs past the bubble edge. The same holds for a tag chip and at a non-100% UI scale (Settings → Appearance → Zoom): the layout follows the scale, not the pixel count
- [ ] The bubble stays fully visible at the far right and bottom edges of the log (clamped inside the window), and wraps onto several lines instead of running off the window edge when the IDE window is narrow and you drag a `-s`/`-b` scope rebase (the longest label)
- [ ] Moving the cursor **over** the bubble itself (e.g. near the right edge, where it is pushed left under the cursor) keeps the drag working: the hovered row still highlights and a drop there still lands. The bubble is drawn **above** the red fill and stays legible over it
- [ ] Dragging a commit onto itself shows no fill and **no** bubble (deliberately silent). Moving off any row (empty space below the last row), leaving the log for another panel, or releasing/cancelling with Esc makes the bubble disappear every time, with nothing lingering after the drop
- [ ] The cross-repo and allowed cases on the **Bookmarks panel** (dragging a commit onto a bookmark of another repo; a bookmark onto a valid commit) give the same bubble behaviour. With Registry key `ide.dnd.textHints` on (Help → Find Action → Registry), only **one** hint appears (the platform's), not two; turn it back off afterwards. The bubble and red fill are readable in both light and dark themes

#### Commit → commit rebase by drag

The headline drag gesture: dropping a dragged commit row (or multi-selection) onto another row's centre band runs `jj rebase --onto`; the top/bottom bands run `-A`/`-B`. It applies immediately with no confirmation dialog and an undo balloon on success. `RebaseSourceMode` defaults to `-r` (only the dragged commit(s) move); see "Rebase source scope" below for `-s`/`-b`.

**Code:** `ui/dnd/DropPerformers.kt`, `ui/log/JujutsuLogTableDnD.kt`, `actions/change/executeRebase.kt`

- [ ] Dragging a mutable commit onto another's **centre** band shows a row outline and the tooltip "Rebase &lt;id&gt; onto &lt;id&gt;", and release applies (confirm the new parent with `jj log` in a terminal). Dragging onto the **top** band shows a thin band outline and "...inserting after..." and lands **visually above** the destination; the **bottom** band lands visually below (`-B`). The top/bottom ↔ `-A`/`-B` mapping is deliberately non-identity (`ui/dnd/DropTarget.kt`) and was a shipped bug once
- [ ] Each successful drop shows an undo balloon whose Undo link reverts the graph to its prior shape; dismissing a balloon on a separate drop and using the persistent Undo Last Operation action has the same effect. The existing **Rebase...** dialog action (context menu / toolbar) also shows an undo balloon on success
- [ ] Dragging a multi-row selection (Shift/Ctrl-click first) rebases all selected commits together
- [ ] Dragging onto an immutable commit, or onto a descendant of the dragged commit (a cycle), shows a **filled** (not outlined) reject indicator on that row and a reject cursor if it lands; the drop does nothing. Hovering slowly, the filled indicator is reliably visible every time you're over that row, not an occasional flicker (the native reject cursor alone is not reliable feedback). The reason text beside the cursor is checked in "Drop hint text" above. In a multi-root project, dragging a commit onto a row from a different repo in the unified log shows the same filled reject indicator
- [ ] Dragging a commit onto itself, or onto another member of the same multi-selection, shows the reject cursor with **no** indicator at all (deliberately silent)

#### Copy-modifier drag to duplicate

The same three zones as the plain rebase gesture, held with the platform's copy modifier: **Option (⌥) on macOS**, matching the Project view's file drag; Ctrl on Windows/Linux (Ctrl also copies on macOS but is bound to the secondary-click convention there, so prefer Option). It runs `jj duplicate` instead of `jj rebase`, and the original commit(s) stay exactly where they were.

**Code:** `ui/dnd/DropPerformers.kt`, `actions/change/duplicateOntoAction.kt`

- [ ] With the copy modifier held, dragging a commit onto another's **centre** band shows the tooltip "Duplicate &lt;id&gt; onto &lt;id&gt;", and release applies: `jj log` shows the original untouched and a new duplicate as a child of the destination. The **top**/**bottom** bands put the duplicate in the insert-before/after slot with the same non-identity mapping as plain rebase. A multi-row selection duplicates all selected commits together, originals untouched
- [ ] Each successful duplicate shows an undo balloon; Undo removes the new commit(s) and leaves the originals as they were. The existing **Duplicate Onto...** dialog action also shows an undo balloon on success
- [ ] Duplicating a commit onto an **immutable** destination is still allowed (`jj duplicate` never rewrites the dragged commit, so the "cannot rewrite an immutable commit" guard does not apply). Releasing the copy modifier mid-drag switches the tooltip/operation back to "Rebase..." for the same pointer position, and pressing it switches back

#### Drag a bookmark or tag chip onto a commit to move it

**Code:** `ui/dnd/DropOperation.kt`, `ui/dnd/DropPerformers.kt`, `ui/log/JujutsuLogTableDnD.kt`,
`actions/bookmark/moveBookmarkAction.kt`, `actions/tag/setTagAction.kt`

- [ ] Dragging a bookmark chip onto a different row's centre band shows the tooltip "Move bookmark &lt;name&gt; to &lt;id&gt;" and release applies immediately with an undo balloon; `jj log` shows the bookmark moved and the **selection follows the bookmark to its new row**. A tag chip shows "Move tag &lt;name&gt; to &lt;id&gt;" with the same immediate apply and undo balloon. A bookmark dragged forward onto a descendant applies with no prompt
- [ ] Dragging a bookmark **backward** (onto an ancestor, or a row not reachable forward) shows a "Move Bookmark Backward?" confirm-and-retry prompt (the same one **Move Bookmark Here...** shows, reused verbatim), and confirming applies the move with `-B`. Unlike the dialog's version, the drag path cannot pre-classify direction, so this appears for **any** deliberate backward/sideways drag, and its wording says so plainly without claiming a dialog or a race occurred
- [ ] Moving a bookmark forward, undoing it (balloon or Undo Last Operation), then dragging it forward again to a genuinely different descendant of its (now-reverted) position applies cleanly with **no** backward-move prompt. If the prompt appears, that is a real bug (raw `jj bookmark set` does not misclassify this sequence); check with `jj log` first that the second target really is a descendant of the post-undo position
- [ ] Dragging a tag onto a revision where a differently-placed tag of the same name exists shows the **Set Tag Here...** dialog's "tag already exists, move it?" prompt, and confirming applies with `--allow-move`
- [ ] Dropping a **non-conflicted** bookmark/tag chip back onto the **same row** it's already on (not onto another chip on that row) never shows an indicator and is a silent no-op. For a **conflicted/divergent** bookmark or tag, each of its targets has its own chip on its own row (build one with the divergent-bookmark recipe in MT-BOOKMARK "Move direction"), and dragging either chip onto the row it's already on **is** an operation: the tooltip reads "Resolve bookmark &lt;name&gt; to &lt;id&gt;" (bookmarks) or "Move tag &lt;name&gt; to &lt;id&gt;" (tags), release applies with an undo balloon, and `jj bookmark list`/`jj log` then show a single, unconflicted target. After undoing, repeating onto the **other** chip's row resolves there too, not a silent no-op
- [ ] Dragging a chip near a row's **top/bottom edge band** still resolves to that row's centre (no gap-based operation for a chip drag), with no indicator flicker near the edge. Dragging a chip across repositories in a multi-root project shows the filled reject indicator, as for a commit drag
- [ ] Dragging a **commit row** onto a **tag chip** shows the tooltip "Move tag &lt;name&gt; to &lt;id&gt;", with the same immediate apply and undo balloon as dragging the tag chip itself (not a plain rebase onto that row)

#### Drag a local bookmark chip onto its remote chip to push

**Code:** `ui/dnd/DropOperation.kt`, `ui/dnd/DropPerformers.kt`, `actions/bookmark/pushBookmarkAction.kt`

- [ ] With a local bookmark ahead of a tracked `name@remote`, dragging the local chip onto the remote chip (same row or a different row) shows the tooltip "Push &lt;name&gt; to &lt;remote&gt;", and release opens **Git Push** pre-filled to "Specific bookmark" with that bookmark/remote selected; it never pushes without this dialog, and the dialog's own force-push/deletion/untracked warnings still appear. Cancelling the pre-filled dialog pushes nothing and shows no error
- [ ] With the local bookmark already in sync with its remote, dragging local onto `name@remote` shows no indicator (the "chips already coincide" case does nothing, no crash). Dragging a **remote** chip (`name@origin`) onto the **local** chip (reverse direction) shows no indicator and is not a push. With two remotes tracked, dragging onto each remote's chip in turn opens the dialog pre-filled to that specific remote

#### Files → commit squash, files → gap split

Dragging a file selection out of a changes tree is the first drag source outside the log table: the changes tree only ever *sources* a `Files` payload and the log table stays the only drop target, with the same `CommitRow`/`Gap` hit-test as every other payload. Squash and split are both dialog-gated (squash merges content and abandons the emptied source; split rewrites the source's content).

**Code:** `ui/common/JujutsuChangesTreeDnD.kt`, `ui/dnd/DragGuards.kt` (`filesRejectionReason`),
`ui/dnd/DropPerformers.kt`, `actions/filechange/SquashIntoFilesAction.kt`
(`performFileSquashInto`), `actions/filechange/SplitFilesAction.kt` (`performFileSplit`)

- [ ] Selecting one or more files in the **Working Copy** panel's changes tree and dragging onto another mutable commit's **centre** band shows a tooltip naming the squash, and release opens **Squash Into** pre-filled with those files ticked and that commit as the fixed destination (not a free picker); confirm with `jj log`/`jj status` after accepting. The same drag from a **commit's own changes tree** (a historical, non-`@` commit selected in the log, files dragged from its details-panel tree) onto a different commit opens the same pre-filled Squash dialog
- [ ] Dragging files onto the owning commit's own **top or bottom** band shows a tooltip naming the split, and release opens **Split** pre-filled with those files ticked: the **bottom** band opens in "new parent" mode (`jj split -B`, the ticked files become a new commit inserted as the owning change's parent) and the **top** band in the default "new child" mode; confirm the resulting position with `jj log`. Dragging files into a gap on any **other** commit shows a filled reject indicator reading "Files can only be split out next to their own change", with no dialog
- [ ] Dragging files onto their **own** owning commit's centre band shows no indicator and is a silent no-op with no dialog. Dragging onto an **immutable** destination shows a filled reject indicator "&lt;id&gt; is immutable". With the owning commit itself immutable, dragging its files anywhere (a commit row or a gap) shows a filled reject indicator "Cannot rewrite an immutable commit"
- [ ] Cancelling either pre-filled dialog runs no command and shows no error
- [ ] No drag initiates when files belong to more than one commit (several commits selected in the log, files selected across them in the commit-details tree: no single owning change), or when selecting files belonging to another repo while a different repo is bound in a multi-root project (no single owning repo)
- [ ] Neither drag source disturbs the changes tree's existing behaviour: double-click to diff, the right-click context menu and the diff preview still work normally on the same tree

#### Bookmarks panel as drag source and drop target

The bookmarks panel is a second surface a bookmark/tag can be dragged from and to, in either direction with the log table. Identity is carried directly as repo + change id, not a hydrated log entry, so a bookmark whose change fell outside the loaded log window is still draggable and droppable.

**Code:** `ui/log/bookmarks/JujutsuBookmarksPanelDnD.kt`, `ui/dnd/DropTarget.kt`,
`ui/dnd/DragPayload.kt`

- [ ] Dragging a local bookmark node from the panel onto a log commit row's centre band shows a tooltip naming the move, and release applies with an undo balloon, as dragging the log's own chip does. This works even after scrolling or filtering the log so the bookmark's change is **not currently loaded**, dragging onto a commit that *is* visible. Dragging a bookmark/tag node shows the same small cursor-following chip label as the log table's own chip drag
- [ ] Dragging a log commit row onto a bookmark node in the panel is the same operation, reversed. Dragging a log commit row onto a **tag** node, or a tag node onto a log commit row, moves the tag there
- [ ] A bookmark node with no target commit (deleted, or a pending-delete row) neither drags nor accepts a drop (try one you've just deleted but not yet refreshed away). Dragging a bookmark/tag node onto itself, or back onto the row it already sits on, shows no indicator and is a silent no-op
- [ ] In a multi-root project, dragging a commit from one repo's log row onto another repo's bookmark node in the panel (or vice versa) shows a filled reject indicator on the panel node, with the same hover-slowly reliability check as the log table's cross-repo case
- [ ] With a conflicted bookmark (the divergent-bookmark recipe in MT-BOOKMARK "Move direction"), dragging its **single node** in the panel onto `<rev-a>`'s log row and, separately (undo in between), onto `<rev-b>`'s row **both** show the "Resolve bookmark conflicted-bm to &lt;id&gt;" tooltip and apply (neither is allowed to silently no-op like a single-target bookmark's self-drop); `jj bookmark list` shows the conflict gone afterwards
- [ ] Push within the panel: with a local bookmark ahead of a tracked remote, dragging the local node onto its `name@remote` node shows a tooltip naming the push, and release opens **Git Push** pre-filled to that bookmark/remote (never pushing without the dialog), even when the remote bookmark's commit isn't loaded in the log at all

#### Bookmark/tag chip drag source in the commit details panel

A bookmark or tag chip in the metadata pane (bottom of the commit details panel) is draggable like a log or panel chip; dragging from plain description/metadata text is unaffected.

**Code:** `ui/components/IconAwareHtmlPaneDnD.kt`, `ui/components/IconAwareHtmlPane.kt`

- [ ] Selecting a commit with a bookmark and dragging its chip out of the details pane's metadata section onto another commit in the log moves the bookmark, as any other chip drag; the same works for a tag chip
- [ ] Click-dragging across plain description/metadata text in the same pane (not starting on a chip) still selects text normally, and that holds for a second gesture after starting and releasing a chip drag (the suppression doesn't stick). Right-clicking a chip in this pane still shows its usual ref context menu (Move/Push/Delete/etc.)

#### Drag the `@` marker: edit or new-on-top by zone

The working-copy `@` marker is a drag source using the same zone vocabulary as the commit-rebase gesture, not a modal dialog. Dropping **on** a commit's centre band runs `jj edit` (rejected outright if immutable, with no dialog); dropping in the band **just above** a commit runs `jj new` with that commit as parent, creating a new change on top of it, and is **always allowed**, even for an immutable target, since `jj new` never rewrites its parent. The bottom band has no operation for `@`.

**Code:** `ui/log/LogClickTarget.kt` (`WorkingCopyClick`), `ui/log/JujutsuLogTableRenderers.kt`
(`appendWorkingCopyMarker`), `ui/dnd/DropOperation.kt` (`EditWorkingCopy`, `NewChangeOnTop`),
`ui/dnd/DragGuards.kt` (the `WorkingCopyRef`+`CommitRow`+immutable check),
`ui/statusbar/JujutsuWorkingCopySwitcher.kt` (`editWorkingCopy`, `newChangeOnTop`)

- [ ] Dragging the `@` marker (next to the current working-copy row) onto a different **mutable** commit's centre band shows the tooltip "Edit &lt;id&gt;", and release runs `jj edit` immediately with an undo balloon (confirm with `jj log` that `@` moved). Dragging `@` onto an **immutable** commit's centre shows a filled reject indicator "&lt;id&gt; is immutable" with no dialog at all, and the drop does nothing
- [ ] Dragging `@` into the band **just above** any commit, mutable or **immutable**, shows the tooltip "New change on top of &lt;id&gt;", and release creates a new child immediately with an undo balloon and moves `@` there (confirm with `jj log`). The immutable case is the one most worth checking carefully: there must be **no** reject indicator and **no** dialog. Dragging `@` into the band **below** a commit shows no indicator (not an operation)
- [ ] Dragging `@` onto the row it's already on (any zone) shows no indicator and is a silent no-op. In a multi-root project, dragging `@` from one repo's row onto a row from a different repo shows the same filled reject indicator as a commit drag. Dragging `@` shows a small cursor-following chip labelled "@" in the usual working-copy colour
- [ ] The status-bar **Switch Working Copy** popup (click path, not drag) still asks its "Edit / New on Top / Cancel" dialog (a click has no zone to read the operation from) and still shows an undo balloon on success

#### Rebase source scope: `-r`/`-s`/`-b`

Three **mutually-exclusive icon toggle buttons** in the toolbar, next to the View Options button (a commit-dot icon, a tree icon and a branch icon), pick whether a commit-onto-commit drag rebases just the dragged commit(s) (`-r`, the default), the dragged commit(s) plus everything descended from them (`-s`), or the entire branch containing them (`-b`). Exactly one is pressed at a time; clicking a different one presses it and un-presses the others, as a diff viewer's unified/side-by-side toggle works. The setting is project-level and sticky across drags, so the drop tooltip and drag chip both name the resolved scope every time (deliberate anti-footgun UX, so confirm it rather than skipping past it). While dragging under `-s`/`-b`, the rows that would actually move are tinted live in the log, the same green `RebasePreviewPanel`'s dialog preview uses.

**Code:** `ui/common/CommitTablePanel.kt` (`DragScopeAction`), `settings/JujutsuSettingsState.kt`
(`dragRebaseSourceMode`), `ui/dnd/DragGuards.kt` (`DragContext.movedIds`), `ui/dnd/DropOperation.kt`
(`sourceScopeLabel`), `ui/dnd/DropPerformers.kt` (`toRebaseSpec`), `ui/log/JujutsuLogTableDnD.kt`
(`applyDragHighlight`)

- [ ] The toolbar shows three icon buttons next to View Options (commit dot / tree / branch), the commit-dot one pressed by default, each with a tooltip naming its scope. Clicking the tree icon presses it and un-presses the commit-dot icon (exactly one pressed at a time)
- [ ] With the tree icon selected, starting to drag a commit that has children (without dropping) tints its descendant rows green live, before you release, and the dragged commit's own row too; releasing or cancelling (Esc, or dropping off-target) removes the tint. Completing the drag shows the tooltip "Rebase &lt;id&gt; and its N descendant(s) onto &lt;id&gt;" (singular for exactly one descendant) before release, and `jj log` shows the children moved too. A **leaf** commit (no descendants) reads exactly like the plain `-r` case (no "and its 0 descendants" wording) and shows **no** tint
- [ ] With the branch icon, dragging a commit shows the tooltip "Rebase the branch containing &lt;id&gt; onto &lt;id&gt;" (no count) and tints the whole branch live; `jj log` shows the whole branch moved
- [ ] With the tree or branch icon selected, dragging a **mutable** commit whose descendants/branch include an **immutable** commit elsewhere shows a filled reject indicator "Cannot rewrite an immutable commit", even though the commit under the cursor is mutable (if it's missing, that's a real bug). Clicking the commit-dot icon again, the same case no longer rejects (only the dragged commit's own immutability matters) and no live tint appears for a plain drag
- [ ] With the tree or branch icon selected, holding the **copy modifier** and dragging a commit still duplicates only that one commit (the tooltip has no scope wording and no live tint appears): `jj duplicate` has no `-s`/`-b` axis, so the selector is inert for a copy-modifier drag (confirm with `jj log` that descendants were *not* duplicated)
- [ ] The scope choice persists after closing and reopening the project (or restarting the IDE)

### MT-TASKS

**IntelliJ Tasks integration (`vcs.taskHandler`, GitHub #102)**

**Code:** `vcs/JujutsuTaskHandler.kt`, `META-INF/plugin.xml` (`vcs.taskHandler`)
**Also re-run:** MT-WORKINGCOPY (switching moves `@`); MT-BOOKMARK (task bookmarks appear in the widget and panel)

Requires the bundled Tasks plugin (Tools → Tasks & Contexts). Use a repo with a pushed/immutable `main` bookmark and a mutable change on top.

- [ ] Tools → Tasks & Contexts → Open Task… → creating a local task "PROJ-1 test" shows a dialog offering **Create branch**, a **From:** list of bookmarks and a suggested name that is a valid bookmark. A name with a space or `@` is rejected. Committing the dialog with Create branch shows in `jj log` a bookmark with that name on a new change that is `@`, described with the task's commit message; when `@` was already empty and undescribed, no extra empty change is left behind
- [ ] Creating a second task and switching to the first (Tools → Tasks & Contexts) returns `@` to the first task's newest change, and uncommitted edits in the second task are still there when you switch back (no shelving needed). A task whose bookmark is on an immutable change (e.g. `main`) creates a new change on top instead of editing. **Use branch** with an existing bookmark switches `@` to it. The Undo balloon after a switch reverts the switch
- [ ] Close Task (Tools → Tasks & Contexts → Close Task…): the **Commit changes** box is Tasks' own and shows "Jujutsu tracks changes automatically" if ticked (nothing to commit). With **Merge branch** ticked, a dialog offers *Rebase onto \<original\>*, *Merge* and *Just Switch Back* (plus "Remember my choice"), and Cancel leaves the repo untouched
- [ ] Rebase (after doing two changes of work past the task bookmark): the bookmark ends on the newest of them (not left behind), the whole stack sits on the original bookmark, a mutable original bookmark has fast-forwarded to it, and `@` is on that stack. Rebasing onto an immutable original (e.g. `main`) leaves `main` where it is, with `@` a new change on `main`
- [ ] Merge produces a "Merge \<task\>" change with both as parents, a fresh empty `@` on top, and the mutable original bookmark on the merge change. Just Switch Back rewrites nothing and moves no bookmark (the task bookmark is always kept)
- [ ] "Remember my choice" ticked makes the next close skip the dialog (reset by clearing `jj.tasks.closeMode` in `options/other.xml` of the IDE config)
- [ ] In a multi-root project the task applies to every jj root that has the bookmark; in a project with no jj root the branch options are hidden in the Open Task dialog

### MT-OPLOG

**Operations tab (`jj op log` browser) - preview feature `OPERATION_LOG`**

**Code:** `ui/oplog/*`, `ui/log/JujutsuRootFilterComponent.kt`, `actions/undo/ShowOperationLogAction.kt`, `jj/cli/CliExecutor.kt` (`opRestoreArgs`), `preview/PreviewFeature.kt`
**Also re-run:** MT-CROSS (the roots filter component is shared with the log); MT-WORKINGCOPY (revert/restore invalidate repo state and the working copy); MT-LOG-REFRESH (the tab refreshes on the same funnel); MT-CTXMENU (VCS → Jujutsu submenu entry); MT-SETTINGS (Preview features group)

Precondition: the feature is gated. Either enable it with `-Djjidea.preview.operationLog=true` (runIde / VM options) or enter an access code granting **Operations tab** under Settings → Version Control → Jujutsu → Preview features, tick it, Apply, and reopen the IDE. Use a repo with a few changes and a dirty working copy.

- [ ] **Gating:** with no access code and no property, VCS → Jujutsu has **no** "Show Operation Log" entry (hidden, not greyed out), Find Action does not offer it, and the "+" in the VCS tool window tab strip opens a new log tab directly with a single click (no menu). With the feature enabled, the "+" becomes a small menu offering **Open New Jujutsu Log Tab** and **Show Operation Log**, both working
- [ ] VCS → Jujutsu → **Show Operation Log** opens an **Operations** tab in the VCS tool window, and running it again re-selects the same tab rather than opening a second. Closing the tab and reopening it rebuilds the list with no errors in `idea.log`; disabling the plugin dynamically (Settings → Plugins) with the tab open makes the tab disappear
- [ ] Rows are newest first with Description, Command and Time columns (Time last, like the log); the bottom row is the "(repository created)" root operation. Time reads "Today 14:05" / "Yesterday 09:12" / a locale date exactly like the log's Date column, with the full absolute time in its tooltip
- [ ] **Snapshot toggle:** "snapshot working copy" rows are hidden by default; the toolbar **Show Snapshot Operations** toggle reveals them and the selected row stays selected. **Terminal operation appears:** running `jj describe -m "from terminal"` in a terminal and focusing the IDE shows the new top row with its command after the repo refreshes; with the head unchanged, Refresh does not reset the scroll position or selection
- [ ] **Revert (primary):** selecting an older, non-root row and pressing **Revert This Operation** (toolbar and right-click) undoes only that operation's effect, leaves later operations' effects in place, adds a new row at the top and updates the log and Working Copy panel
- [ ] Revert and Restore are **disabled with a tooltip reason** on the root row and with nothing selected; Restore is also disabled on the top (current) row
- [ ] **Restore (secondary):** right-click shows Restore in its own section below a separator, labelled "Restore Repository to This Point...". Choosing it shows a confirmation naming the operation and how many later operations are discarded, and **Cancel** changes nothing. Confirming resets the repo to that point (`jj log` matches the state after that operation), adds a new top row, and refreshes the log/Working Copy panel. Revert and Restore never raise an undo balloon
- [ ] **Multi-root:** with two jj roots, a **Repository** chip (same as the log's) appears at the right of the toolbar, absent with one root, and the tab opens showing only the repo it was opened from. Cycling roots in the chip (include / exclude / unset) and **Select All** behave like the log's; with both repos shown, rows interleave by time, each with a repo-coloured square and the repo name in the Description tooltip, and with one repo shown there are no squares. Revert and Restore act on the **row's own repo**, and Restore's confirmation counts only that repo's later operations. The log's own root filter is unchanged (see MT-CROSS)

### MT-CROSS

**Multi-repository, visual consistency, edge cases, and error handling**

**Code:** `ui/log/JujutsuRootFilterComponent.kt`, `ui/log/RootFilterSelection.kt`, `ui/log/JujutsuRootGutterRenderer.kt`, `ui/log/RepositoryColors.kt`, `ui/common/JjNotInstalledPanel.kt`

#### Multi-Repository (if applicable)

- [ ] The Root filter appears for multi-root projects and hides for single-root projects, and the root gutter column shows repo names. Entries from different roots sort by timestamp. Reference filter and graph repo scoping are under MT-LOG-FILTER's "Multi-repo scoping" subsection (no cross-repo ancestry or graph lines, even when repos' root commits share a change id)
- [ ] Tri-state root filter (GitHub #96): the Root filter popup shows a "Select All" header, then one outlined coloured square per root. Clicking a root once fills its icon, keeps the popup open and shows only that root in the log; clicking again shows an outlined-and-struck-through square and every root *except* it; clicking again returns to outline and shows everything
- [ ] Including one root and excluding another shows only the included root (included wins); cycling the included root back to unset leaves the excluded root hidden and shows everything else. With a root excluded, "Select All" fills every root and clears the exclusion, and "Select None" returns all icons to outline and shows every row. The include/exclude selection persists across an IDE restart
- [ ] With an include active, mapping an additional repo (**Settings → Version Control**) leaves it hidden. After clearing the filter and excluding one root instead, a newly mapped repo shows

#### Visual Consistency

- [ ] Light and dark themes both render correctly: icons at correct size, row striping, hover highlighting, distinct selected-row highlighting, disabled actions grayed out

#### Edge Cases

- [ ] An empty repository shows an appropriate message; a large repository (100+ commits) loads without hanging; rapid filtering doesn't cause errors. Very long descriptions truncate with an ellipsis and non-ASCII characters display correctly

#### Error Handling

- [ ] Invalid JJ path, non-JJ repository, network errors and concurrent operations each show a helpful, user-friendly message rather than corrupting state or crashing
