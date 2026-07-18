# Changelog

All notable changes to the Jujutsu VCS Plugin for IntelliJ IDEA will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.7.15] - 2026-07-18

### Added
- "Compare with Working Copy" in the log's right-click menu diffs every file that differs between the selected commit and your working copy, in one editor tab with the working-copy side editable — handy for reviewing everything since a target commit and editing it directly for a final `jj absorb`. ([#40](https://github.com/kkkev/jj-idea/issues/40))
- The References/Bookmarks selector above the log now also lists bookmarks that exist only on a remote and aren't tracked locally, shown as `name@remote` with a distinct icon, so you can start work off a remote branch without tracking it first. ([#47](https://github.com/kkkev/jj-idea/issues/47))
- New "Tracked" checkbox in the right-click menu for selected files (Project view, editor, Working Copy panel, Commit view) that match a `.gitignore` rule lets you force-include or stop tracking them, without touching `.gitignore` by hand. The checkbox updates instantly, and a notification confirms what happened (or explains why it didn't). ([#42](https://github.com/kkkev/jj-idea/issues/42))
- The commit details panel now shows the author's (and committer's, when different) email address alongside their name, as a clickable mail link, with the date/time kept together on one line when it wraps. ([#51](https://github.com/kkkev/jj-idea/issues/51))
- New "New Change" quick action creates a change without prompting for a description upfront (the common case, since you'll usually squash it in later). It's the primary option in the log's right-click menu (the old dialog-based flow is kept as "New Change with Description..."), and is bound to Cmd/Ctrl+Shift+N while the log is focused. ([#51](https://github.com/kkkev/jj-idea/issues/51))
- New and Edit buttons in the log toolbar act on the selected change with one click, without opening the right-click menu. ([#51](https://github.com/kkkev/jj-idea/issues/51))
- New "Fit Columns to Window Width" option (Columns menu) shrinks the description and other columns as the log window narrows so horizontal scrolling is rarely needed, e.g. on a laptop screen or with the details pane docked beside the log. On by default for tabs you haven't customized; tabs where you've already resized a column keep your exact layout and scrolling as before. ([#51](https://github.com/kkkev/jj-idea/issues/51))

### Fixed
- Selecting a commit in the log no longer resets the table's horizontal scroll position. ([#51](https://github.com/kkkev/jj-idea/issues/51))
- On a narrow log window, the filters no longer crowd out the New/Edit/Refresh/Fetch/Push/Columns buttons. Filters that don't fit collapse into a "»" menu instead, with any filter you've actually applied kept visible longer than ones you haven't. ([#51](https://github.com/kkkev/jj-idea/issues/51))
- The New button in the log toolbar now greys out instead of disappearing when there's no selection, matching how the Edit button already behaved.

## [0.7.14] - 2026-07-11

### Fixed
- The plugin no longer bundles its own copy of the Kotlin standard library, reducing plugin size since the IDE already provides it. ([#41](https://github.com/kkkev/jj-idea/issues/41))
- Annotate (blame) no longer logs a spurious error when the project window is closed while an annotation is loading. ([#45](https://github.com/kkkev/jj-idea/issues/45))
- File-change actions (Open, Compare, Restore, etc.) no longer throw an error when a change from another VCS (e.g. Git in a colocated repository, or an unrelated VCS backend) is selected alongside or instead of a Jujutsu change. ([#50](https://github.com/kkkev/jj-idea/issues/50))

## [0.7.13] - 2026-07-07

### Fixed
- "Resolve Conflicts…" now appears (though disabled, with a hint to edit the change first) for a conflicted commit that isn't the working copy — including a commit that only inherits its conflict from an ancestor merge. Previously the option was hidden entirely, so there was no way to discover that the change could be resolved.
- The Working Copy tool window, and the "Resolve Conflicts…" actions, no longer show changes from Git in a repository that uses both Jujutsu and Git.

## [0.7.12] - 2026-07-07

### Added
- Splitting allows selection of specific hunks within files.
- You can now support me to keep working on this plugin! See the *Sponsor this plugin** link at the bottom of **Settings → Version Control → Jujutsu**.

### Fixed
- Opening a file with F4 from the commit details or working-copy changes panel no longer logs a "Synchronous execution on EDT" error. The same fix covers Show Diff, Show Diff in New Tab, and Compare File with Branch when invoked from a changes selection.
- Filtering the commit log (by text, author, date, reference, or root) no longer leaves the graph lines misaligned or pointing at hidden commits. The graph layout is now recomputed from the visible subset whenever the filter changes, and the renderer is updated before Swing repaints so no intermediate misaligned state is shown.
- Resolving a conflict now updates the Working Copy panel automatically — previously the file kept showing as conflicted even after clicking Refresh, and trying to resolve it again would throw an error.

## [0.7.11] - 2026-06-28

### Fixed
- A file newly created inside an ignored directory (e.g. matching a `.gitignore` pattern) now appears under "Ignored Files" in the Changes view immediately, without requiring a second edit to `.gitignore` to trigger a refresh.
- Opening a newly created ignored or unversioned file no longer produces a spurious warning in the IDE log.
- The ignored-file scan's slow-scan watchdog now reliably fires on directories containing very many files (previously it only checked elapsed time once per directory entered, so a single huge flat directory could block past the 5s threshold without ever showing the "scan is slow" notification). The watchdog now also aborts the in-progress scan when it fires, instead of letting it run to completion (GitHub #35).
- The log and working-copy view no longer fail to load entirely on jj backends that can't evaluate the `remote_bookmarks()` revset (e.g. some non-standard jj implementations). The plugin now falls back to loading the log without the "pushed to remote" decoration on affected repositories instead of crashing (GitHub #35).
- A commit with many bookmarks no longer hides the description or silently clips part of the bookmark list in the log table. Bookmarks/tags are now capped to half the column's width, with any overflow collapsed behind a clickable "+N more" chip that opens a popup of the hidden refs.
- The log row hover tooltip no longer gets clipped by the screen for commits with many bookmarks. It now reflows the bookmark list across multiple lines and scrolls if the content is taller than the screen, instead of cutting off the description and/or bookmarks.

## [0.7.10] - 2026-06-18

### Added
- The "Compare with Another Commit…" popup and the working-copy switcher now accept arbitrary revision expressions (change id, git SHA, bookmark name) that fall outside the loaded log window. Typing a query that matches nothing locally shows a "Use … as revision" fallback, which resolves in the background and upgrades to a full commit preview, including the correct mutability icon.

### Changed
- Ignored-file scanning now runs asynchronously off the modified-file refresh path. Working-copy changes appear immediately after a save regardless of ignored-tree size; the Commit toolwindow shows the built-in "updating" indicator while ignored files are computed in the background.
- In multi-repository log windows, the repository color gutter is now expanded by default (showing the repo name) instead of collapsed to a thin colored strip, making per-repo colors easier to discover. Each log tab remembers your collapse/expand choice (GitHub #10).

### Fixed
- Bookmark and tag chips in the commit details panel no longer break apart when wrapping to multiple lines — the icon could previously be separated from its label, and long hyphenated names could break mid-word; each chip now wraps as a single atomic unit.
- Typing a revision that doesn't exist in the revision-choice popup or working-copy switcher no longer logs a WARN-level entry; resolution failures for a single typed revision are now logged at INFO, since this is an expected outcome rather than a plugin error.
- Mutable/immutable icon in the working-copy switcher and revision-choice popup now correctly reflects the target commit's immutability for bookmarks and tags, even when the target is outside the loaded log window.
- Resolving conflicts now refreshes the working copy so resolved files no longer appear conflicted in the panel; re-opening the Resolve dialog after a successful resolution no longer throws "Could not extract conflict data".

## [0.7.9] - 2026-06-11

### Added
- Per-repository setting to disable ignored-file scanning (Settings → Version Control → Jujutsu → expand the repository section). Useful for very large repositories where the scan causes a startup hang.
- Slow-scan watchdog: if the ignored-file walk exceeds 5 seconds, a notification appears offering to disable scanning for that repository or copy diagnostic stats to the clipboard for a bug report.

### Fixed
- Restoring a renamed or deleted file now correctly undoes the change. Previously, restoring a rename would delete the target file without recreating the source (leaving the working copy in a broken state), and deleted files could not be restored at all because the Restore action was hidden.
- `collectTrackedAbsolutePaths` no longer includes the `Parent commit (@-):` summary line as a phantom tracked path (status output without a blank line between sections).

## [0.7.8] - 2026-06-11

### Fixed
- Ignore scan no longer walks into ignored directories (e.g. `node_modules`, `build`) on every working-copy refresh. Ignored directories are now pruned at entry and reported as a single entry, fixing the multi-second stall and 10s shutdown hang reported in GitHub #35.
- Read-access error (`RuntimeExceptionWithAttachments`) no longer logged when showing a diff from the History view or using Compare with Local, Compare Before with Local, Open File, or Compare File with Branch actions.
- Working-copy switcher widget no longer paints doubled (two icons and two copies of the text).
- Filter components (reference, author, date) now show the correct X close icon immediately after IDE restart when a filter is active — previously the icon stayed as a dropdown arrow until the filter was interacted with.
- Status bar widget no longer throws an EDT-access exception on startup in projects with Jujutsu repositories.
- Working-copy panel no longer flickers or lags with 40+ changed files. Rapid file-save bursts (e.g. save-all over a large working copy) are coalesced into a single tree rebuild per 200ms window using `MergingUpdateQueue`, and bulk VFS events now issue a single batched `filesDirty()` call instead of one call per file.

### Changed
- Improved log performance when expanding context around a commit in large repositories.

### Added
- Multiple independent log windows: click the **+** button in the VCS tool window tab strip to open a new tab (all repos, default name). In projects with both Git and Jujutsu, the action appears in Git's existing "▼" dropdown. Right-click any Jujutsu log tab → **Rename…** to rename it inline. All filter state (search text, reference, author, date, root filter selection) and layout (column visibility, widths, details position) persists per-window across restarts.
- Tag create/set and delete actions (requires jj 0.37+): right-click a commit in the log to set a tag at that revision, or expand the "Tag" submenu to delete an existing tag. Right-clicking a tag chip directly also offers a delete action. Moving an existing tag prompts for confirmation. The bookmark and tag chip plumbing (URI scheme, hit-testing, popup dispatch) is now unified.
- Show repository roots in change trees and bookmark widget menus using coloured repository icons.

## [0.7.7] - 2026-06-08

### Added
- jj tags now appear as decoration chips in the log table (Decorations column, inlined graph column, and commit details panel), the "Compare with Another Commit…" popup, and the working-copy switcher. Tags resolve as selectable revision targets alongside bookmarks, with a dedicated tag icon and a distinct green chip colour. A `jj tag list` cache ensures tags beyond the log limit are included in selectors.
- Tags in the working-copy switcher and revision selector now show the target commit's description alongside the tag name, matching the existing behaviour for bookmarks.

### Fixed
- Moving a file from a parent directory into a new child directory (e.g. `pages/{ => blankExperience}/blankExperience.tsx`) no longer causes the entire change to fail with "Invalid rename/copy format". Both sides of the `{old => new}` rename spec can now be empty, and the rename/copy parser is deduplicated into a single implementation.
- Typing in the "Compare with Commit" revision selector no longer freezes the UI on large repos. The cell renderer now reuses a single component instead of allocating a new one per cell (eliminating an O(n²) `ArrayList.indexOf` accumulation in `CellRendererPane`); list-model updates are applied in one batch call instead of one-at-a-time; per-render reflection (`IconSpec.javaField`) is hoisted to constants; and tag immutability is precomputed off the EDT instead of re-scanned linearly on each paint.
- Out-of-limit revisions loaded for navigation expansion (annotation-line click, parent change-ID click in the details panel) no longer render with empty Author and Date columns. `RepoLogCache` now fetches with the full log template so every cached entry carries author/committer signatures, matching the main log load.
- Reference filter (bookmark/tag dropdown in the log toolbar) now shows **all** bookmarks and tags regardless of the log limit. Selecting an out-of-limit reference automatically expands the log to include a context window around that commit (same behaviour as navigate-to-out-of-limit), then applies the filter normally. Selecting a tag or bookmark no longer mismatches when a tag and bookmark share the same name on different commits.
- Bookmark toolbar widget now shows **all** bookmarks regardless of the configured log limit; previously only bookmarks attached to loaded log entries were displayed (missing out-of-limit bookmarks in large repos).
- "Compare with Another Commit…" popup no longer runs `jj bookmark list` on every search keystroke; bookmarks are fetched once and filtered in memory, eliminating per-keystroke lag with 100+ bookmarks.
- `JujutsuAnnotationProvider.populateCache` no longer logs a spurious "control-flow exception" warning when the annotation preloader task fires after a project has begun disposing. The method now exits early on a disposed project and rethrows `ProcessCanceledException` so the platform can handle the race cleanly.
- "Compare with local" and "Compare before with local" no longer crash with a `VcsException` when the selected file does not exist in the current working copy (`@`). The diff now opens with an "(empty)" local side instead.
- `⌘D` / `Ctrl+D` in editor tabs no longer hijacks the Duplicate Line shortcut. `Jujutsu.ShowChangesDiff` now only activates on keyboard shortcut when there is an actual change or log selection in context; context-menu invocations are unaffected. The action promoter likewise only promotes the action over the built-in `Compare.SameVersion` when VCS or log data is present.
- **Show Diff** (`⌘D` / `Ctrl+D`) no longer throws a `VcsException` when invoked while a diff editor tab is focused. Synthetic diff-tab virtual files (`ChainDiffVirtualFile`) that have no Jujutsu VCS root are now silently skipped instead of causing an error.
- **Compare with Another Commit…** now works when invoked from inside a diff editor (previously a silent no-op). Focusing a diff side and invoking the action correctly opens the revision selector for that file.
- "Annotate Previous Revision" from the annotation gutter no longer shows a line-count mismatch warning. Previously the provider failed to recognise the revision type returned by `FileAnnotation.getRevisions()` and silently annotated at the working copy instead of the requested historical revision.

## [0.7.6] - 2026-06-06

### Fixed
- `JujutsuVirtualFile.contentsToByteArray()` no longer calls `jj file show` while a ReadAction lock is held, eliminating the threading violation and potential UI freeze. When the platform requests content under read access with a cold cache, the call returns an empty array immediately and launches a background load; once that load completes it fires a VFS `contentsChanged` event so any open editor reloads automatically with the real content.

## [0.7.5] - 2026-06-05

### Added
- Navigating to a commit that is outside the configured log limit (e.g. clicking a parent change ID in the details panel or an annotation line) now expands the log to show a context window around the target commit and selects it. In multi-repository projects, each repo's expansion accumulates additively — expanding a commit in repo A then repo B shows both context windows together. Clicking the Refresh toolbar button returns the log to the configured limit view.

### Fixed
- Working copy changes no longer require a manual refresh to appear on startup. Two fixes: (1) the change provider skips `jj status` until the working copy state is loaded (eliminating a wasted invocation), and (2) the working copy panel now reads from the `ChangeListManager` cache when it connects to `workingCopies`, so changes are visible immediately even if the panel was created after the initial VCS scan completed.
- Fixed a memory leak reported on IDE shutdown: `JujutsuCommitDetailsPanel` was registered in the Disposer tree under `ROOT_DISPOSABLE` instead of its owning panel, causing a `RuntimeException` on quit.
- Diff views for mutable revisions no longer show stale content after the revision is edited: cached file bytes are invalidated on `logRefresh` and re-fetched lazily on next access.

### Changed
- Git remote lookup is now backed by `NotifiableState` (consistent with working copy and repository state) instead of a per-repository `CompletableFuture`. Remotes are refreshed whenever the repository set changes, and BGT callers are guaranteed a loaded result via `immediateValue` rather than silently receiving an empty list during startup.
- Rebase, squash, move-bookmark, and revision-picker dialogs now open without issuing extra `jj log` calls when the main log is already loaded — they reuse the cached commit data instead.
- Single-revision lookups via `getLogEntry` now route through the in-memory `LogCache` for `ChangeId`, `CommitId`, and `BookmarkName` targets, falling back to a targeted `jj log -r` shell-out only on a cache miss or unknown revision type. Removed the dead `repositoryStates` state (superseded by `workingCopies`) and eliminated the redundant `jj log` fetch fired when the working-copy panel binds to a repository (the cached working copy entry from `workingCopies` is already applied by the `boundRepository` setter).
- The status-bar working-copy switcher resolves bookmark and commit-ID selections from the cache on a cache hit, avoiding an extra `jj log` call per switch.

## [0.7.4] - 2026-06-02

### Added
- Cmd+D / Ctrl+D on a selected log entry now opens the native diff preview — the same embedded tree+diff view you get by double-clicking a file in the commit details panel, with directory grouping and a `"abc1234: FileName.kt"` tab title. The action is also available in the log table's right-click context menu. "Open in New Tab" opens a standalone chain-viewer tab and likewise now works from a selected log entry.

### Fixed
- Toggling Description, Change ID, Status, or Decorations in the Columns menu now correctly shows or hides that content in the log view, rather than adding a duplicate column.
- Copied files are no longer shown as merge conflicts in the working copy panel. They now appear as rename-style entries (source → destination) and are correctly shown in commit history.
- Split, Rebase, Squash Into, and Squash From dialogs no longer silently fail when the relevant changes are not currently visible in the log (e.g. outside the display limit or filtered by the active revset). The Split preview graph now also appears when Split is opened from the Changes view.
- Squash Into, Squash From, and Rebase destination/source pickers now populate immediately when the dialog opens.

## [0.7.3] - 2026-06-01

### Fixed
- Selecting an immutable change in the status-bar working-copy switcher no longer fails with "Commit … is immutable". The switcher now queries jj directly for the selected revision's immutability (no longer limited to the 10 most recent changes), acts on the commit id to avoid divergent-change ambiguity, and shows a confirmation dialog before switching. Immutable items are also dimmed with a lock icon in the popup.

### Changed
- Status-bar working-copy widget now renders a full styled row (commit icon + change ID + truncated description) via the shared `TextCanvas` renderer, replacing the plain-text display. The widget is no longer built on the internal `DvcsStatusWidget` API and now uses `CustomStatusBarWidget` for proper platform integration. Widget refresh is coalesced into a single handler and is freeze-safe; a `FileEditorManagerListener` updates the displayed repository when switching files in multi-root projects.

### Added
- A bookmark management widget now appears in the log toolbar. In single-repo projects it shows the current working-copy bookmark name(s) and opens a dropdown with per-bookmark sub-menus (Create, Rename, Delete, Forget; remote bookmarks folded in as Track/Untrack). In multi-repo projects the label is blank and the dropdown groups bookmarks under one sub-menu per repository, with "Create Bookmark Here…" scoped to each repo's working copy.
- A working-copy switcher widget now appears in the bottom-right status bar for JJ-managed projects. It shows the current local bookmark name, falling back to the change description or `(no description set)`. Clicking opens a speed-searchable popup listing local bookmarks at the top, then up to 10 recent changes; bookmarks and changes are rendered with the same icons, colors, and styled IDs as the "Compare with Another Commit…" revision selector. Selecting an item shows a confirmation dialog to choose **Edit** or **New on Top** (`jj edit` / `jj new`); immutable targets offer only New on Top. The widget auto-refreshes reactively via the state model and hides for non-JJ projects.
- Log table now correctly shows pending-deletion bookmarks: entries carrying a remote bookmark (e.g. `main@origin`) whose local counterpart has been deleted now display the deleted local (`main` with strikethrough) at that commit, and the remote no longer shows a spurious large ahead count.
- Pending-deletion bookmarks (bookmarks deleted locally but still present on the remote) now appear in the "Specific bookmark" dropdown in the push dialog, shown with strikethrough and a "(deleted)" label. The confirmation dialog before a push now also warns when pending deletions will be included.
- Bookmark decorations now show pending deletions (strikethrough), local/remote divergence (↑n ↓n), and bookmark conflicts (conflict icon) in the log gutter, graph column decorations, and commit details panel.
- "Forget" context menu action on local bookmarks: removes a bookmark locally without creating a pending remote deletion. A confirmation dialog explains that the bookmark will reappear on the next fetch.
- "Move Bookmark Here…" opens a searchable dialog that classifies bookmarks into Forward and Backward/Sideways sections with directional icons and full-width section dividers. Forward bookmarks can be selected immediately; backward or sideways moves are shown at 50% opacity and require checking "Allow backward or sideways move" to enable selection. If the repository state changes between opening the dialog and confirming, a fallback prompt handles the race condition.
- Bookmark chips in the log table (Decorations column and inlined graph/description column) are now interactive: hovering shows a hand cursor, left-clicking navigates the log to the change that bookmark points at, and right-clicking opens a per-bookmark context menu with Rename, Delete, Forget, Track/Untrack, and Move To… actions.
- Bookmark chips in the commit details panel are now interactive with the same left-click navigation and right-click context menu.
- "Move \<bookmark\> To…" context menu action: opens a searchable change-picker dialog showing candidate destinations classified as Forward (descendants) or Backward (ancestors), with an "Allow backward move" checkbox to gate ancestor selection.

## [0.7.2] - 2026-05-24

### Added
- "Show Diff in New Tab" context menu action on file-change trees: opens a fresh diff editor tab each time, allowing multiple diffs to be open simultaneously alongside the reusable preview tab.
- Diff preview editor tabs now show context in their title: working copy diffs show `@: filename`, log-detail diffs show `<short-change-id>: filename`, and multi-selection shows the count (e.g. `3 changes: filename`).

### Fixed
- Double-clicking a file in the working copy or log details file-change tree now opens a diff preview editor tab. Subsequent single-clicks on other files swap the diff content in the same tab rather than opening new tabs. Escape closes the preview. Cmd/Ctrl+D routes through the same preview tab when invoked from these panels.
- Single-clicking a file in the working copy panel no longer opens a new editor tab on every click.

## [0.7.1] - 2026-05-23

### Added
- "Squash Into Here from..." action in the log context menu: right-click on a destination change to pick one or more source changes to fold into it via `jj squash --from ... --into ...`. This is the inverse of "Squash from Here into..." — destination is pre-selected, sources are chosen. The working copy is pre-selected as a source when it is mutable. Supports multi-source selection, partial file selection, description combining, and the delete/move working-copy option.

### Changed
- Squash dialogs no longer automatically move the working copy to the destination after a squash. A new "Delete empty source change and move working copy to destination" checkbox gives explicit control over this behaviour; the last-used state is persisted per project and defaults to unchecked (matching vanilla `jj squash` semantics).

### Fixed
- "Squash Into…" destination picker now includes descendants of the source change. Previously descendants were incorrectly excluded (using rebase cycle-prevention logic that does not apply to squash).
- Squash dialogs no longer send `--message` when jj would handle the description naturally. Descriptions are now only combined and sent when both the source and destination have non-empty descriptions and the squash is full (source will be abandoned). For partial squashes the destination description is left unchanged.
- Log column widths are now correctly persisted across sessions. Previously, programmatic width assignments during panel initialization were triggering the save listener and overwriting user-adjusted widths with defaults before they could be restored.
- "Squash into Parent…" is now enabled for merge commits. The dialog shows a destination picker restricted to the source's mutable parents; the user picks which parent to squash into. Previously the action was silently disabled for any commit with more than one parent.

## [0.7.0] - 2026-05-20

### Added
- Files matching `.gitignore` patterns (e.g. `build/`, `.gradle/`) are now grayed out in the Project tool window and listed in the "Ignored Files" node of Local Changes, matching the behavior of the Git plugin. Ignore rules are re-evaluated automatically when `.gitignore` or `.git/info/exclude` changes.
- "Squash from Here into..." action in the log context menu: squash one or more selected mutable changes into an arbitrary destination change via `jj squash --from ... --into ...`. Supports multi-source selection, file-level selection, description editing (auto-populated from destination + sources), and a keep-emptied option. The destination picker searches by change ID, description, or bookmark name and excludes invalid targets.
- Fetch dialog with repository and remote selection: when multiple repos or remotes are configured, Fetch now opens a dialog to choose which repository (or all) and which remote (default, specific, or all remotes) to fetch from

## [0.6.12] - 2026-05-19

### Added
- "Open Local File" action in the log detail panel and editor context menu: opens the working copy version of a file when viewing a historical revision

### Fixed
- Historical file editor tabs no longer lose their change ID suffix when the local version of the same file is opened
- Ctrl+D from the project tree now correctly shows parent vs working copy diff instead of two identical sides

### Changed
- Revision selector allows for search by commit id

## [0.6.10] - 2026-05-17

### Added
- Pressing Cmd+K in a Jujutsu project now opens the Describe Working Copy dialog instead of IntelliJ's commit dialog

### Fixed
- Changes/Commit tool window no longer disappears in mixed Jujutsu+Git projects
- Cmd+K (commit shortcut) is disabled in Jujutsu-only projects where it previously silently discarded the commit message; in mixed Jujutsu+Git projects, Jujutsu changes now show a clear error instead of being silently dropped
- Push dialog "Tracking bookmarks (default)" option now works correctly when new bookmarks need to be created on the remote — after the user confirms, the push proceeds with `--allow-new`
- Fetch action no longer throws an EDT threading error when clicked
- Show History now brings the VCS tool window to the front

## [0.6.9] - 2026-05-16

### Added
- Conflict resolution: conflicted files detected via `jj resolve --list` appear in red (MERGED_WITH_CONFLICTS) in the working copy panel
- Three-way merge dialog opens on conflicted files showing ours / base / theirs; supports bulk Accept Yours / Accept Theirs
- "Resolve Conflicts…" context menu action scoped to the selected files in the working copy panel, or the focused file in the editor; only visible when the selection contains conflicted files
- Conflicted files now appear highlighted in the log details pane when a conflicted commit is selected
- "Resolve Conflicts…" in the log row context menu for the working copy entry when conflicts are present
- "Resolve Conflicts…" in the log details pane changes tree for working copy entries

### Fixed
- Conflict marker parsing handles all three jj formats — snapshot (`+++++++`), diff (`%%%%%%%`), and git (`|||||||`/`=======`) — including the git format introduced as the default in jj 0.37

## [0.6.9] - 2026-05-14

### Fixed
- "Open in Remote" now appears in the working copy panel context menu and resolves to the nearest pushed ancestor
- "Open in Remote" for historical file editors now correctly resolves unpushed commits to their nearest pushed ancestor instead of using the commit hash directly (which would produce a broken URL)

## [0.6.8] - 2026-05-13

### Fixed
- "Open File in Remote" no longer causes IDE errors when hovering the context menu in large repos (eliminated synchronous jj subprocess call from action update)
- Diff on a directory opens diff views on all changed files under that directory
- Cmd+D (Show Diff) now works in working copy tool window regardless of which component has focus
- Compare with Local, Compare Before with Local, and Restore to This now appear and work in historical file editors
- Compare with Another Commit now appears in the changes tree context menu (details panel and working copy panel)
- Restore to This registered in editor Jujutsu menu
- Restore (working copy) correctly hidden in historical file editor context
- Jujutsu editor submenu grouped with separators matching the commit details panel layout

## [0.6.7] - 2026-05-12

### Added
- Jujutsu context menu available when viewing historical file versions
- Show diff for historical change
- Compare historical change with another commit
- Custom history provider replaces built-in VCS history tabs

### Fixed
- Diffs, change lists, and annotation gutters now show the correct base content for merge commits, using the auto-merged parent tree instead of the first parent's content
- Diffs work for files that have been renamed
- Open in Remote finds last pushed revision
- Restore Selection action no longer evaluates repository resolution unnecessarily when hidden in historical context

### Changed
- "Restore to This" now supports multiple file selections
- Removed spurious opening of files from working copy window when selecting a file
- Consolidated "Open File" and "Open Repository Version"
- Split and squash actions consolidated for working copy and historical revisions
- Open in Remote works for multiple files

## [0.6.6] - 2026-04-19

### Added
- Annotation gutter now shows the change ID column by default, coloured in the plugin's change ID blue
- Annotation tooltips are now formatted HTML: change ID, commit ID, author · date, and description — matching the log row tooltip style
- Log row tooltip now includes author · date above the description
- Commit details panel now shows author · date using absolute timestamps; committer date also shown when different from author

### Fixed
- ENTER key no longer hijacks global IDE navigation (e.g. Find Usages); the Jujutsu changes tree handles ENTER locally
- Clicking an annotation gutter column now correctly navigates the log to that change
- Annotation "View Colors" now works — lines from the same change share a background colour, and "Color by Author" groups by author
- Annotation "View Names" / "Email" modes now work correctly — name shortening (initials, first/last name, email) is applied when toggled
- Annotation no longer shows a "line count mismatch" warning when the working copy has local changes; the annotation now targets `@-` (the parent), matching IntelliJ's line status tracker base
- "Annotate Previous Revision" now loads file content correctly, including for files opened in historical annotation tabs

### Changed
- Repository icon colours are now consistent with IDE folders

## [0.6.5] - 2026-04-18

### Fixed
- Describe dialog no longer shows a literal `{0}` placeholder in the prompt; it now correctly shows the change ID being described
- Deleting a remote bookmark no longer shows an error dialog with a missing resource string. Remote bookmarks (e.g. `main@origin`) no longer offer Delete or Rename actions; only Track/Untrack is offered for remote bookmarks
- Diffs from merge working copies no longer fail. Previously, comparing against `@-` was ambiguous when the working copy had multiple parents, causing "cannot load diff" errors or showing files as entirely new. Now resolves to the first parent's change id from cached state

### Changed
- Marketplace description expanded to surface additional shipped features: compare with branch/revision, restore files, configurable revsets with Test button, per-repository setting overrides, push safety (non-fast-forward and sideways move warnings), multi-select in log, rebase post-rebase preview, split's dual-tree UI, Show History for Selection, and colocate option in clone/init
- Roadmap trimmed: "Open in Remote" removed (shipped in 0.6.2/0.6.3); "Squash & Split from File Selection" narrowed to remaining destination-picker + hunk work; "Remote Bookmark Management" narrowed to branches panel and bookmark toolbar widget

## [0.6.4] - 2026-04-16

### Added
- Push now warns before performing sideways (non-fast-forward) bookmark moves. A confirmation dialog lists the affected bookmarks and requires explicit approval before force-pushing

### Fixed
- Plugin no longer crashes on startup when the system PATH contains entries with invalid characters (e.g. `Dev:\sdks\flutter\`); invalid entries are now skipped with a warning logged

## [0.6.3] - 2026-04-15

### Added
- "Open in Remote" now works in editors opened via "Open Repository Version": the editor correctly resolves its repository and opens the file at the pinned historical commit rather than the latest pushed ancestor
- "Open in Remote" now appears in diff viewer right-click menus (Show Diff, Compare with Local, Compare Before with Local). Both the local and historical sides are supported: the historical side opens the file at the exact commit shown, while the local side opens at the latest pushed ancestor
- "Open in Remote" now includes the current line or selection as a URL fragment (`#L42`, `#L42-L50`). When opening a working-copy file, local line numbers are mapped to the corresponding remote lines using a diff of the local and pushed content

### Fixed
- Modified and added files now appear correctly in Local Changes and Working Copy. Previously, a spurious VFS cache check caused them to be silently dropped in large projects (or any project where IntelliJ's VFS scan was still in progress when changes were first detected), leaving only deleted files visible (fixes #19)

## [0.6.2] - 2026-04-15

### Added
- "Open in GitHub" / "Open in GitLab" action in the commit log context menu and file history toolbar. Supports github.com and gitlab.com remotes (SSH and HTTPS). Shows a submenu when multiple recognized remotes are configured. File history additionally offers "Open File" to navigate to the specific file at that revision. Tooltip warns when the commit has not been detected on the remote.

### Fixed
- Annotation and diff providers no longer race with startup: the repository cache is guaranteed warm before IntelliJ activates VCS
- Describe button now correctly enables when typing into an empty (undescribed) working copy
- Descriptions no longer accumulate a trailing blank line on each describe: jj's storage newline is stripped on read, and intentional trailing blank lines are preserved
- Uninitialised Jujutsu repository notification now navigates to the Directory Mappings settings page

## [0.6.1] - 2026-04-10

### Added
- Multi-select in log: changes tree now shows the union of all selected commits' changes (chained by file path, oldest-to-newest)
- Multi-select in log: metadata pane shows each selected commit's details stacked with separators (capped at 20, with overflow label)
- Change ID in commit details panel is now a clickable link that selects that single commit in the log

### Removed
- "UI Preferences" settings group containing three non-functional checkboxes (auto-refresh, short change ID format, auto-open log tab) — these settings were stored but never read

## [0.6.0] - 2026-04-09

### Added
- Push from a specific log entry now scopes the operation to that revision: bookmark list is filtered to ancestors of the selected change, and `jj git push -r <revision>` is used for the default push scope

### Fixed
- Push dialog now includes a repository selector when multiple repositories are open, pre-selected from file context
- "Specific bookmark" dropdown in the push dialog is now disabled unless that radio button is selected
- Pushing a new (untracked) local bookmark no longer fails with "Refusing to create new remote bookmark" — `--allow-new` is passed automatically
- Push and fetch now show a clear warning notification instead of failing cryptically when no Git remote is configured

## [0.5.21] - 2026-04-08

### Fixed
- Spurious WARN log entries for `jj config get` (key not found is normal) and `jj file annotate` (caller already logs failures)
- Annotating an empty file no longer shows an error dialog
- Fixed repository detection for single-repository projects (regression introduced in 0.5.20)
- "Configure VCS roots..." link now navigates correctly to VCS directory mappings settings (previously opened last-visited settings page due to wrong configurable ID) (GitHub #20)

## [0.5.20] - 2026-04-08

### Added
- Test button for revset expressions in Log Settings — validates against each repo and shows change count
- Per-repo Test button for revset overrides in Repository Settings
- Revset validation on apply — prevents saving invalid expressions

### Fixed
- Root filter now appears in log toolbar for multi-repo projects (GitHub #10)
- Repository names in multi-repo projects now use simple directory names if they are unambiguous (Github #10 part 3)

## [0.5.18] - 2026-04-05

### Added
- User Identity section in Settings → Jujutsu for editing global `user.name` / `user.email`
- Repository Settings section in Settings → Jujutsu with one collapsible group per repo — all repos visible and configurable simultaneously, no switching required
- Startup now checks user config per-repo using each repo's own executor, so repo-scoped config is recognised
- User config notification now links directly to Settings → Jujutsu for configuration
- Repository Settings identity section auto-detects existing repo-scoped overrides (via `jj config get --repo`)

### Fixed
- `jj config get` now runs in repo context, picking up repo-scoped config (GitHub #9)
- Changed jj executable path in settings now takes effect immediately without IDE restart
- Upgrading jj binary in-place (same path) now detected when clicking Test button, refreshing working copy and log automatically

## [0.5.16] - 2026-04-03

### Changed
- JJ executable path is now a global (application-level) setting, shared across all projects. Existing custom paths are automatically migrated.
- Settings architecture uses three tiers: global (app), project, and per-repository overrides
- Working copy and log panels show "Checking jj..." instead of "jj Not Found" during startup availability check

### Fixed
- Describe working copy no longer throws write-only access error (Github #8)

## [0.5.15] - 2026-04-02

### Added
- Truncation indicator in log panel when the entry count hits the limit, with a clickable link to open settings
- Log automatically reloads when the limit setting is changed

### Fixed
- Support custom jj builds with version strings like "jj companyname-0.39.0-..." (GitHub #7)
- Conflicted bookmarks no longer break lists of all bookmarks

### Changed
- Default log limit increased from 50 to 500, with automatic migration for existing users who never changed the default

## [0.5.14] - 2026-04-02

## [0.5.13] - 2026-04-02

### Added
- User feedback after successful push — now shows a notification with the push output
- Can now push untracked bookmarks

### Fixed
- Respect remote and bookmark selections when pushing to remote

## [0.5.12] - 2026-04-01

## [0.5.11] - 2026-03-31

### Added
- Roadmap of upcoming features
- Squash/split from file context menus: right-click files in working copy panel, commit details, project view, or editor to squash into parent or split into a new change with those files pre-selected
- Track/untrack remote bookmarks: right-click a remote bookmark in the log context menu to track or untrack it
- Visual distinction between tracked (filled) and untracked (outline) bookmark icons in the log and push dialog

### Fixed
- UI freeze when VCS operations triggered from modal dialogs (e.g., push dialog) by propagating correct modality state to async callbacks
- Project view and editors now refresh after VCS operations (edit, new, abandon, rebase, squash, split, fetch) that change working copy files on disk

### Changed
- Resized mutable/immutable icons in the log to be less prominent

## [0.5.10] - 2026-03-30

### Added
- Git clone with Jujutsu: Clone Git repositories directly from File > New > Project from Version Control using `jj git clone`
- Initial jj setup: Prompt to configure user.name and user.email when jj is detected but not configured, pre-populating from git global config

## [0.5.9] - 2026-03-29

### Added
- jj installation detection: Plugin now detects jj in PATH and common installation locations (Homebrew, Cargo, Scoop, Chocolatey, Winget, Snap, APT)
- jj availability notifications: Shows helpful guidance when jj is not found, version is too old (requires 0.37.0+), or configured path is invalid
- Installation guidance: Notifications include one-click actions to copy install/upgrade commands or open documentation

## [0.5.8] - 2026-03-27

### Fixed
- Settings changes (jj executable path) now take effect immediately without IDE restart ([#4](https://github.com/kkkev/jj-idea/issues/4))

## [0.5.7] - 2026-03-24

### Changed
- Made accented icon to represent a repository

## [0.5.6] - 2026-03-24

### Fixed
- Fix bookmark icons inconsistent in dark mode (wrong size and fill)

### Added
- Capability to split commits
- Icons for describe, new change, rebase, split, and squash actions
- Icons in HTML panels now scale correctly inside `smaller` blocks

## [0.5.5] - 2026-03-22

### Fixed
- Fix squash into parent not selecting parent change in log after operation
- Fix HTML icon sizing and alignment at different zoom levels

## [0.5.4] - 2026-03-18

### Fixed
- Show history for selection: fix AssertionError caused by missing block history provider
- Remove deprecated/252-only API usages (ActionUtil.performAction, Disposer.isDisposed)
- Fix occasional error caused by catching ProcessCanceledException in change provider

### Changed
- Minimum supported version lowered back to IntelliJ IDEA 2025.1

## [0.5.3] - 2026-03-18

### Added
- Clicking on change ids in working copy and description panels selects the change in the log

### Fixed
- Remove remaining internal API usages flagged by Plugin Verifier (ActionToolbarImpl, VcsUserImpl)
- Performance: Remove double refreshing of the log per VCS operation
- Performance: Remove wasted initial load of repository state
- Bookmark list: Filter deleted bookmarks that were previously throwing errors
- Move bookmark: Remove remote bookmarks from the list of candidate bookmarks to move
- Move bookmark: Fix popup title

## [0.5.2] - 2026-03-10

### Fixed
- Fix icon colorization compatibility break across platform versions
- Replace deprecated and internal API usages flagged by Plugin Verifier

### Changed
- Minimum supported version is now IntelliJ IDEA 2025.2

## [0.5.1] - 2026-03-09

### Fixed
- Replace deprecated `preload="true"` service attribute with `ProjectActivity` to pass JetBrains Marketplace validation

## [0.5.0] - 2026-03-09

### Added
- Apache 2.0 license
- Updated README with screenshots and current feature documentation

### Fixed
- Save all open editor files before executing JJ commands to prevent race conditions with unsaved changes
- Log now refreshes after abandoning a non-working-copy change, and selects the abandoned change's parent instead of jumping to the working copy

## [0.4.2] - 2026-03-08

## [0.4.1] - 2026-03-08

### Added
- Hide `.jj` directory from Project tool window (added to IDE ignored files list on startup)

### Fixed
- Descriptions starting with hyphens (e.g., markdown bullet points) no longer fail with "unexpected argument" error

## [0.4.0] - 2026-03-07

### Added
- Bookmark management: create, delete, rename, and move bookmarks from log context menu
- Move bookmark confirmation dialog when moving backwards or sideways (non-descendant change)

## [0.3.6] - 2026-03-02

## [0.3.5] - 2026-03-02

### Added
- Squash into Parent action: squash a change into its parent with file selection, description editing, and keep-emptied option
- Platform test infrastructure: `@Tag("platform")` tests run with full IntelliJ bootstrap via `./gradlew platformTest`

### Fixed
- Fix "Write-unsafe context" error when clicking files in working copy panel

## [0.3.4] - 2026-02-28

### Fixed
- Fix log table rows expanding on hover, causing graph+description to overpaint adjacent columns
- Performance: sped up log loading for large repositories

### Added
- Git remote operations: Fetch and Push actions accessible from log toolbar, context menu, and VCS menu
- Push dialog with remote selector, bookmark scope options (tracking/specific/all)
- Progress bar feedback for network operations (fetch/push)

## [0.3.3] - 2026-02-26

### Fixed
- Fix IDE freeze caused by duplicate background loading tasks and stuck loading state on cancellation
- Fix annotation click "Searching commit in the log" never completing — now selects in custom log
- Fix `SwingUtilities.invokeLater` misuse causing Graphics2D paint corruption (must use `ApplicationManager.invokeLater` in plugins)
- Fix re-entrant data loads by keeping `loading` flag set during EDT callback in `BackgroundDataLoader`
- Remove redundant `updateRootFilterVisibility()` call with stale data from state listener
- Reduce startup CLI overhead by passing limit revsets to standard VCS log provider
- Fix disposal-safety in state model catch-up handler (prevent stale callbacks on project close/reopen)
- Remove unnecessary `invokeLater` from data loader callbacks to prevent stale table updates
- Format change ids correctly in the log

### Changed
- Reduce vertical whitespace in details panel 

## [0.3.1] - 2026-02-24

### Fixed
- Fix crash on IntelliJ 2025.1 caused by `IconUtil.colorize` signature change in 2025.2

## [0.3.0] - 2026-02-24

### Added
- Rebase dialog with full `jj rebase` support: source modes (-r/-s/-b), destination modes (-d/-A/-B), multi-select source and destination
- Rebase dialog: log-style destination picker with commit graph, bookmarks as decorations, and search by change ID, description, or bookmark name
- Rebase dialog: simulated post-rebase graph preview showing reparented commits with source/destination highlighting
- Rebase dialog: split layout with destination picker on the left and live preview on the right
- Rebase dialog: invalid destinations (self-rebase, cycle-creating targets) are automatically filtered based on source mode

### Fixed
- Fix: log window text filter
- Fix: renames and moves are now tracked properly

## [0.2.5] - 2026-02-15

### Fixed
- Performance: simplified UI updates from file changes

## [0.2.4] - 2026-02-12

### Added
- Custom file history panel with same styling as the log view (replaces standard Show History action)
- Diagnostic logging for state model, change provider, and CLI executor to aid freeze investigation

### Changed
- Declared `.jj` as administrative area in plugin.xml (hides from project view, consistent with Git plugin)

### Fixed
- Performance: most UI action classes now use ActionUpdateThread.BGT to avoid EDT contention during toolbar updates
- Performance: BulkFileListener now filters out `.jj` internal directory changes to prevent unnecessary refresh cycles
- Performance: repository state equality excludes volatile timestamps to prevent spurious invalidations
- Fix: log panel now refreshes after VCS operations (abandon, edit, new) even when working copy unchanged
- Fix: only mark repos dirty when their state actually changes, reducing FileStatusManager event flooding
- Fix: register IgnoredFileProvider for `.jj/` directories to prevent change list processing of internal files
- Fix: downgrade diff summary failure from SEVERE to WARN (expected when viewing abandoned commits)

## [0.2.3] - 2026-02-11

### Fixed
- Graph rendering: fix phantom passthrough lanes where fork+merge connections drew disconnected vertical lines
- Graph rendering: correct lane assignments and passthrough lines for merge commits with intervening side branches
- Keyboard shortcut error: use `meta` instead of `command` for macOS keyboard shortcuts

## [0.2.0] - 2025-02-09

First release under structured release management. This release consolidates
numerous improvements made during the 0.1.x development cycle.

### Added
- Multi-root repository support with unified log window and gutter column
- Change action menu system for context menu operations
- Multi-select files in diff view
- Restore actions for files and revisions
- Platform Annotate action integration for keyboard shortcut support
- Test coverage for custom log tab feature parity
- `jj init` action for initializing repositories
- Release management: CHANGELOG.md, CI changelog check, release script
- CI enforces changelog updates for source changes (skip with `[skip changelog]`)

### Fixed
- Log ordering: use topological sort instead of timestamp sort
- Bookmark names now include remote suffix
- EDT freeze: moved graph building and entry merging off UI thread
- Empty author filter selection no longer causes errors
- VirtualFile.jujutsuProject properly matches files under JJ roots
- Empty marker updates when editing a change
- Auto-select working copy in log after VCS operations
- Handle removed commits gracefully in detail pane
- Hide repo selector dropdown for single-root projects
- Disposal error when opening native log pane in mixed VCS project
- VCS log cache assertion error
- Notification shown when JJ VCS root is not initialized

### Changed
- Removed custom VCS log columns in favor of inline graph rendering
- Improved multi-root support throughout the plugin
- Refactored log tab management
- Change hashes from change IDs to commit IDs for platform compatibility

[Unreleased]: https://github.com/kkkev/jj-idea/compare/v0.7.15...HEAD
[0.7.15]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.15
[0.7.14]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.14
[0.7.13]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.13
[0.7.12]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.12
[0.7.11]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.11
[0.7.10]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.10
[0.7.9]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.9
[0.7.8]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.8
[0.7.7]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.7
[0.7.6]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.6
[0.7.5]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.5
[0.7.4]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.4
[0.7.3]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.3
[0.7.2]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.2
[0.7.1]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.1
[0.7.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.7.0
[0.6.12]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.12
[0.6.11]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.11
[0.6.10]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.10
[0.6.9]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.9
[0.6.8]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.8
[0.6.7]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.7
[0.6.6]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.6
[0.6.5]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.5
[0.6.4]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.4
[0.6.3]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.3
[0.6.2]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.2
[0.6.1]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.1
[0.6.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.6.0
[0.5.21]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.21
[0.5.20]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.20
[0.5.19]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.19
[0.5.18]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.18
[0.5.17]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.17
[0.5.16]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.16
[0.5.15]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.15
[0.5.14]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.14
[0.5.13]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.13
[0.5.12]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.12
[0.5.11]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.11
[0.5.10]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.10
[0.5.9]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.9
[0.5.8]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.8
[0.5.7]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.7
[0.5.6]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.6
[0.5.5]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.5
[0.5.4]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.4
[0.5.3]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.3
[0.5.2]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.2
[0.5.1]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.1
[0.5.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.0
[0.4.2]: https://github.com/kkkev/jj-idea/releases/tag/v0.4.2
[0.4.1]: https://github.com/kkkev/jj-idea/releases/tag/v0.4.1
[0.4.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.4.0
[0.3.6]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.6
[0.3.5]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.5
[0.3.4]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.4
[0.3.3]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.3
[0.3.1]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.1
[0.3.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.0
[0.2.5]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.5
[0.2.4]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.4
[0.2.3]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.3
[0.2.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.0
