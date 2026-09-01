# Manual Test Checklist

This document provides a comprehensive manual test checklist for the Jujutsu IDE plugin. These
tests require GUI interaction and should be verified manually using `./gradlew runIde`.

Use this checklist:
- Before releases, as a full regression pass
- When verifying feature parity with standard VCS logs
- When onboarding new contributors to understand expected behavior
- **After a change, to re-run only the affected sections** — see [How to scope a run](#how-to-scope-a-run)

## How to Run Manual Tests

1. Start the IDE with the plugin: `./gradlew runIde`
2. Open a project with a Jujutsu repository (or create one with `jj git init`)
3. For a full pass, work through every section below. For a change, scope to the affected
   sections first (see below) — the full list is too large to run for every PR.

## How to scope a run

Each section's **Code:** line is the blast-radius map. To scope a run: list the files your
change touched → `grep -n 'Code:' docs/manual-tests.md` for each → check
[Shared Surfaces](#shared-surfaces) and each match's **Also re-run:** line → run the union.
See contributing.md § "Manual regression scope as a deliverable" for how this feeds a PR report.

## Shared Surfaces

Components whose blast radius exceeds their own package:

| Component | Feeds |
|---|---|
| `ui/components/TextCanvas.kt`, `LogEntryText.kt`, `HtmlTextCanvas.kt`, `UnbreakableContent.kt`, `AtomicHtmlView.kt`, `HtmlIcons.kt`, `Linkifier.kt` | [MT-LOG-DETAILS](#mt-log-details), [MT-WORKINGCOPY](#mt-workingcopy), [MT-BOOKMARK](#mt-bookmark) |
| Renderer trio: `ui/log/JujutsuLogTableRenderers.kt`, `ui/log/JujutsuGraphAndDescriptionRenderer.kt`, `ui/log/LaidOutCell.kt`, `ui/log/LogClickTarget.kt`, `LogEntryText.kt.appendSummaryAndStatuses` | [MT-LOG-TABLE](#mt-log-table), [MT-LOG-GRAPH](#mt-log-graph), [MT-LOG-DETAILS](#mt-log-details) |
| `ui/components/RevisionSelectorPopup.kt` | [MT-CTXMENU](#mt-ctxmenu), [MT-DIFF](#mt-diff) |
| Shared commit picker (used by Rebase, Squash Into…, Duplicate Onto…, Move Bookmark to Change) | [MT-CTXMENU](#mt-ctxmenu), [MT-SQUASH](#mt-squash), [MT-SPLIT](#mt-split) |
| `ui/components/IconAwareTooltip.kt` (icon-aware tooltip installer, incl. `installIconAwareTableTooltip`) and `ui/log/LogPreviewTable.kt` (shared picker/preview table setup) | [MT-LOG-TABLE](#mt-log-table), [MT-CTXMENU](#mt-ctxmenu), [MT-SQUASH](#mt-squash) |
| Diff preview-tab helper (`ui/common/JujutsuEditorTabDiffPreview.kt`) | [MT-DIFF-PREVIEW](#mt-diff-preview), and its three referrers |
| In-dialog file diff preview shell (`ui/common/FileDiffPreviewPanel.kt`) | [MT-SPLIT](#mt-split), [MT-SQUASH](#mt-squash) |
| Shared hunk-pick preview cache/renderer (`ui/common/HunkPickPreviewController.kt`, `ui/common/HunkSelectionModel.kt`) and the diff-editor staging protocol (`diffedit/DiffEditTool.kt`, `diffedit/HunkApplyMain.kt`) | [MT-SPLIT](#mt-split), [MT-SQUASH](#mt-squash) |
| Multi-repo scoping (root-aware actions/filters generally) | [MT-CROSS](#mt-cross), plus every section with a repo-scoped action |
| `actions/filechange/FileChangeActionGroup.kt` (file-change right-click menu, via `JujutsuChangesTree.installHandlers()`) | [MT-LOG-DETAILS](#mt-log-details), [MT-WORKINGCOPY](#mt-workingcopy), [MT-CTXMENU](#mt-ctxmenu) (`JujutsuCompareChangesPanel`) |
| `diffedit/HunkArrowDiffExtension.kt` (plugin-wide `diff.DiffExtension` — fires on every diff viewer the platform creates, gated to a no-op elsewhere) | [MT-SPLIT](#mt-split), [MT-SQUASH](#mt-squash), [MT-DIFF](#mt-diff), [MT-DIFF-PREVIEW](#mt-diff-preview) |
| `vcs/diffbase/DiffbaseService.kt` (shared base-revision resolver) | [MT-DIFFBASE](#mt-diffbase), [MT-DIFF](#mt-diff) (Annotate), [MT-WORKINGCOPY](#mt-workingcopy) (gutter markers) |

## Fixtures

Shared setup, referenced by ID instead of repeated inline. Each fixture is a runnable script
under `scripts/fixtures/` rather than an embedded snippet — run it, then open the printed
directory as a project in the plugin IDE (`./gradlew runIde`).

| ID | Script | Creates |
|---|---|---|
| FX-STACK | `scripts/fixtures/fx-stack.sh [target-dir]` | Linear stack base → A → B → C (@ = C), for squash testing |
| FX-CONFLICT | `scripts/fixtures/fx-conflict.sh [target-dir] [marker-style]` | A content conflict on `file.txt` (change A rebased onto change B, working copy on the conflict); `marker-style` is `git` (default), `snapshot`, or `diff` — rerun with a different style against the same repo to test all three |
| FX-MD-CONFLICT | `scripts/fixtures/fx-md-conflict.sh [target-dir]` | A modify/delete conflict on `a.txt` — one side deletes the file entirely, so "accept" on that side must remove it from disk, not leave an empty file; a different case from FX-CONFLICT's content conflict |
| FX-STRESS | `scripts/fixtures/fx-stress.sh [target-dir]` | A ~1084-commit repo with ~26 concurrent heads (main trunk, 20 short feature branches, 5 long branches, a 200-commit deep-branch, an octopus-merge, a hotfix/* cluster), for log-graph/filter stress testing. Reused as `jj-stress-test` by MT-LOG-GRAPH's stress bullet and its "Graph layout under filtering" subsection, and by MT-LOG-FILTER's Reference filter fixture — don't build a separate throwaway multi-branch repo for those, this one already has far more lanes than any of them need |

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
- **jj-idea-ddcd** — native Commit tool window's "Resolve" link discards a side on cancel; use jj-idea's own "Resolve Conflicts…" entries instead, never this one.

## Test Sections

### MT-LOG-TABLE

**Log table selection, navigation, and row interaction**

**Code:** `ui/log/JujutsuLogTable.kt`, `ui/log/UnifiedJujutsuLogPanel.kt`, `ui/log/JujutsuColumnManager.kt`, `ui/log/JujutsuLogContextMenuActions.kt`, `ui/log/LogClickTarget.kt`, `ui/components/TextCanvas.kt`
**Also re-run:** MT-LOG-DETAILS (issue-tracker link rendering is shared with the details panel)

#### Selection & navigation

- [ ] Basic selection and navigation all work: single-click select, Shift+click range,
      Ctrl/Cmd+click non-contiguous, Up/Down arrows, Page Up/Down, Home/End
- [ ] Selection persists after filtering (if entry still visible): select a commit, then clear or
      change each of the reference/bookmark, author, date, text, and root filters in turn while the
      commit stays visible throughout — the selection (and the details panel content) never
      flickers to empty and stays on the same commit each time (jj-idea-yje9)
- [ ] Selection clears when filtered entry is hidden: select a commit, then apply a filter that
      excludes it — the selection clears and the details panel shows its empty state (jj-idea-yje9)
- [ ] On a narrow window with horizontal scroll active (scrolled right so later columns are
      visible), selecting a commit (click, arrow keys, ref-chip click, a details-panel parent
      link click, or a log refresh) does not reset horizontal scroll position (jj-idea-f27g)
- [ ] Clicking a parent/change-id link in the commit details panel (or working copy panel)
      selects that commit in the log and it stays selected (no flicker/deselect); if the
      linked commit is beyond the log limit, the log expands to include it (jj-idea-f27g)
- [ ] Select a change that's outside the log window (so expansion is needed), then in a terminal
      `jj abandon` it (or squash it into its parent) before the log refreshes — refresh the log
      (and refresh again): no "Uncaught exception" error dialog appears, the selection is simply
      dropped, and selecting another off-window change still expands normally afterwards
      (GitHub #76)
- [ ] Ctrl/Cmd+C on a selected row copies the change ID (or file path, when a file is selected)
- [ ] Delete on a selected change abandons it, with a confirmation dialog first
- [ ] F2 on a selected change renames/describes it (opens the describe dialog)

#### Double-click / Enter on a log row (jj-idea-th9h)

- [ ] Select a commit row and press Enter → that commit's diff opens (Show Diff)
- [ ] Double-click a commit row (not on a bookmark/tag chip or the root gutter) → the same
      diff opens
- [ ] Double-click a bookmark or tag chip → does nothing (no diff opens, no filter change,
      jj-idea-wkcz — bookmark/tag chips have no left-click action, only a right-click menu)
- [ ] Double-click the "+N more" overflow chip → shows the hidden-refs popup (no diff opens)
- [ ] Double-click the root gutter column (multi-repo view) → toggles expansion (no diff opens)
- [ ] In Settings → Keymap, rebind "Show Diff" off Enter onto a different jj action (or clear
      it) → both Enter and double-click on a log row now follow the new binding

#### Hover tooltip behaviour (jj-idea-wp12)

**Code:** `ui/components/IconAwareTooltip.kt`

- [ ] In Settings → Version Control → Issue Navigation, add a pattern (as above) so a commit's
      row tooltip contains a clickable issue-tracker link; hover that row until the tooltip
      appears, then move the pointer up into the tooltip — it stays open (does not disappear)
- [ ] With the tooltip open, click the issue-tracker link inside it → opens in your browser;
      click the author's name (a `mailto:` link) → opens your mail client
- [ ] With the tooltip open, select some of its text with the mouse (click-drag) — the text
      highlights instead of the tooltip disappearing on the first move
- [ ] With the tooltip open, move the pointer sideways or away from it (not towards it) → it
      dismisses normally
- [ ] Hover a row so the tooltip appears, then scroll the log (mouse wheel, scrollbar drag, or
      Page Up/Down) without moving the pointer — the tooltip disappears immediately and does
      **not** reappear while the pointer stays still; move the pointer afterwards — the tooltip
      reappears with the *new* row under the pointer (not stale content from before the scroll)
- [ ] Move the pointer between adjacent rows without ever entering the tooltip — content still
      updates to match each row as before
- [ ] Move the pointer off the log table onto another tool window or editor — the tooltip
      dismisses
- [ ] jj-idea-lgo4: off-switch for this tooltip — see "View options menu" below

#### Column management

- [ ] Column visibility toggle, reordering (drag-and-drop), resizing (drag separator), and
      auto-fit (double-click separator) all work
- [ ] Column widths and visibility both persist across IDE restarts

#### View options menu (jj-idea-lgo4, n22a)

The toolbar's eye-icon button opens a single flat "View Options" popup (replacing the old,
separate "Columns" and "Details Position" submenus) with labeled section headers: **Columns**
(the per-column toggles, then a plain separator, then "Fit Columns to Window Width" - a layout
behavior rather than a column), **Details** (Right/Bottom), and an unlabeled trailing group with
**Alternating Row Colors** and **Commit Tooltips**.

- [ ] Toolbar shows one eye-icon **View Options** button (no separate Columns / Details Position
      buttons). Opening it shows "Columns" and "Details" section headers with the expected items
      grouped underneath, and "Alternating Row Colors" / "Commit Tooltips" at the bottom, both
      checked by default
- [ ] Uncheck **Commit Tooltips**, then hover a log row — no tooltip appears; re-check it —
      hovering again shows the tooltip, no restart needed. Repeat in a file-history tab and in
      the **Working copy** tool window (same table, same global setting) — toggling it in one
      table's menu updates all of them immediately
- [ ] Uncheck **Alternating Row Colors** — log window rows become uniform, no restart needed;
      open the Duplicate, Squash-into, and Rebase dialogs — their destination/source picker
      tables are also unstriped (they read the same global setting when opened). Re-check and
      confirm striping returns everywhere, including in tables/tabs opened while it was off
- [ ] Column visibility, Fit Columns to Window Width, and Details Right/Bottom toggles all still
      work exactly as before from this flattened menu

#### Responsive column sizing (jj-idea-lzq7)

- [ ] Open a fresh log tab (or one where you've never dragged a column). The View Options menu's
      "Fit Columns to Window Width" is checked by default. Drag the tool window narrow: the
      description column shrinks and no horizontal scrollbar appears until the window is very
      narrow; author/date visibly narrow (and ellipsize) before any scrollbar appears
- [ ] With that tab still narrow, dock the commit-details pane to the right (Details position):
      the table re-fits to the remaining width with no scrollbar; widen the window back out and
      the columns grow back
- [ ] In a tab with a column you dragged before this change shipped (or drag one now, then
      reopen the tab), "Fit Columns to Window Width" defaults to unchecked and the layout/
      scrollbar behavior is exactly as before
- [ ] Toggle "Fit Columns to Window Width" off and on in the View Options menu; behavior switches
      between responsive and manual immediately, and the choice persists across closing and
      reopening the tab
- [ ] With fit-to-width on, manually widen the author column via drag, then narrow the window:
      the chosen author width is remembered (the column returns to it on widen) and persists
      across reopening the tab

#### Issue-tracker links in the description column (jj-idea-91qf)

- [ ] In Settings → Version Control → Issue Navigation, add a pattern (e.g. issue regexp
      `[A-Z]+-\d+`, link `https://example.com/browse/$0`); Apply
- [ ] A commit whose description contains a matching reference (e.g. `Fixes JIRA-123`) shows it
      link-colored (not underlined at rest) in the log table's description column
- [ ] Hovering just the reference shows a hand cursor and underlines only that word, not the rest
      of the description; hovering elsewhere in the description shows the default cursor
- [ ] Left-clicking the reference opens the URL in your default browser
- [ ] Right-clicking the reference shows a popup with a single "Open <url>" action that does the same
- [ ] The row tooltip's description also renders the reference as a link
- [ ] With no Issue Navigation patterns configured, the description column renders exactly as
      before (no link, no hover cue)
- [ ] Narrowing the column so the description truncates still linkifies a reference that appears
      before the truncation point; one that would appear after it is dropped along with the rest
      of the truncated text, not left half-rendered

#### Description word spacing and copy fidelity (jj-idea-myje / GitHub #77)

- [ ] Describe a change with a long, multi-word description (long enough to wrap in the
      description column and in the commit details panel/tooltip) — it wraps at word
      boundaries like ordinary text, not only at the column edge
- [ ] Select some of that description's text with the mouse (in the description column, the
      details panel, or the row tooltip), copy it, and paste into a plain text editor — the
      pasted text has ordinary spaces between words, matching what you typed (not literal
      non-breaking spaces, which look identical on screen but paste/diff/search differently)
- [ ] Right-click the row → Copy Description (or the equivalent toolbar/keyboard action) still
      copies the description exactly as typed

#### Issue-tracker links inside bookmark/tag chip names (jj-idea-vrmv)

- [ ] With an Issue Navigation pattern configured (as above), create/rename a bookmark to include a
      matching reference (e.g. `jira-123-fix-thing`, regexp `[A-Za-z]+-\d+`) — the reference renders
      link-colored within the chip; the rest of the chip (icon, remaining text) stays plain
- [ ] Hovering just the reference substring shows a hand cursor and underlines only it; hovering the
      rest of the same chip (icon or non-matching text) shows no hand cursor, matching plain
      bookmark/tag chip hover (jj-idea-wkcz)
- [ ] Left-clicking the reference substring opens the URL in your default browser; left-clicking
      elsewhere in the chip does nothing (still no whole-chip left-click action)
- [ ] Right-clicking the reference substring shows a popup with a single "Open <url>" action;
      right-clicking elsewhere in the same chip still shows the usual bookmark/tag actions menu
      (Rename…, Delete, Forget, etc.) — the two right-click behaviors are position-sensitive
- [ ] Repeat for a tag name containing a matching reference
- [ ] A bookmark/tag name with no matching reference renders unaffected
- [ ] With no Issue Navigation patterns configured, chip names render exactly as before

#### Row click actions: author/committer links and bookmark/tag chips (jj-idea-iesq, jj-idea-wkcz, jj-idea-a52h)

Log rows render bookmark/tag chips and author/committer names with link styling. Author/committer
names are real left-click hyperlinks (left-click performs the default action; right-click opens a
menu with that default action pre-highlighted). Bookmark/tag chips are **not** — they have no
left-click action; only the right-click menu reaches their actions (jj-idea-wkcz, a prerequisite
for letting issue-tracker references *inside* a bookmark/tag name become their own links, without
a link-inside-a-link). Hovering one instead shows a subtle grey background highlight
(jj-idea-a52h) — the same "hover" tint used for a hovered row elsewhere — signaling "right-click
here" without a hand cursor implying a left-click action that doesn't exist.

- [ ] Author/committer names are link-colored at rest, with **no underline**; hovering the name adds an underline, and moving off it removes the underline again (jj-idea-iesq: was permanently underlined before)
- [ ] Hovering blank cell space to the right of a short author/committer name does **not** add an underline (matches the existing "no click target" boundary)
- [ ] Hovering an author name (not blank space in the cell) shows a hand cursor
- [ ] **Left-clicking** an author name opens the OS mail client addressed to that author's email
- [ ] **Right-clicking** an author name opens a menu with **Send Email to ...** highlighted, a separator, then **Filter Log by ...**
- [ ] Choosing **Filter Log by ...** narrows the log to that author (the Author filter chip updates) and closes the menu; choosing it again while already active clears the filter and closes the menu
- [ ] **Filter Log by ...** shows a checkmark when that author is the currently active author filter, and no checkmark otherwise
- [ ] **Left-clicking** a committer name opens the OS mail client (same as author)
- [ ] **Right-clicking** a committer name shows only **Send Email to ...** — no filter option
- [ ] Clicking blank cell space to the right of a short author/committer name does **not** launch the mail client
- [ ] Hovering a bookmark or tag chip does **not** show a hand cursor, but does show a subtle grey background highlight (jj-idea-a52h) — its accent color (bookmark/tag color) stays visible on top of the highlight. Check across the whole width of the chip (left edge, middle, right edge), not just one spot
- [ ] The highlight covers only the hovered chip's own icon+label(+suffix) — not the space before/after it, and not a neighboring chip
- [ ] **Left-clicking** a bookmark/tag chip does nothing — no filter change, no navigation
- [ ] **Right-clicking** a bookmark/tag chip opens a menu with **Filter Log to '...'** highlighted at the top, followed by a separator and the existing rename/delete/forget/move/advance/track actions (see MT-BOOKMARK for Advance)
- [ ] Choosing **Filter Log to '...'** from the right-click menu applies the filter and closes the menu; choosing it again while already active clears the filter and closes the menu
- [ ] **Filter Log to '...'** shows a checkmark when that reference is the currently active filter, and no checkmark otherwise — reopen the menu after toggling to confirm the checkmark follows the filter state
- [ ] The "+N more" overflow chip shows both a hand cursor and the same grey background highlight on hover (jj-idea-ttmp), and **left-clicking** it still opens its popup of hidden refs, each still openable via their own submenu

#### Palette readability (jj-idea-mn1a)

- [ ] Switch the IDE to a **light** theme (e.g. IntelliJ Light). Bookmark chips, tag chips, the
      `@` working-copy marker, a conflicted change's marker, and a divergent change's marker are
      all comfortably readable against the row background — none reads as washed-out or
      near-invisible (was reported for bookmark gold, GitHub #51)
- [ ] Repeat with a row **selected** (chips keep their accent color per the hover-highlight item
      above; confirm it's still readable against the selection background too)
- [ ] Switch back to a **dark** theme (e.g. Darcula) and confirm nothing regressed there

### MT-LOG-GRAPH

**Graph rendering**

**Code:** `ui/log/JujutsuCommitGraph.kt`, `ui/log/JujutsuGraphAndDescriptionRenderer.kt`, `ui/log/graph/DataStructures.kt`, `ui/log/graph/LayoutCalculator.kt`

- [ ] Graph lines render correctly for linear history, merges, and branches; the working
      copy (@) indicator is visible; colors differentiate branches; the graph column
      auto-sizes to content
- [ ] On a commit with ~30 bookmarks (e.g. `for i in $(seq 1 30); do jj bookmark create
      bm-$i; done`), the log row still shows description text (not blank), and the
      bookmarks collapse behind a "+N more" chip rather than overflowing the cell
      (jj-idea-w61m); the chip still shows a colored bookmark icon (not plain grey text),
      so the row still reads as a branch head even fully collapsed (jj-idea-lm3o); the icon
      is the tracked-bookmark glyph as long as every hidden bookmark is tracked (all local
      bookmarks count as tracked), and falls back to the plain glyph if any hidden bookmark
      is an untracked remote; on a commit whose overflow is tags only (no bookmarks
      hidden), the chip's icon is the green tag glyph instead
- [ ] Stress-test repo, many concurrent branches (`jj-idea-1ojh`, `jj-idea-5i6i`): run
      FX-STRESS; set Settings → Version Control → Jujutsu → Log Limit to 200 so several
      branches fall out of view; apply an author or date filter to shrink the visible set
      further; confirm tree lines never cross over unrelated commits or share a lane
      (dropped edges to filtered-out ancestors are expected and tracked separately as
      `jj-idea-hlu3`, not a bug here); clear the filter and confirm the graph restores
      immediately without a manual Refresh
- [ ] Hovering that row's tooltip lists every bookmark, including the ones collapsed
      behind "+N more" (jj-idea-w61m), wrapping the bookmark list across multiple lines
      and showing the full description without being clipped by the screen edge; if the
      content is taller than the screen it scrolls instead of clipping (jj-idea-szn8)
- [ ] Left-clicking the "+N more" chip opens a popup listing the hidden bookmarks, each
      as a sub-menu with the usual bookmark actions (Rename…, Delete, Forget, etc.); right-
      clicking it does the same (jj-idea-w61m); with a large hidden count (~50+, e.g. via
      FX-STRESS), every sub-menu's actions are enabled/clickable as normal, not greyed out
      (jj-idea-lm3o); each sub-menu is labelled with the same colored bookmark
      (tracked/plain) or tag glyph its own chip would show

#### Graph layout under filtering (jj-idea-7jkr)

→ automate: jj-idea-2k2b (layout re-alignment under filtering is a deterministic
`LayoutCalculator` computation, testable without rendering)

Reuse FX-STRESS rather than building another throwaway multi-branch repo — it already has far
more lanes than the 3-4 needed here.

- [ ] Type a text filter that hides some rows — the graph re-draws to match the **visible** rows only: lines do not extend to hidden commits, no misaligned passthrough lines across the remaining rows
- [ ] Clear the text filter — the graph returns to the full layout immediately (no stale passthrough lines from the filtered view)
- [ ] Apply author, date, or root filter on a multi-branch repo — same check: graph lines align with visible rows only
- [ ] Rapid typing (several characters quickly) converges to a single correct layout within ~250 ms (no flickering per keystroke)

### MT-LOG-DETAILS

**Commit details panel**

**Code:** `ui/log/JujutsuCommitDetailsPanel.kt`, `ui/components/HtmlTextCanvas.kt`, `ui/components/TextCanvas.kt`, `ui/log/JujutsuLogContextMenuActions.kt`, `ui/log/LogClickTarget.kt`, `actions/filechange/FileChangeActionGroup.kt`
**Also re-run:** MT-DIFF-PREVIEW (details changes panel shares the preview-tab behavior); MT-LOG-TABLE (change-id link click handling and the file-change context menu are shared with the log table / working copy panel)

- [ ] Details panel shows on row selection
- [ ] Metadata displays correctly (author, date, change ID)
- [ ] Author line shows `Name <email>` as a single clickable mailto link; when the committer
      differs from the author, a "committed by Name <email>" line also appears with its own mailto link
- [ ] Narrow the details panel (or use a commit with a long name/email, e.g. a bot commit) so the
      author/committer line must wrap: `Name <email>` and `· date, time` each wrap as a whole (never
      splitting mid-email or mid-date), with a visible gap between them, never touching
- [ ] Description renders HTML formatting
- [ ] Splitter position persists
- [ ] Toggle details panel position (right/bottom) works
- [ ] On a commit with several long/hyphenated bookmarks (e.g. `hotfix/issue-123`,
      `feature/long-name-here`), narrow the panel until the bookmark line wraps — each
      bookmark (icon + name) stays intact on one line; wrapping only occurs between
      bookmarks, never inside a name or between its icon and text (jj-idea-kds1)
- [ ] The `Name <email>` link is link-colored, with **no underline** at rest; hovering it adds
      an underline, moving off removes it (jj-idea-iesq)
- [ ] Hovering a bookmark or tag chip in the details panel shows a subtle grey background
      highlight but no hand cursor (jj-idea-a52h) — check across the whole chip width including
      near its right edge, a hit-testing bug meant this used to only hold over roughly the left half
- [ ] **Left-clicking** a bookmark/tag chip does nothing here either; **right-clicking** opens the
      same menu as the log table (Filter Log to '...' plus rename/delete/forget/move/track), with
      the checkmark reflecting active filter state
- [ ] **Right-clicking** an author or committer email in the details panel opens the same menu as
      the log table (Send Email to ..., plus Filter Log by ... for the author) — this previously
      did nothing at all (jj-idea-a52h)
- [ ] **Right-clicking** a parent/change-id link (e.g. a parent reference in a merge commit's
      details) opens that commit's full log-row context menu (Show Diff, New/Edit, Describe,
      Abandon, Rebase, etc.) — this previously showed an empty "Nothing Here" placeholder
      (jj-idea-in2h). Left-click still navigates/selects that commit as before
- [ ] Right-click a change-id link whose target has since become invalid (e.g. abandon that
      commit via the CLI in another terminal, then right-click the now-stale link without
      refreshing) — shows an empty menu rather than throwing or crashing

#### Issue-tracker links in descriptions (jj-idea-10fo)

- [ ] In Settings → Version Control → Issue Navigation, add a pattern (e.g. issue regexp
      `[A-Z]+-\d+`, link `https://example.com/browse/$0`); Apply
- [ ] Select a commit whose description contains a matching reference (e.g. `Fixes JIRA-123`) —
      the reference renders underlined/link-styled in the details panel
- [ ] Clicking the reference opens the URL in your default browser
- [ ] A bare `https://…` URL in a description is also clickable and opens correctly
- [ ] A commit description with no matching reference renders unchanged (plain text, no link)

#### Issue-tracker links inside bookmark/tag chip names (jj-idea-vrmv)

- [ ] With an Issue Navigation pattern configured (as above), select a commit with a bookmark/tag
      whose name contains a matching reference (e.g. `jira-123-fix-thing`) — the reference renders
      link-colored within the chip in the details panel's bookmark/tag line; the rest of the chip
      (icon, remaining text) stays plain
- [ ] Hovering just the reference substring shows a hand cursor and underlines only it; hovering the
      rest of the same chip (icon or non-matching text) shows no hand cursor and no background
      highlight — the two hover cues don't overlap
- [ ] Left-clicking the reference substring opens the URL in your default browser; left-clicking
      elsewhere in the chip still does nothing
- [ ] Right-clicking the reference substring shows a popup with a single "Open <url>" action;
      right-clicking elsewhere in the same chip still shows the usual bookmark/tag actions menu
- [ ] A bookmark/tag name with no matching reference in the details panel renders and right-clicks
      exactly as before (ref-only hover highlight, no link)
- [ ] With no Issue Navigation patterns configured, descriptions render exactly as before

#### Details Changes Panel

- [ ] File change tree shows correct files
- [ ] Preview-tab behavior (double-click, Enter, tab-swap, Escape, Cmd/Ctrl+D, F4): see MT-DIFF-PREVIEW
- [ ] Open file for historical version opens correct version
- [ ] Open file for working copy opens editable editor
- [ ] jj-idea-lo7u: right-clicking a file for a historical commit shows "Compare Before with
      Another Commit..."; hidden for the working-copy entry and for a root commit
- [ ] Right-click a file in the details panel's change tree → **Show History** appears in the
      menu (jj-idea-cb3r, fixed for real in jj-idea-v9g4 — the initial jj-idea-cb3r fix added it to
      the menu-building code but the action stayed invisible there since it only read
      `CommonDataKeys.VIRTUAL_FILE`, which this tree only supplies for a working-copy selection —
      see `JujutsuChangesTree.showsLocalFiles`) and opens that file's custom history tab, same as
      from the editor's Jujutsu submenu
- [ ] Repeat from the **Working Copy** tool window's file tree and from a compare-changes panel
      (e.g. "Compare with Another Commit…") — **Show History** works from all of them, not just
      the commit details panel
- [ ] Right-click a **deleted** file (e.g. in a historical commit's file list) → **Show History**
      is still available (uses the file's last-known path, not just files with current content)
- [ ] Multi-select several files in the details panel's change tree for a **historical** commit
      (not `@`) → **Show History** is disabled/hidden rather than silently acting on just one of
      the selected files

#### Platform file actions on the changes tree (`JujutsuChangesTree.showsLocalFiles`)

- [ ] Select `@` (the working copy) in the log → in the details panel's change tree, select one
      or more files → the IDE's **Reformat Code** (Ctrl/Cmd+Alt+L) and **Optimize Imports**
      (Ctrl/Cmd+Alt+O) both act on the selected file(s) on disk
- [ ] Right-click a file in that same `@`-selection tree → Jujutsu submenu → **Annotate** opens
      the gutter annotations for that file
- [ ] F4 on that same `@`-selection still opens the file via **Jujutsu.OpenChangeFile** (unchanged
      — this tree owns the F4 shortcut, not the platform's generic Jump to Source)
- [ ] Select a **historical** (non-`@`) commit in the log → in the details panel's change tree,
      Reformat Code/Optimize Imports/Annotate either do nothing or act on the *editor's* current
      file (not silently acting on the historical revision or the working-copy file instead)
- [ ] "Open File" on a historical commit's change still opens that **revision's** content, not
      the current working-copy file, both before and after selecting files in the tree

### MT-LOG-FILTER

**Toolbar, filters, and reference filter**

**Code:** `ui/log/JujutsuFilterComponent.kt`, `ui/log/JujutsuAuthorFilterComponent.kt`, `ui/log/JujutsuDateFilterComponent.kt`, `ui/log/JujutsuReferenceFilterComponent.kt`, `ui/log/JujutsuRootFilterComponent.kt`, `ui/log/JujutsuPathsFilterComponent.kt`, `ui/log/LogFilterMatcher.kt`, `ui/common/FilterPriorityLayoutStrategy.kt`, `ui/common/CommitTablePanel.kt`, `ui/log/UnifiedJujutsuLogPanel.kt` (primaryActions), `actions/change/DescribeChangeAction.kt`, `actions/change/RebaseChangeAction.kt`

#### Toolbar & filters

- [ ] Refresh button reloads data; text search filters in real-time; regex and
      case-sensitivity toggles both work
- [ ] Author dropdown shows all authors and restricts visible entries when applied;
      bookmark/reference and date filters each restrict correctly
- [ ] Clear filters (X button) resets all filters; multiple active filters combine correctly (AND logic)

#### Search by Git commit hash (jj-idea-odzo)

→ automate: jj-idea-4u7j (hash-prefix matching, filter AND-combination, and regex/case
toggles are pure `LogFilterMatcher` logic, testable without rendering)

- [ ] Copy a full 40-character Git commit hash of a commit currently visible in the log
      (e.g. `jj log -T commit_id`) and paste it into the search field — the row filters
      in and is the only result
- [ ] Paste just an abbreviated prefix of that hash — it still matches
- [ ] Paste the hash in a different case (e.g. uppercase) — it still matches (case-insensitive
      by default)
- [ ] Paste a hash for a commit that isn't currently loaded in the log window and press **Enter**
      — see "Whole-repo search on Enter (jj-idea-lpbv)" below

#### Whole-repo search on Enter (jj-idea-lpbv)

**Code:** `jj/LogSearchRevset.kt`, `ui/log/UnifiedJujutsuLogDataLoader.kt` (`searchWholeRepo`,
`fetchSearchResults`), `ui/log/UnifiedJujutsuLogPanel.kt` (`onSearchSubmitted`),
`ui/common/CommitTablePanel.kt` (status bar), `ui/log/LogFilterMatcher.kt` (full-body matching)

→ automate: `jj-idea-lpbv`'s own unit tests already cover revset construction
(`LogSearchRevsetTest`) and per-repo fetch/merge logic (`UnifiedJujutsuLogDataLoaderTest`); this
section is for the end-to-end wiring that only shows up when jj actually runs.

Setup: lower "Number of changes to show" (Settings → Version Control → Jujutsu) to something
small (e.g. 100) against `scripts/fixtures/fx-stress.sh`'s ~1084-commit repo (`jj-stress-test`,
shared with MT-LOG-GRAPH/MT-LOG-FILTER's other stress fixtures) so most commits are off-window.

- [ ] Find a commit hash/change-id well past the window (e.g. `jj log -r 'all()' -T commit_id
      --no-graph --limit 2000`), paste it into the search field — typing alone shows no results;
      pressing **Enter** fetches and shows it as the only visible row, and the status bar reports
      it was found outside the current view
- [ ] Type a word that appears only in an off-window commit's description **body** (not its first
      line) — Enter finds it (full-body matching, not just the summary line)
- [ ] Type gibberish that matches nothing — Enter leaves the table unchanged and the status bar
      says no changes were found
- [ ] Type free text containing punctuation (e.g. `fix: the . thing`) — Enter must not error (check
      `idea.log`); it just searches description/author as usual
- [ ] Click **Refresh** — the merged-in search results disappear and the log returns to the
      configured revset/limit
- [ ] In a multi-repo project, repeat with a hash from a second repo — results from both roots
      merge and the root gutter stays correct
- [ ] Open a file's history (right-click a file → Show History) — Enter in its search field
      behaves exactly as before (whole-repo search is log-window only, not wired to file history)

#### New/Edit toolbar buttons (jj-idea-e53e)

- [ ] **New** and **Edit** icon buttons appear at the left of the main log toolbar, before Refresh, each with a tooltip
- [ ] Selecting a mutable non-working-copy change and clicking **Edit** moves the working copy to it (it becomes `@`) and the log reselects it
- [ ] Selecting the working-copy change or an immutable commit disables **Edit**
- [ ] **Edit** has a default keyboard shortcut (Ctrl/Cmd+Shift+E, jj-idea-crt0) — check Settings → Keymap for "Jujutsu.EditChange"; with a log row selected, pressing it edits that row. (This shortcut soft-conflicts with IntelliJ's built-in "Recent Locations" outside the log table — Recent Locations still wins there since Edit is disabled without a log selection; a keymap conflict warning in Settings → Keymap is expected, not a bug)
- [ ] Clicking **New** with a change selected creates a new empty change on top of the selection and it becomes `@`; with no selection it stacks on the working copy
- [ ] Clearing the log selection entirely (e.g. Ctrl/Cmd-click the selected row to deselect) disables **New** (greyed out) rather than removing it from the toolbar — it stays in place, matching **Edit**'s behavior
- [ ] Open a file's history (right-click a file > Show History) — confirm its toolbar shows only Refresh/search, with no New/Edit buttons

#### Rebase/Describe toolbar buttons (jj-idea-ck64, GitHub #78)

- [ ] **Rebase** and **Describe** icon buttons appear on the main log toolbar, after **Edit** and before Refresh, each with a tooltip and a default keyboard shortcut (check Settings → Keymap for "Jujutsu.RebaseChangeToolbar" / "Jujutsu.DescribeChangeToolbar")
- [ ] Selecting a mutable change and clicking **Describe** opens the same description dialog as the right-click menu's Describe entry; editing and confirming updates the change
- [ ] Selecting an immutable change disables **Describe**; selecting nothing also disables it (stays in place, greyed out)
- [ ] Selecting one or more mutable changes (same repo) and clicking **Rebase** opens the same rebase dialog as the right-click menu's Rebase entry, pre-populated with the selection as source
- [ ] Multi-selecting changes across two repos in a multi-root project disables **Rebase** (no arbitrary repo is picked), matching **New Change From These**'s cross-repo behavior
- [ ] Selecting only immutable changes disables **Rebase**
- [ ] Pressing the toolbar buttons' keyboard shortcuts while the log table has focus triggers the same actions as clicking them

#### Context-menu shortcut hints (jj-idea-crt0)

- [ ] Right-click a log row → the **Rebase** and **Describe** entries show their keyboard shortcut hint next to the label (e.g. "Ctrl+Shift+R"), matching the toolbar buttons — this previously showed no hint even though the toolbar shortcut existed
- [ ] Right-click a change-id link (e.g. a parent reference in the commit details panel) → its menu's **Rebase**/**Describe** entries do **not** show a shortcut hint (this menu acts on the link's target, not the table's live selection, so it deliberately keeps using a non-registered action) and that menu has no **New**/**Edit** entries at all
- [ ] Multi-select several mutable rows (same repo), right-click → **Rebase** is enabled and opens with all of them as source; multi-select spanning two repos → **Rebase** is disabled
- [ ] Multi-select several rows, right-click → **Describe** is disabled (only meaningful for a single selection)
- [ ] Open a file's history (right-click a file > Show History) — confirm its toolbar still shows only Refresh/search, with no Rebase/Describe buttons

#### Narrow-width toolbar (jj-idea-kxx4)

- [ ] Shrink the log tool window / splitter narrower and narrower — New, Edit, Refresh,
  Fetch, **Push**, Columns, and Details-position buttons all stay visible and clickable at
  every width; they are never pushed off-screen
- [ ] As the window narrows, the search field shrinks down to a minimum width and stops
  (it does not keep shrinking to zero or overlap the buttons)
- [ ] As the window narrows further, filter chips (Reference, Author, Date, Root) start
  disappearing from the toolbar one at a time and a "»" overflow chevron appears in their
  place; clicking the chevron opens a popup listing all filters, including the ones that
  no longer fit — from there they're fully usable (clicking one opens its dropdown)
- [ ] With no filters applied, narrowing hides filters in trailing (rightmost) order first
- [ ] Apply a value to a filter that would otherwise be hidden first (e.g. select a bookmark
  in the Reference filter, then narrow) — the applied filter stays visible and unapplied
  filters are hidden ahead of it, even though the applied filter isn't the leftmost one;
  whatever remains visible keeps its original left-to-right order (nothing reorders/jumps)
- [ ] Widen the window back out — hidden filters reappear and the chevron disappears once
  everything fits again

#### Reference filter (bookmark/tag dropdown)

Use a repo where the log limit is **smaller** than total history, with at least one bookmark and
one tag pointing at commits **beyond** the limit. FX-STRESS at Log Limit 100 works: `main`,
`release-1.0`/`release-2.0`, and the `v1.0` tag all sit deep in history, and `main`'s ancestry
is immutable.

- [ ] Dropdown lists **all** local bookmarks (with the gold bookmark icon, narrower than the tag icon), not only those on loaded log rows, including bookmarks beyond the log limit
- [ ] Dropdown lists **all** tags (with the green tag icon), including tags beyond the log limit
- [ ] Bookmark and tag icons are visibly distinct from each other and from the "@" working-copy icon, and colored to match the bookmark/tag colors used in the log table

#### Loading placeholder (jj-idea-a52h)

- [ ] Open the dropdown as early as possible after opening the project/log tab (before bookmarks/tags have had time to load) — it shows a single disabled "Loading bookmarks and tags…" entry instead of looking empty
- [ ] Reopen the dropdown once bookmarks/tags have loaded — the placeholder is gone, replaced by the real list
- [ ] For a repo that genuinely has no bookmarks or tags, the dropdown eventually shows as empty (no bookmark/tag rows, no "@" if there's no working copy either) once loading finishes — it does **not** get stuck on the loading placeholder forever

#### Remote-only bookmarks (jj-idea-iadu)

Clone a repo and leave at least one remote bookmark **untracked** (e.g. `jj git clone`, then
push a bookmark from another clone without running `jj bookmark track` in this one —
`jj bookmark list --all-remotes` in the terminal should show it as untracked).

- [ ] Dropdown lists the untracked remote bookmark as `name@remote`, with an icon visibly
      distinct from local bookmarks (plain vs. filled bookmark icon)
- [ ] A local bookmark whose remote is synced (tracked, same target) appears only **once**, as
      the plain local name — no duplicate `name@remote` row
- [ ] Selecting the remote-only bookmark filters the log to that commit and its ancestors,
      expanding the log window first if the target is outside the current limit
- [ ] The currently-selected reference shows a checkmark next to its icon; no other row does
- [ ] Hovering over rows or moving the keyboard selection up/down does **not** move the checkmark — it stays on the actually-selected reference
- [ ] Creating/deleting a bookmark or tag in the terminal updates the dropdown after the auto-refresh (see MT-LOG-REFRESH) — without clicking Refresh or saving a file
- [ ] Selecting a reference that **is** on a loaded row filters the log to that commit and its ancestors, and the dropdown closes
- [ ] Selecting a reference whose target is **outside** the log limit expands the log to a context window around that commit, then applies the ancestor filter (no silent empty result)
- [ ] Selecting "@" (working copy) filters to the working copy and its ancestors
- [ ] Reopening the dropdown while a filter is active scrolls to and highlights the currently-selected reference
- [ ] Arrow up/down moves the highlight; Enter applies the highlighted reference and closes the dropdown
- [ ] Clearing the filter restores the full (limited) log

#### Multi-repo scoping (jj-idea-1ra9, jj-idea-2xf3)

Open a multi-root project with at least two independent (non-colocated) jj repos — FX-STRESS
alongside one other repo works.

- [ ] Filtering to a bookmark that exists in only one repo shows **only** that repo's ancestry —
      no other repo's root commit ("zzzzzzzz", "no description", empty) appears
- [ ] The graph draws **no** connector line between rows from different repos, even though every
      repo's root shares the same underlying change id
- [ ] Filtering to "@" (working copy) shows **every** repo's working copy, each with its own
      ancestry — not just the first repo's

### MT-LOG-REFRESH

**Auto-refresh**

**Code:** `ui/log/UnifiedJujutsuLogDataLoader.kt`, `ui/common/BackgroundDataLoader.kt`

- [ ] Log refreshes when files change in working copy
- [ ] Log refreshes after VCS operations (describe, new, edit)
- [ ] Log refreshes after an **external** jj operation run in a terminal (e.g. `jj new`,
      `jj bookmark create`) within ~300 ms, without saving a file (op-heads watch) — this is the
      canonical auto-refresh check; other sections (bookmark widget, reference filter) reference
      it rather than repeating it
- [ ] Working copy (@) selection maintained after refresh
- [ ] No flickering during refresh
- [ ] jj-idea-c4tp: open a large repo's log, click Refresh, then close the project while it is
      still loading → project closes promptly (no multi-minute stall); idea.log shows the load
      being cancelled rather than running to completion

### MT-CTXMENU

**Log row context menu actions**

**Code:** `actions/change/`, `ui/duplicate/DuplicateDialog.kt`, `ui/duplicate/DuplicateImmutabilityGuard.kt`, `ui/newchange/NewChangeDialog.kt`, `ui/rebase/RebasePreviewPanel.kt`, `ui/rebase/RebaseSimulator.kt`, `ui/common/JujutsuCompareChangesPanel.kt`, `ui/components/RevisionSelectorPopup.kt`, `actions/change/compareWithRevisionAction.kt`
**Also re-run:** MT-SQUASH, MT-SPLIT (share the commit picker); MT-DIFF (Compare with Working Copy / Show Diff in New Tab reuse the RevisionSelectorPopup and Changes-pane view); MT-WORKINGCOPY (its "New Change" button shares `CommandExecutor.new`)

- [ ] Right-click opens context menu
- [ ] **Copy Change ID** works and copies to clipboard
- [ ] **Copy Description** works and copies to clipboard
- [ ] **New Change From This** (primary, no dialog) creates new change directly and refreshes
- [ ] **New Change...** (secondary) opens the New Change dialog and creates the change
- [ ] **Edit** action changes working copy
- [ ] **Describe** action opens dialog and updates description
- [ ] jj-idea-n3w1 (GitHub #46): the Describe dialog opens with the description field already
      focused; it's a real commit-message editor (spellcheck, subject-length inspection, Ctrl+E
      history popup), not a plain text box; Enter inserts a newline, Ctrl+Enter accepts
- [ ] **Abandon** action removes change after confirmation
- [ ] **Duplicate Change** action creates an identical copy in place, with a new change ID and the same description; `@` does not move
- [ ] **Duplicate Onto...** opens a dialog to pick a destination and placement (onto/after/before), then creates the copy there
- [ ] jj-idea-2md7: hovering a commit row in the Duplicate Onto... destination picker, and in the
      Rebase destination picker / preview, shows real bookmark/tag chips and status icons in the
      tooltip - not a broken-image glyph
- [ ] jj-idea-rskx: **Set Tag Here...** shows a distinct tag-plus badge icon (not the platform's
      generic + icon); entering a name creates the tag at that commit and the log updates

#### Duplicate Change (jj-idea-vu35)

- [ ] Right-clicking an **immutable** change still offers both Duplicate actions (unlike Abandon/Describe/Edit)
- [ ] Multi-selecting several changes (same repo) and choosing **Duplicate Change** creates an identical copy of each, in place
- [ ] Multi-selecting commits across two repos in a multi-root project: both Duplicate actions are disabled/hidden
- [ ] **Duplicate Onto...**: choosing "Onto (-d)" places the copy as a child of the destination
- [ ] **Duplicate Onto...**: choosing "Insert after (-A)" / "Insert before (-B)" places the copy relative to the destination accordingly
- [ ] **Duplicate Onto...** with no destination selected shows a validation error and does not close the dialog

#### Duplicate Onto... immutability guard (jj-idea-70e6)

In a repo with an immutable trunk (e.g. `main` tracked as immutable, with mutable commits on top):

- [ ] Selecting an immutable **head** (no children) as destination: "Insert after" stays enabled; "Insert before" greys out
- [ ] Selecting an immutable **non-head** commit (has a child) as destination: both "Insert after" and "Insert before" grey out; only "Onto" is selectable
- [ ] With "Insert before" already selected, immutable commits don't appear in the destination picker at all
- [ ] With "Insert after" already selected, only immutable commits that have an immutable child are hidden from the picker; an immutable head remains selectable
- [ ] Switching placement back to "Onto" makes every commit (including immutable ones) reappear in the picker
- [ ] A permitted "Insert after" on an immutable head actually succeeds when you click Duplicate
- [ ] Normal "Onto" duplicates and the quick in-place **Duplicate Change** action are unaffected by the guard

#### Dialog commit-picker ordering (jj-idea-6fxz, jj-idea-45id)

→ automate: jj-idea-pa05 (cold-cache ordering is a `logCache`/`RepoLogCache` invariant,
testable without rendering)

Restart the IDE (or open a repo the log tool window hasn't loaded yet) so `logCache` starts cold for it, then — **without** opening the main log tab for that repo first — open a dialog with a commit picker (Rebase, Squash Into..., Duplicate Onto..., Move Bookmark to Change):

- [ ] The picker's commit order matches what the main log window shows (newest first, root/oldest last) — not reversed or arbitrary
- [ ] The graph connector lines in the picker render correctly (no crossed/backwards lines), consistent with a cold-cache fetch
- [ ] Opening the main log tab afterwards shows the same order as the dialog did

In a **multi-repo** project (multiple `.jj` roots open together):

- [ ] Open the main log tab (loads and merges all repos), then open a commit picker for each repo in turn — every repo shows its own commits newest-first, root last; none show the root (or any commit) out of place
- [ ] Repeat across a few IDE restarts — the correct ordering should hold consistently for every repo, not just some of them

#### New Change quick action (jj-idea-byfa)

- [ ] With the log focused and a commit selected, pressing Cmd/Ctrl+Shift+N creates a new change on top of it, with no dialog, and the log reselects the new change
- [ ] With the log focused and nothing selected but the default working-copy (@) selection, Cmd/Ctrl+Shift+N creates a new change on top of @
- [ ] With the **editor** focused (not the log), Cmd/Ctrl+Shift+N still triggers the IDE's normal Go to File / New Scratch File action - it is not intercepted
- [ ] Multi-selecting two commits (same repo) and choosing **New Change From These** (or the shortcut) creates a merge change with both as parents
- [ ] Multi-selecting commits across two repos in a multi-root project: **New Change From This/These** is disabled/hidden (no arbitrary repo is picked)

#### New Change... dialog (jj-idea-grc8, GitHub #83)

Build a small stack `A → B → C` (three plain changes) for this section.

- [ ] Right-click B → **New Change...** opens a dialog showing B as the target, an empty
      description field, placement radios (Onto/Insert after/Insert before, Onto selected by
      default), a "Switch working copy to the new change" checkbox (checked), and a live preview
      graph
- [ ] **Onto** + typing a description + Ctrl+Enter (Cmd+Enter): creates a plain child of B with
      that description, `@` moves to it — matching the old "New Change with Description..."
      flow's effect. (jj-idea-n3w1, GitHub #46: the description field became a real
      commit-message editor, so plain Enter now inserts a newline instead of submitting — verify
      this is in fact the current behavior, since it's a change from the plain text area this
      dialog used before)
- [ ] **Insert after**: preview shows the new change between B and C; clicking Create makes it
      so — C is rebased onto the new change, `@` moves to it, log reselects it
- [ ] **Insert before**: preview shows the new change between A and B; clicking Create makes it
      so — B (and C) are rebased onto the new change, `@` moves to it
- [ ] Unchecking "Switch working copy to the new change" before clicking Create: the change is
      inserted but `@` stays on its prior commit (`jj new --no-edit`)
- [ ] Preview fidelity: for each placement mode, the graph shown before clicking Create matches
      what the log shows immediately after
- [ ] Multi-selecting B and another head, then **Insert after**: creates a merge change with both
      as parents and relocates both targets' children onto it
- [ ] Right-clicking an **immutable** change and choosing New Change...: **Insert before** is
      disabled (would rewrite the immutable target); **Insert after** stays enabled unless the
      target has an immutable child, matching the Duplicate Onto... immutability guard above
- [ ] Cross-repo multi-select in a multi-root project: the action is disabled/hidden (no arbitrary
      repo is picked), same as **New Change From This/These**
- [ ] Right-clicking a `jjc://` change-navigation link (e.g. a parent reference in the commit
      details panel) still offers **New Change...** and it acts on the link's target

#### Compare with Working Copy (jj-idea-a6cz, jj-idea-vtdl)

- [ ] Right-clicking a non-working-copy commit shows **Compare with Working Copy**
- [ ] Right-clicking the working-copy entry: **Compare with Working Copy** is **not visible**
- [ ] Invoking it on a commit with differences from `@` opens the **VcsChanges** tool window with a Changes tree listing every changed file (added/modified/deleted/renamed all included), and the first file's diff open in the editor
- [ ] Selecting other files in the tree updates the diff in the same reusable editor tab
- [ ] The right (working-copy) side of the diff is editable, and edits are written through to the real file on disk
- [ ] The left (commit) side is read-only
- [ ] Right-clicking a file in the Changes tree shows the jj file-change context menu (Show Diff, Restore, etc.)
- [ ] Invoking it on a commit identical to `@` shows a "No Differences" notification instead of an empty pane

#### Compare with Another Commit / Compare Before with Another Commit (jj-idea-jp33)

- [ ] Right-clicking any historical commit shows **Compare with Working Copy**, **Compare with
      Another Commit...**, and **Compare Before with Another Commit...** together, in that order
- [ ] Right-clicking the **working-copy** entry: **Compare with Working Copy** is not visible, but
      **Compare with Another Commit...** is still shown and enabled
- [ ] Right-clicking a **root commit** (no parents): **Compare Before with Another Commit...** is disabled
- [ ] Invoking **Compare with Another Commit...** opens the same revision-picker popup as
      **Compare with Branch...** (bookmark/change ID/revision search); picking one opens a
      Changes-pane tab titled `<selected> vs <picked>`, with the **selected commit's content on
      the left** and the **picked revision's content on the right**, both read-only
- [ ] Invoking **Compare Before with Another Commit...** on a commit compares its **parent** (not
      itself) against the picked revision, same left/right convention; on a **merge commit** the
      left side reflects the auto-merged parent content (matching Compare with Working Copy's
      merge-parent handling)
- [ ] Picking a revision identical in content to the base shows a "No Differences" notification, no tab opens
- [ ] Typing an unresolvable revision in the picker shows a "Compare Failed" error dialog
- [ ] Both actions also appear, and work identically, from a `jjc://` change-navigation link's menu

#### Show Diff in New Tab, multi-file (jj-idea-vtdl)

- [ ] Multi-selecting files (in the log's file list or a commit's changes) and choosing **Show Diff in New Tab** opens the same VcsChanges Changes-pane view as Compare with Working Copy, with the first file's diff open
- [ ] Selecting a single file still shows a title with just that file's name; multiple files show "N files"

### MT-SQUASH

**Squash Into…**

**Code:** `ui/squash/SquashIntoDialog.kt`, `ui/squash/SquashFilePreview.kt`, `ui/common/FileDiffPreviewPanel.kt`, `ui/common/HunkPickPreviewController.kt`, `ui/common/HunkSelectionModel.kt`, `diffedit/HunkPickerDialog.kt`, `diffedit/HunkArrowDiffExtension.kt`, `diffedit/DiffEditTool.kt`, `actions/change/squashIntoAction.kt`, `actions/change/squashFromAction.kt`, `actions/filechange/SquashIntoFilesAction.kt`
**Fixture:** FX-STACK
**Also re-run:** MT-CTXMENU (shares the commit picker); MT-SPLIT (shares `ui/common/FileDiffPreviewPanel.kt`, `ui/common/HunkPickPreviewController.kt`, the hunk picker, and the staging protocol); MT-DIFF, MT-DIFF-PREVIEW (the hunk picker registers a plugin-wide `diff.DiffExtension` — confirm it stays a no-op on every other diff viewer)

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
- [ ] jj-idea-2md7: hovering a commit row with bookmarks/tags in the picker table shows real chip
      icons in the tooltip - not a broken-image glyph

→ automate: jj-idea-ikr6 (description auto-population + validation logic below is pure
string/state logic, no rendering dependency)

#### Description editor (jj-idea-n3w1, GitHub #46)

The description field is now a real commit-message editor (`ui/components/DescriptionEditor.kt`,
wrapping the platform's `CommitMessage`) instead of a plain text area:

- [ ] Typing a long first line shows the subject-length inspection highlight; misspelling a word
      shows a spellcheck squiggle
- [ ] The toolbar's history button (Ctrl+E / Cmd+E) opens a popup of recently-used descriptions
      from other dialogs/changes; picking one previews it, Escape reverts, and it doesn't close
      the dialog
- [ ] Enter inserts a newline (does not submit/click OK); Ctrl+Enter (Cmd+Enter) submits

#### Per-file diff preview (jj-idea-8a8z)

The preview always shows **Before** (the source's own pre-change content, fixed) next to
**Destination** (that same content, plus this file's change if it's ticked — i.e. what the
destination ends up with). This is deliberately anchored to the source's own before/after,
not the real destination's content — see the section's linked issue for why.

- [ ] Right-click a mutable change with 2+ changed files → **Squash into Parent…** (or
      **Squash from Here into…**) → a preview pane appears to the right of the file list,
      wider dialog overall
- [ ] Click a file → preview shows a syntax-highlighted, read-only diff titled roughly
      "Before" / "Destination (all changes)", showing the file's full change (all files are
      ticked by default, so everything moves)
- [ ] Untick that file → titles flip to "Destination (unchanged)" and the diff goes empty;
      re-tick → restored
- [ ] Click a second file → preview switches to it; click back to the first → switches back
      immediately (no visible reload)
- [ ] **Squash Into Here from…** (multi-source picker): select a file, then change the
      source selection in the picker table → preview resets to the placeholder and the file
      tree repopulates
- [ ] Multi-select two changes → **Squash Into…** → preview works on the combined file list
- [ ] Select a binary or deleted file → preview degrades gracefully (no exception)

#### Hunk picking (jj-idea-4q7m)

Single-source only — jj's diff editor is one before/after pair, so hunk-level squashing across
multiple sources isn't well-defined. Reuses Split's 3-pane arrow picker
(`diffedit/HunkPickerDialog.kt`) with squash-specific wording: Before (fixed) | destination
(live) | full source change (fixed) — a right-hand arrow squashes a hunk into the destination, a
left-hand arrow reverts it back to unsquashed. No "resolved" concept, so no merge-conflict
confirmation dialogs.

- [ ] Right-click a mutable change with 2+ changed files → **Squash into Parent…** → select a
      changed file → **Pick Hunks…** button is enabled below the preview
- [ ] **Squash Into…**/**Squash Into Here from…** with **two or more sources selected** →
      **Pick Hunks…** is **not offered** (hidden); selecting down to exactly one source makes it
      appear
- [ ] Click **Pick Hunks…** → a 3-pane diff opens: Before | destination (live) | full source
      change, with arrows at each hunk's divider
- [ ] Move one hunk's arrow so only part of the file's change is squashed → **Apply** → dialog
      closes with **no confirmation dialog of any kind**
- [ ] The file now shows **half-checked** in the file list; the diff preview title reads
      "Destination (partial)"
- [ ] **The file's tick state is unchanged by a partial pick** — re-open the file list and
      confirm the checkbox itself wasn't force-ticked or unticked
- [ ] Reopen **Pick Hunks…** on that file → the middle pane resumes the exact prior partial
      selection
- [ ] Click **Apply** with every hunk moved to the destination side → file **fully ticks**;
      with every hunk left unsquashed → file **fully unticks**; either way the override clears
      (reopening the picker starts fresh from the tick-derived default, not a stale partial)
- [ ] With a partial pick present, the **"Delete empty source and move working copy"** checkbox
      is disabled (a partial squash can never empty the source) and the description field shows
      only the destination's own description (no merge)
- [ ] Click **Squash** → `jj diff` on the destination shows only the picked hunk; the source
      still has the rest of that file's change
- [ ] Repeat with a change that **deletes a file**: tick the deletion (or pick-hunks to fully
      squash it) alongside a partial pick elsewhere → the deletion lands in the destination too
      (not left behind as an empty file)
- [ ] Open any ordinary diff elsewhere in the IDE → no gutter arrows appear (the `DiffExtension`
      stays a no-op outside the picker)
- [ ] **Split regression pass** (the preview cache and hunk picker are now shared with Split):
      run MT-SPLIT's "Basic hunk selection" and "Hunk picking with the 3-pane arrow picker"
      checks — wording must read "Parent"/"Child", not the squash wording above

#### Description auto-population (full squash — all files selected)

- [ ] Field pre-fills correctly for each source/dest description combination: both non-empty
      (`<dest desc>\n\n<source desc>`), dest empty (source only), source empty (dest only),
      both empty (empty); multi-source appends all non-empty source descriptions after dest
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

### MT-SPLIT

**Split, hunk-level selection**

**Code:** `ui/split/SplitDialog.kt`, `ui/common/HunkSelectionModel.kt`, `ui/common/HunkPickPreviewController.kt`, `ui/common/FileDiffPreviewPanel.kt`, `diffedit/HunkPickerDialog.kt`, `diffedit/HunkArrowDiffExtension.kt`, `diffedit/DiffEditTool.kt`, `actions/change/splitAction.kt`, `actions/filechange/SplitFilesAction.kt` (also `SplitIntoNewParentFilesAction`)
**Also re-run:** MT-CTXMENU (shares the commit picker in some flows); MT-DIFF, MT-DIFF-PREVIEW (the hunk picker registers a plugin-wide `diff.DiffExtension` — confirm it stays a no-op on every other diff viewer); MT-SQUASH (shares `ui/common/FileDiffPreviewPanel.kt`, `ui/common/HunkPickPreviewController.kt`, the hunk picker, and the staging protocol)

Setup: create a scratch jj repo with a file that has at least **two separate** hunks of changes
(so partial selection is meaningful).

Model: **ticking a file moves it to the new child commit**; unticked files stay in the
parent. Nothing is ticked by default. "Pick Hunks…" opens a native **3-pane** diff — Before
(fixed) | Parent (live) | Child (fixed) — with a directional arrow at each hunk's divider instead
of a checkbox: a right arrow at a Before|Parent divider moves that hunk to the child; a left
arrow at a Parent|Child divider moves it back. The Parent pane genuinely updates in place as you
click, so you always see the actual resulting parent content, not just an inferred state — this
replaced an earlier 2-pane checkbox picker (itself replacing the original 3-way *merge* widget)
specifically so gnarlier, many-hunk splits have somewhere to see the live result while picking.
The Parent pane is view-only (arrow clicks only, no direct typing) — deliberately, so the result
is always a well-formed composition of Before/Child hunks. No "resolved" concept, so no
merge-conflict confirmation dialogs anywhere in this picker.

#### Basic hunk selection (main dialog preview)
- [ ] Right-click a mutable change → **Split…** → dialog shows changed-files list on the left (nothing ticked) and a native read-only diff preview on the right
- [ ] Click a file in the list → right panel shows a native syntax-highlighted diff titled **"Parent (all changes)"** / **"Child (no changes)"**, with an **empty diff** (nothing ticked yet, so nothing moves)
- [ ] Tick the file → titles switch to **"Parent (unchanged)"** / **"Child (all changes)"**, showing the **full diff** (the whole file's change moves to the child); untick → back to the "all changes"/"no changes" pair and empty diff
- [ ] Fully-ticked files show a filled checkbox; unticked show empty; partially-picked (see below) show a **half-checked** box
- [ ] Directory nodes containing a partial file also show a half-checked box

#### Right-click file(s) → "Split into New Child"
- [ ] Select one or more files in the working-copy / commit-details file list, right-click → **Split into New Child** → dialog opens with exactly those files **ticked** (moving to the child)
- [ ] Split → the new child commit contains only the selected files; the parent keeps the rest

#### Right-click file(s) → "Split into New Parent" (jj-idea-qswq / jj-idea-tkog, GitHub #74)

Uses `jj split -B` (verified against jj 0.44: the ticked fileset becomes a **new** commit inserted
before the source; everything else **stays on the source's own change ID and location** — the
opposite polarity from "Split into New Child"'s plain `jj split`, where the ticked fileset becomes
a new **child** and the unticked fileset keeps the original ID). Unlike the original jj-idea-qswq
implementation (which just inverted the tick pre-selection through the same no-flag `jj split`),
the right-clicked files now tick **directly**, same as "Split into New Child".

- [ ] Select one or more files, right-click → **Split into New Parent…** appears alongside **Split into New Child…**
- [ ] Invoking it opens the dialog with exactly the selected files **ticked** — the same starting tick state as "Split into New Child", not inverted
- [ ] Dialog title reads "Split into New Parent"; the ticked pane's header reads **"New commit (will become parent of &lt;shortid&gt;)"** and the unticked pane's header reads **"Stays here (keeps change ID &lt;shortid&gt;)"** — not "Parent"/"Child" wording, which would say the opposite of what happens in this mode, and explicit enough that which side becomes whose parent doesn't require inference
- [ ] The **"Stays here"** description field is on top, **"New commit"** below — the reverse of "Split into New Child"'s order (Child on top, Parent below) — matching each side's actual position in the log: "Stays here" keeps the more-recent position, "New commit" becomes the older parent one row further down
- [ ] The "Create parallel commits" checkbox is **not shown** (mutually exclusive with `-B`)
- [ ] "Pick Hunks…" is **not shown** (hunk-level partial selection isn't supported in this mode)
- [ ] Split → via `jj log`/`jj show`: the ticked files land in a **new commit inserted as the parent** of the original; the original commit (unticked files) keeps its **own original change ID**, now with the new commit as its parent
- [ ] Editing the "New commit" description field and splitting → the new commit gets that description (passed as `-m`); the "Stays here" field, if left unedited, leaves the original commit's description untouched
- [ ] Editing the "Stays here" description field and splitting → after the split completes, the original commit's description updates to match (chained via a follow-up `jj describe` on the same, unchanged change ID)
- [ ] **Splitting the working copy itself**: an info line under the source commit reads "The working copy (@) stays on <shortid>; the new commit becomes its parent" — after splitting, confirm `@` is still genuinely on the original change ID (not the new parent)
- [ ] Compare: repeat "Split into New Child" on the working copy — its info line instead reads "The working copy (@) moves to the new commit", and after splitting `@` has genuinely moved
- [ ] Selecting **every** changed file → dialog opens with every file ticked, which trips the "at least one file must stay here" validation (nothing would be left at the original change ID) — expected, not a bug
- [ ] Selecting **no** files → ticking nothing trips "move at least one file to the new commit" — expected

#### Hunk picking with the 3-pane arrow picker
- [ ] Click **Pick Hunks…** → a dialog opens titled "Pick Hunks — <filename>" with **three** panes: Before | Parent (live) | Child
- [ ] On a freshly-opened **unticked** file: Parent's text equals Child's; every hunk shows as a Before|Parent divider bar with a **right arrow**
- [ ] Click a right arrow → that hunk's Parent content flips to Before's text (matching); the Before|Parent bar for that hunk disappears, and a **new** Parent|Child bar with a **left arrow** appears in its place
- [ ] Click that left arrow → reverses back to a Before|Parent bar with a right arrow — confirm this is reversible any number of times, either direction, independently per hunk
- [ ] **Try typing directly into the Parent pane** → rejected; it's view-only, arrow clicks are the only way to change it (a deliberate choice, so the result is always a clean composition of Before/Child hunks, never a hand-edited hybrid)
- [ ] Click **Apply** with a mix of moved/unmoved hunks → dialog closes immediately with **no confirmation dialog of any kind** (the regression the original merge-widget picker had — a "Save changes and mark the conflict resolved anyway?" prompt used to fire here)
- [ ] After Apply → file shows **half-checked** in the file list; summary shows "(N partial)"
- [ ] **The file's tick state is unchanged by a partial pick** — if it was unticked before opening the picker, it's still unticked after a partial Apply
- [ ] Moving every hunk to the child → Apply results in a **fully ticked** file (no half-check), same as ticking it directly
- [ ] Moving no hunks (or reversing back to none) → Apply results in the file being **fully ticked or unticked** to match its starting state, with no partial override left over
- [ ] Click **Cancel** → closes immediately with **no confirmation dialog**; file state (tick + any prior override) unchanged
- [ ] **Reopen "Pick Hunks…" on a file with an existing partial selection** → the Parent pane opens already showing the exact prior split (the content itself resumes; no per-hunk state to reconstruct)
- [ ] Split (linear) → child commit contains only the hunks left pointing at Child; parent has the rest
- [ ] Log refreshes selecting the newly created change
- [ ] **Global extension no-op check**: open any ordinary diff elsewhere (log → Show Diff, a working-copy file diff) — confirm **no arrows appear** and behavior is identical to before (the arrow overlay is registered as a plugin-wide `diff.DiffExtension`, gated to fire only inside this picker)

→ automate: jj-idea-ygtw (validation, whole-file fast path, and binary gating below are
state/routing logic, not rendering)

#### Descriptions
- [ ] Both description fields are pre-populated with the source commit's description
- [ ] Child description field appears **above** the parent field (matching the child's position above the parent in the log)
- [ ] Editing the child description field updates the child commit; editing parent updates the parent
- [ ] (jj-idea-n3w1, GitHub #46) Both fields are real commit-message editors, not plain text
      areas: typing a long subject line highlights it, misspellings get a spellcheck squiggle,
      and Enter inserts a newline rather than doing anything else

#### Parallel split
- [ ] Check "Create parallel commits" → header labels switch to "First" / "Second"
- [ ] Split → two sibling commits created (not parent/child)

#### Validation
- [ ] With nothing ticked → OK is disabled with a message to move at least one file to the child
- [ ] With everything ticked (no overrides) → OK is disabled with a message that at least one file must remain in the parent

#### Whole-file fast path
- [ ] With no partial hunk selection (all files fully ticked or unticked) → split completes via file-level `jj split` (no diff-editor overhead); verify via log that both commits have the expected files

#### Binary / conflicted files
- [ ] A binary file in the changed list shows no "Pick Hunks…" button (whole-file only)

### MT-BOOKMARK

**Bookmark widget**

**Code:** `ui/toolbar/JujutsuBookmarkToolbarWidget.kt`, `ui/statusbar/JujutsuBookmarkStatusBarWidget.kt` + `JujutsuBookmarkStatusBarWidgetFactory.kt`, `actions/bookmark/BookmarkMenu.kt`, `actions/bookmark/`, `actions/bookmark/pushBookmarkAction.kt`, `jj/ClosestBookmarks.kt`, `jj/JjFeature.kt`, `ui/workingcopy/WorkingCopyControlsPanel.kt` (Advance Bookmark toolbar button)
**Also re-run:** MT-LOG-REFRESH (label reactivity relies on the same auto-refresh path); MT-CROSS (multi-repo dropdown structure); MT-WORKINGCOPY (Advance Bookmark toolbar button); MT-GIT (push confirmation dialogs triggered from this action); MT-LOG-FILTER (the bookmarks panel's "Filter Log to Bookmark" action); MT-LOG-TABLE (log tab layout — the bookmarks panel adds a splitter); MT-SETTINGS (new persisted per-window panel-visibility state)

As of jj-idea-cpno, the bookmark widget lives in the main IDE toolbar (next to where Git's
branch widget would sit), not the log window's filter row — this is the location fix for
GitHub #62. A status-bar fallback (MT-BOOKMARK-STATUSBAR below) takes over when the main
toolbar itself is hidden or unavailable.

#### Single-repo project

- [ ] "\<name\>" label appears in the **main IDE toolbar** (not the log toolbar) when @ has a local bookmark
- [ ] `jj new` off a bookmarked change with nothing left ahead of it — label shows "\<name\> +1" (jj-idea-l7wd, GitHub #62), where `<name>` is the nearest ancestor bookmark and `+1` the number of changes since it; label is blank only when @ has no bookmark anywhere in its ancestry
- [ ] Two bookmarks equally close to @ (e.g. either side of a merge) — label lists both names, comma-separated, before the shared `+N`
- [ ] Label updates reactively: run `jj bookmark create foo` in the terminal — label changes to "foo" within ~300 ms, without saving a file or restarting (see MT-LOG-REFRESH); `jj new` afterwards updates it to "foo +1" the same way
- [ ] Click the widget — dropdown opens with "Create Bookmark Here…", then "Advance Bookmark Here" at the top
- [ ] Dropdown lists all local bookmarks in the repo (not just those on @, and including bookmarks beyond the log limit), each as a sub-menu
- [ ] For a bookmark **on @**: sub-menu contains Advance, Rename…, Delete, Forget (no Move Here)
- [ ] For a bookmark **not on @**: sub-menu contains Move…, Advance, Rename…, Delete, Forget
- [ ] Remote bookmarks (e.g. `master@origin`) are folded into the corresponding local bookmark's sub-menu as Track/Untrack, not shown as separate top-level items
- [ ] "Create Bookmark Here…" (enter name → confirm) creates the bookmark at @, label and log
      decorations update; Rename… renames it in log and label; Delete removes it (label
      reverts to blank if it was on @); Forget (remote entry) removes remote tracking
- [ ] jj-idea-rskx: Create/Delete/Forget context-menu items show distinct bookmark-pennant
      icons (plus/cross/minus overlay), not the platform's generic +/trash/- icons
- [ ] jj-idea-rskx: a bookmark deleted locally but not yet pushed (`jj bookmark delete X`)
      shows a dashed/hollow pennant in the log chip (in addition to strikethrough text); a
      conflicted bookmark (e.g. after a divergent fetch) shows a red forked-tail pennant
      instead of the generic red warning triangle. Both remain legible at 125%/150% IDE
      text size and in both Light and Dark themes
- [ ] The log window's filter row (Root/Reference/Author/Date) no longer shows a Bookmark chip

#### Multi-repo project

- [ ] Bookmark widget is present in the main toolbar (not hidden)
- [ ] Label is blank regardless of which bookmarks exist (the "name +N" fallback only applies to a single-repo project — see jj-idea-1ra9 for the wrong-repo-ancestry bug this must not repeat)
- [ ] Click the widget — dropdown shows one sub-menu **per repo**, named by repo display name
- [ ] Each repo sub-menu contains the same structure as the single-repo dropdown: "Create Bookmark Here…", then "Advance Bookmark Here", then the repo's bookmark sub-menus
- [ ] "Create Bookmark Here…" inside repo-a's sub-menu creates a bookmark at **repo-a's** working copy, not repo-b's (check via `jj bookmark list` in each repo)
- [ ] `jj new` past every bookmark in repo-a only (repo-b still has one on @) — repo-a's "Advance Bookmark Here" is enabled and targets repo-a's nearest bookmark; repo-b's advances repo-b's bookmark, unaffected by repo-a
- [ ] Rename/Delete/Forget in repo-b's sub-menu affects only repo-b

#### Status-bar fallback (jj-idea-cpno)

**Code:** `ui/statusbar/JujutsuBookmarkStatusBarWidget.kt`, `JujutsuBookmarkStatusBarWidgetFactory.kt`

- [ ] New UI, Settings → Appearance & Behavior → uncheck "Show main toolbar" → the main-toolbar
      bookmark widget disappears and an equivalent bookmark widget appears in the status bar
      (bottom of the IDE window), live, without restarting; re-check the setting → reverses
- [ ] Switch to Classic UI (no main toolbar exists at all) → the bookmark status-bar widget is
      present
- [ ] The status-bar widget shows the same text as the main-toolbar widget would (bookmark on @,
      or nearest-ancestor "name +N"), and clicking it opens the identical dropdown (Create,
      Advance, per-bookmark Move/Rename/Delete/Forget/Track)
- [ ] With the main toolbar visible (New UI default), the bookmark status-bar widget is **not**
      shown — only the main-toolbar widget is
- [ ] Non-jj project: neither the main-toolbar widget nor the status-bar fallback appears

#### Advance Bookmark (jj-idea-l7wd, GitHub #61)

- [ ] With exactly one bookmark closest to @: clicking "Advance Bookmark Here" moves it directly to @, no dialog — confirm via `jj bookmark list` or the updated log decoration
- [ ] With two+ equidistant closest bookmarks (e.g. `jj new` off a merge of two bookmarked branches): clicking "Advance Bookmark Here" opens a picker dialog listing all of them, pre-checked; unchecking one and confirming advances only the checked ones
- [ ] The per-bookmark "Advance … to Working Copy" action (in a bookmark's own sub-menu, or via right-click on its chip in the log) moves that specific bookmark to @ regardless of distance, without opening a picker
- [ ] Advancing a bookmark that's already at @ is a no-op (no error)
- [ ] With no bookmark anywhere in @'s ancestry: "Advance Bookmark Here" is visible but disabled, with a tooltip explaining there's nothing to advance
- [ ] **Version gating**: with a jj executable below 0.39 configured (Settings → Version Control → Jujutsu → jj executable path), both "Advance Bookmark Here" and the per-bookmark Advance action are visible but disabled, with a tooltip naming the required version and your current one, and Settings → Version Control → Jujutsu → Install/Upgrade shows the correct upgrade command for your detected install method
- [ ] The disabled reason is also appended to the menu item's own text, not just its tooltip (menus don't reliably show tooltips) — e.g. "Advance Bookmark Here (needs jj 0.39+)" or "Advance 'main' to Working Copy (needs jj 0.39+)"; with no bookmark anywhere in @'s ancestry, "Advance Bookmark Here (nothing to advance)"
- [ ] jj-idea-xsa8 (GitHub #61): the same "Advance Bookmark Here" action is also available as an
      icon button in the Working Copy tool window's toolbar, alongside New Change/Split/Squash/
      Abandon/Create Bookmark/Set Tag (see MT-WORKINGCOPY) — clicking it there behaves identically
      to the bookmark widget's menu item, including the picker for equidistant bookmarks and
      version gating; switching the bound repository via the panel's dropdown in a multi-root
      project re-evaluates the button against the newly selected repo
- [ ] jj-idea-xsa8 follow-up: the tooltip names the actual bookmark(s) it would move rather than
      generic wording — "Move 'main' forward to the working copy (jj bookmark advance)" for one
      candidate, "Move 'main', 'feature' forward…" (each name individually quoted) for two+
      equidistant ones — both from the bookmark widget's menu item and the Working Copy toolbar
      button
- [ ] jj-idea-xsa8 follow-up: after a successful advance — **both** the direct single-bookmark
      path and after confirming the equidistant-candidates picker — a balloon notification
      appears: "Bookmark Advanced" / "Advanced '\<name\>' to \<shortid\>", where \<shortid\>
      matches `jj log -r @`'s change id. This is deliberately the *only* one of the Working Copy
      toolbar's actions with this treatment (see MT-WORKINGCOPY) — advancing is the only one with
      no dialog on its common path and no other visible effect in that panel
- [ ] jj-idea-xsa8 follow-up: clicking the Working Copy toolbar's Advance button with exactly one
      nearest bookmark shows a "Advance Bookmark" Yes/No confirmation naming the bookmark before
      moving it — Yes advances (and still shows the completion notification above), No/Escape
      leaves the bookmark untouched. This confirmation is specific to the toolbar's icon button —
      clicking the same "Advance Bookmark Here" entry from the bookmark widget's dropdown menu or
      the log's right-click menu still advances immediately with **no** confirmation, since those
      are more deliberate two-step clicks than an icon-only toolbar button. The equidistant-
      candidates picker dialog (multiple close bookmarks) is unaffected either way — it already
      served as its own confirmation before this change

#### Move direction (forward vs. backward/sideways)

Covers `actions/bookmark/MoveBookmarkDialog.kt`, `MoveBookmarkToChangeDialog.kt`,
`BookmarkClassifier.kt`. jj-idea-tvch: in a repo with any divergent change, every move used to
be misclassified as backward/sideways.

- [ ] Right-click a commit that is a **descendant** of an existing bookmark → "Move Bookmark
      Here…" → the bookmark row shows the forward (move-up) icon at full opacity, is selectable,
      and OK enables **without** ticking "Allow backward or sideways move"
- [ ] Right-click an **ancestor** of the bookmark instead → the bookmark row is greyed out with
      the warning icon and is only selectable after ticking the checkbox; confirming without the
      checkbox is impossible (OK stays disabled)
- [ ] Right-click a bookmark → "Move '\<bookmark\>' to Change…" → descendants of the bookmark's
      current position show as forward/selectable; ancestors are greyed with the warning icon
      until the checkbox is ticked
- [ ] In a repo containing a divergent change (`jj log` shows `(divergent)` on some commit): the
      forward/backward classification above still works for bookmarks unrelated to the divergent
      change — it doesn't blank out to "everything backward" the way it did before jj-idea-tvch
- [ ] Confirming a forward move without ticking the checkbox actually runs `jj bookmark set`
      without `-B` (check via `jj op log` or that the bookmark moved) — no unexpected
      "backwards or sideways" retry prompt

#### Per-bookmark push (jj-idea-t29z, GitHub #81)

`pushBookmarkAction` opens the same Push dialog as MT-GIT, pre-selected to "Specific bookmark"
scope with this bookmark (and the chosen remote) already selected — skipping the repo/remote/
bookmark selection clicks a fresh dialog needs, while still requiring an OK click before
anything is pushed (pushing mutates a shared remote, so this is deliberately not a one-click
fire-and-forget action). Reachable from every surface that shows a bookmark's other actions
(Rename…, Delete, Forget): the bookmark's own sub-menu in this widget, the log row's Bookmark
submenu, and a right-click on the bookmark's chip in the log.

Whether there's anything to push is evaluated **per remote**, using that remote's own
`name@remote` tracking entry — deliberately not the local bookmark's own aggregate ahead/behind
count, which in a **colocated** repo (this plugin always colocates) is always `0` because jj
auto-tracks a same-commit `@git` remote alongside any real ones. Getting this wrong makes every
bookmark look permanently up to date; the checks below exist specifically to catch that.

Setup: a repository with a local bookmark tracked against exactly one Git remote, and — for the
multi-remote cases — a second Git remote configured (`jj git remote add <name> <url>`). The
`/tmp/jj-idea-push-test` scratch repo (see jj-idea-ehki's fix) already has all three states set
up: `main` (tracked, in sync on both remotes), `feature` (pending deletion, still present on
both remotes), `new-thing` (never tracked anywhere).

- [ ] Right-click a bookmark's chip in the log (or open its sub-menu from this widget) → a Push
  entry appears alongside Rename…/Delete/Forget, with the same push icon as the toolbar Push
  action
- [ ] With exactly one Git remote: a single "Push '\<name\>' to \<remote\>..." entry, not a
  submenu; clicking it opens the Push dialog with "Specific bookmark" scope, this bookmark, and
  this remote already selected — confirming with OK is enough
- [ ] With two or more Git remotes: the entry becomes a "Push '\<name\>' to ▸" submenu, one item
  per remote, each independently labelled/enabled (see below) — clicking one opens the dialog
  pre-selected to that specific remote
- [ ] A bookmark that's up to date on `origin` (`main@origin` at the same commit as local `main`)
  shows that remote's entry **disabled**, with "(up to date)" appended to the text (a disabled
  item's tooltip alone is easy to miss in a menu) — **even though** the same bookmark is also
  tracked by the automatic colocated `@git` remote at the same commit as local (this is the
  actual regression case: confirm it stays disabled, not "always enabled because @git matches")
- [ ] The same bookmark, if ahead on a second remote (`main@github` behind local `main`), shows
  **that** remote's entry enabled while `origin`'s stays disabled — the two are evaluated
  independently
- [ ] A bookmark that has never been tracked/pushed to a given remote still shows an **enabled**
  entry for it despite having nothing to compare against yet — opening it and confirming creates
  it on that remote (with the usual "will create a new remote bookmark" confirmation from MT-GIT)
- [ ] Cancelling the pre-populated dialog performs no push
- [ ] (jj-idea-ehki) On a pending-deletion bookmark (`jj bookmark delete <name>`, still shown
  strikethrough in the log) that's still present on a remote: that remote's entry is **enabled**
  (deletions don't show up in `aheadCount`) and opens the dialog with `<name> (deleted)`
  pre-selected; confirming runs the same "will be deleted from the remote" warning as MT-GIT,
  then removes the bookmark from that remote. A remote the deletion has already been pushed to
  (or that never had the bookmark) shows its entry **disabled**

#### Push to all tracking remotes (jj-idea-ndzp)

With two or more Git remotes, the submenu above gains a leading "Push '\<name\>' to all remotes"
entry plus a separator, ahead of the per-remote entries. Unlike the per-remote entries, this one
skips the Push dialog entirely — it goes straight to a dry-run push per remote that has
something to push, so the usual force-push/deletion/untracked-bookmark confirmations still fire,
one per affected remote.

- [ ] With exactly one Git remote, no "all remotes" entry appears — just the single per-remote
  entry as before
- [ ] With two or more remotes, "Push '\<name\>' to all remotes" appears first, followed by a
  separator, then the per-remote entries unchanged
- [ ] A bookmark up to date on every remote shows the "all remotes" entry **disabled**, with
  "(up to date)" appended
- [ ] A bookmark ahead on only one of several remotes shows the entry **enabled**; clicking it
  pushes only that remote (verify via `jj git remote list`/the remote's log) — the up-to-date
  remote is silently skipped, not redundantly pushed
- [ ] A bookmark moved backwards/sideways on a remote it would push to: clicking "all remotes"
  still shows the same force-push confirmation as a per-remote push, scoped to that one remote

#### Bookmarks panel (jj-idea-b2ae, GitHub #48)

**Code:** `ui/log/bookmarks/JujutsuBookmarksPanel.kt`, `ui/log/bookmarks/BookmarkTreeModel.kt`, `ui/log/bookmarks/BookmarksStripeButton.kt`, `actions/bookmark/bookmarkLogActions.kt`, `ui/common/CommitTablePanel.kt` (`installLeftComponent`)

A tree of bookmarks/tags to the left of the log table, in the Jujutsu log tab — modelled on
git4idea's Branches dashboard. Expanded by default (matching the root gutter's default). A
narrow always-visible strip with a bookmark icon sits at the far left, outside the panel's own
splitter — clicking it toggles the panel even while collapsed, so there's always something on
screen to bring it back; the same toggle also lives in the toolbar's View Options popup. Plain
selection does nothing; right-click for actions.

- [ ] Panel starts expanded on a fresh log tab
- [ ] Clicking the bookmark-icon strip at the far left collapses the panel; the strip itself
  stays visible (tooltip switches to "Expand Bookmarks Panel") and clicking it again re-expands
- [ ] "Show Bookmarks Panel" in the toolbar's View Options popup (below the Details Position
  toggles) reflects and controls the same state as the strip
- [ ] With bookmarks `feature/A`, `feature/B`, `fix/C`: a "Local" group contains a `feature`
  group (with `A`, `B` underneath) and `fix` (with `C` underneath) — not three flat top-level
  entries
- [ ] A tracked `main@origin` appears under an `origin` group, itself `/`-grouped the same way
- [ ] Tags appear under their own "Tags" group, also `/`-grouped
- [ ] An "@" node at the top shows the same text as the main-toolbar bookmark widget (e.g. "main"
  or "main +3") — create/delete a bookmark and confirm both update together
- [ ] The "@" node's label is bookmark-coloured, followed by a bold "@" glyph in the log's own
  working-copy colour (same colour as the "@" the log table appends after a working-copy row's
  bookmarks/tags — compare side by side)
- [ ] Local/remote bookmark leaves, and their "Local"/remote-name folder groups, render in the
  same brownish colour as bookmark chips in the log table
- [ ] Tag leaves, and the "Tags" folder group, render in the same greenish colour as tag chips in
  the log table
- [ ] A bookmark sitting on `@` renders **bold** (still bookmark-coloured) in the tree
- [ ] Right-click a local bookmark → same actions as its dropdown sub-menu (Move…/Advance/
  Rename…/Delete/Forget, minus Move… when it's on @), plus "Filter Log to Bookmark" and
  "Navigate Log to Bookmark" at the bottom
- [ ] "Filter Log to Bookmark" narrows the log the same way the reference filter does
- [ ] "Navigate Log to Bookmark" scrolls/selects that bookmark's change, including one outside
  the currently loaded log window (triggers an expanding load, same as clicking a bookmark chip)
- [ ] Right-click a remote bookmark → Track/Untrack, plus Filter/Navigate
- [ ] Right-click a tag → Delete, plus Navigate (no Filter — tags aren't a log filter reference
  here)
- [ ] Right-click the "@" node → Create Bookmark Here…, Advance Bookmark Here
- [ ] With an issue-tracker pattern configured (Settings → Version Control → Issue Navigation) and
  a bookmark named e.g. `JIRA-123-fix-thing`: the `JIRA-123` portion of its label renders as a
  link (same styling as the log table/description) while the rest of the name doesn't; hovering it
  shows a hand cursor and clicking opens the configured issue URL in a browser. Same for a tag
  named the same way, and for the "@" node when the bookmark on `@` has such a name
- [ ] Clicking elsewhere on a linked bookmark's row (its icon, or non-linked text) does not open a
  browser — only the linked portion is clickable
- [ ] Renaming/creating/deleting a bookmark in the terminal updates the tree reactively, without
  a manual refresh (same auto-refresh path as MT-LOG-REFRESH)
- [ ] Multi-repo project: one top-level group per repository (with its icon), each containing its
  own Local/remote/Tags structure; single-repo project has no such wrapper level
- [ ] Type while the tree has focus — speed search jumps to/filters matching bookmark names
- [ ] Restart the IDE — the panel's expanded/collapsed state is restored per log window

### MT-WORKINGCOPY

**Working copy panel, status bar widget, and tool window behavior**

**Code:** `ui/workingcopy/UnifiedWorkingCopyPanel.kt`, `ui/workingcopy/WorkingCopyControlsPanel.kt`, `ui/workingcopy/WorkingCopyToolWindowFactory.kt`, `ui/statusbar/JujutsuStatusBarWidget.kt`, `ui/statusbar/JujutsuWorkingCopySwitcher.kt`, `ui/services/ToolWindowEnabler.kt`, `ui/services/WorkingCopySignpost.kt`, `ui/services/SponsorAsk.kt`, `ui/services/JujutsuStartupActivity.kt`, `vcs/JujutsuHiddenCommitMode.kt` (Standard Commit Tool Window Suppression), `vcs/JujutsuVcsBase.kt`, `actions/top/InitAction.kt`, `ui/common/JujutsuChangesTree.kt`, `ui/common/JujutsuOtherRepositoriesNode.kt`, `ui/common/JujutsuNoChangesNode.kt` (repo-anchoring, jj-idea-xsa8 follow-up)
**Also re-run:** MT-DIFF-PREVIEW (changed-files tree shares the preview-tab behavior); MT-CROSS (colocated Git / multi-VCS project scoping); MT-CTXMENU, MT-SQUASH, MT-SPLIT (Split/Squash/Abandon/Create Bookmark/Advance Bookmark/Set Tag are shared with the log context menu); MT-BOOKMARK (Advance Bookmark)

#### Working Copy Panel

- [ ] Description text area shows current description
- [ ] jj-idea-qa8i: clicking into the description text area, typing, and pressing Enter inserts
  a newline (does not do nothing or trigger another action)
- [ ] jj-idea-n3w1 (GitHub #46): the description field is a real commit-message editor, not a
  plain text area - typing a long first line highlights the subject-length inspection, a
  misspelled word gets a spellcheck squiggle, and the toolbar's history button (Ctrl+E / Cmd+E)
  opens a popup of recently-used descriptions (shared with Git's own commit UI and every other
  description editor in this plugin)
- [ ] With IdeaVim installed: describe the working copy, enter Insert mode in the description
  field, then press Escape - it should leave Insert mode, not close/cancel anything (this was the
  requester's headline ask on GitHub #46; if Escape does something else, note what and whether
  `:set ideavimsupport=dialog` changes it)
- [ ] jj-idea-n553 (GitHub #15): with an Issue Navigation pattern configured (Settings → Version
  Control → Issue Navigation, e.g. issue `[A-Z]+-\d+` → link `https://example.com/browse/$0`),
  an issue reference like `JIRA-123` inside a bookmark name shown in the current-change summary
  (above the description area) renders as a clickable link, opening the configured URL on click
- [ ] "Describe" button updates description via `jj describe`
- [ ] "New Change" button creates new change via `jj new`
- [ ] jj-idea-xsa8 (GitHub #61): the toolbar row also offers Split, Squash, Abandon, a separator,
  then Create Bookmark, Advance Bookmark, and Set Tag — all acting on `@`, all icon-only
  (tooltip-only labels) — **verify every one of them is actually visible in the row, not
  collapsed behind an overflow `>>` chevron or silently missing**; each behaves identically to
  its log context-menu counterpart (see MT-CTXMENU, MT-SQUASH, MT-SPLIT, MT-BOOKMARK) and greys
  out (never disappears) when not applicable to the current `@` — e.g. Squash with no mutable
  parent, Advance with no ancestor bookmark
- [ ] Multi-root: switching the bound repository via the panel's dropdown re-evaluates every one
  of these buttons against the newly selected repo
- [ ] jj-idea-xsa8 follow-up: in a multi-root project, the repo selector dropdown sits in the same
  row as this toolbar (top of the tool window), not ~60% down inside the description area where
  it used to be — the two are visually adjacent, and choosing a different repo there immediately
  updates both the toolbar buttons and the description/current-change area below
- [ ] Changed files tree shows correct status colors and file type icons
- [ ] Preview-tab behavior (double-click, Enter, tab-swap, single-click-no-op-when-closed,
      single-click-swap-when-open, Escape, Cmd/Ctrl+D, F4): see MT-DIFF-PREVIEW
- [ ] Right-click shows context menu with file actions
- [ ] jj-idea-lo7u: "Compare Before with Another Commit..." is **not** in that menu (working
      copy context — same as "Compare Before with Local")
- [ ] Select one or more files in the changed-files tree → the IDE's **Reformat Code**
      (Ctrl/Cmd+Alt+L) and **Optimize Imports** (Ctrl/Cmd+Alt+O) both act on the selected file(s),
      same as the built-in Git/Commit changes view (`JujutsuChangesTree.showsLocalFiles`)
- [ ] Right-click a file → Jujutsu submenu → **Annotate** opens the gutter annotations for that
      file (jj-idea-0t5o)
- [ ] Open shows working copy as editable
- [ ] Open for multiple files opens multiple editors
- [ ] Menu has Open in -> remote; see MT-DIFF for "Open in -> remote for single parent" and the hidden-when-no-pushed-ancestor case
- [ ] jj-idea-t0zo: in a repository that is colocated with Git (mapped to both Jujutsu and
  Git4Idea), the Working Copy panel's changed-files tree shows only jj's changes — no
  Git-only changes appear. Also verify "Resolve Conflicts…" (both the toolbar/menu action
  and the per-file context menu action) only offers jj-tracked conflicted files
- [ ] jj-idea-mdi4: in a repository mapped to a non-jj VCS (e.g. Git4Idea, colocated or
  otherwise), select a change belonging to that other VCS in the Changes view and press F4
  / open the context menu — no `VcsException: Not a Jujutsu revision` appears in the IDE
  log, and jj's file-change actions (Open, Compare, Restore, etc.) simply don't offer that
  change

#### Changes tree repo-anchoring (jj-idea-xsa8 follow-up, multi-repo only)

Covers `ui/common/JujutsuChangesTree.kt`'s `currentRepo`, `JujutsuOtherRepositoriesNode`,
`JujutsuOtherRepositoryNode`, and `JujutsuNoChangesNode`: the changes tree spans every repo,
unlike the toolbar/description above it (which only ever act on the bound repo), so changes
belonging to any *other* repo are demoted into one collapsed node rather than getting an
equally-weighted group of their own.

- [ ] With a repo bound (via the relocated selector) that **has** changes: that repo's changed
  files show directly at the top level of the tree (still grouped by directory as normal) — **not**
  wrapped in their own repo-named node the way they were before this feature
- [ ] With a repo bound that has **no** changes (e.g. a fresh/clean `@`): the tree still shows a
  line for it — the repo's name followed by a grey "(no changes)" qualifier — rather than showing
  nothing at all for the bound repo (easy to misread as broken, especially when "Other
  Repositories" below it is populated)
- [ ] Every *other* repo's changed files are collapsed under a single "Other Repositories" node,
  sorted below the bound repo's own files/no-changes line, collapsed by default (not expanded) the
  first time it appears
- [ ] Expand "Other Repositories" with changes from **two or more** other repos present: each
  other repo appears as its **own named sub-node**, with the same per-repo colored icon used
  elsewhere (bookmark widget's multi-repo dropdown, the "(no changes)" line), directly followed
  by its changed files — **no** extra node in between for a shared parent directory (e.g. the
  folder containing several sibling repos on disk) or for the repo itself appearing twice nested
- [ ] A file inside a subdirectory of an other-repo (e.g. `src/Main.kt`) shows as just its
  filename ("Main.kt") directly under that repo's node, **not** nested under its own directory
  sub-tree the way the bound repo's own files are, and **not** showing any path (relative or
  absolute) as part of its label — this is a deliberate trade-off (see jj-idea-xsa8) for a
  demoted, secondary area of the tree; only the bound repo's own top-level content gets full
  directory grouping
- [ ] Switch the bound repo via the selector — the split re-partitions immediately (files move
  between the plain top level / "Other Repositories", the no-changes line appears or disappears as
  appropriate), no need to touch Refresh
- [ ] Single-repo project: no "Other Repositories" node ever appears, regardless of how many
  changed files exist — behavior is unchanged from before this feature; the no-changes line is
  also unaffected (single-repo behavior is untouched by `currentRepo`, since it was already the
  only repo shown)
- [ ] With conflicts present (`groupConflicts`): the Merge Conflicts node (top, bold) and the
  Other Repositories node (bottom, sorted after the bound repo's own content) coexist correctly —
  a conflicted file in another repo appears under Merge Conflicts, not duplicated under Other
  Repositories

#### Repository Initialization (jj-idea-uw11)

- [ ] With a directory that is VCS-mapped to Jujutsu but has no `.jj` (shows the
  "uninitialized root" notification), click **Initialize** on the notification, pick the
  directory, confirm → the Working copy tool window populates with the repo's changes
  immediately, without needing any further unrelated action (e.g. no `jj split` first)
- [ ] Same check using **VCS → Jujutsu → Initialize** (top-level menu action) directly on an
  already-mapped-but-uninitialized directory, instead of the notification's button
- [ ] The Log tool window also shows the initial commit(s) immediately after Initialize
- [ ] After Initialize, add/edit a file in the new repo — the editor gutter and Project view
  show the correct added/modified colour immediately, without needing to touch Settings,
  restart, or trigger an unrelated VCS refresh first

#### Unreadable Repository (jj-idea-9ife)

- [ ] With a working jj repo open (tool window populated), break its store on disk
  while the IDE is running — e.g. `rm -rf .jj/repo/store` (leaves `.jj` present, so it still
  passes the "is this a jj repo" check, but `jj log` fails) — then trigger a VCS re-scan
  (e.g. touch a file, or reopen the project)
- [ ] **Expected:** no red "IDE Internal Error" balloon; instead, a single WARNING
  notification "Jujutsu Repository Could Not Be Read" appears, including jj's error detail,
  and **Retry** and **Configure VCS Mappings…** actions
- [ ] The Working copy tool window's empty state shows **"Jujutsu could not read the
  repository '\<name\>'..."**, not the generic "No Jujutsu repositories configured", with its
  own **Retry** link
- [ ] Settings → Version Control → Directory Mappings shows the broken repo's row in red
  (same treatment as an otherwise-invalid mapping)
- [ ] Trigger another refresh (e.g. edit a file) — the toast notification does not repeat,
  but the tool window's empty state and the red mapping row persist (they're not one-shot)
- [ ] Click **Retry** on the notification, or on the tool window's empty-state link — it
  re-checks immediately; while still broken, the same messages reappear (a fresh notification
  can fire again since Retry re-arms it)
- [ ] Restore the store (e.g. `jj git init --colocate .` again) **without** touching any
  other file — within ~1s the tool window, Log, and the Directory Mappings row all recover on
  their own, with no manual Retry/re-scan needed (the plugin watches the repo's `.jj/repo/`
  directory for exactly this)
- [ ] In a multi-repo project, break only one repo's store — the other repo's Working
  copy/Log data is unaffected, and the tool window's empty-state message only mentions the
  broken repo when *all* repos are unreadable (with more than one repo readable, the broken
  one is just silently absent from the dropdown, matching existing uninitialized-repo
  behavior)
- [ ] Break two repos' stores in the same project — the notification message pluralizes
  ("N Jujutsu repositories could not be read")

#### Standard Commit Tool Window Suppression (jj-idea-wb5l)

- [ ] In a **jj-only** project (default setting), the standard **Commit** tool window and
  the **Local Changes** tab are not shown; the **Working copy** tool window is the only
  changes UI. Ctrl/Cmd+K still opens Describe (unchanged)
- [ ] Settings → Version Control → Jujutsu → uncheck "Hide the standard Commit tool window"
  → the Commit tool window / Local Changes tab reappears immediately, without reopening the
  project. Re-check it → it disappears again
- [ ] With the setting on: editor-tab and Project-view file colors for added/modified files
  still render; editor gutter change markers still show; Annotate still works; the Working
  copy panel's changed-files list still updates live as files change
- [ ] In a **mixed jj + Git** project (both VCSes mapped), the standard Commit tool window
  is still shown regardless of the setting (jj is not the single active VCS, so the setting
  has no effect) — Git's own commit workflow is unaffected

#### Working Copy Tool Window Signpost (jj-idea-jqpe)

- [ ] With a fresh sandbox config (no prior `jujutsu.xml` app or project settings), open a
  jj project → the **Working copy** tool window opens on the left automatically, but keyboard
  focus stays wherever it was (e.g. the editor) — it doesn't steal focus
- [ ] A sticky balloon notification appears explaining the Working copy panel and its
  "Open Working Copy" action; clicking it activates and focuses the tool window
- [ ] Close and reopen the same project → the tool window is not force-reopened again and no
  balloon appears (both are one-shot per project/install)
- [ ] Open a **second, different** jj project in the same sandbox → the tool window opens
  automatically again (per-project), but no balloon appears (per-install, already shown)
- [ ] Open a non-jj project → neither the tool window nor the balloon appear

#### Sponsor Ask (jj-idea-z1ld)

- [ ] Fresh sandbox config, open a jj project → no sponsor balloon on first run
- [ ] Quit, backdate `firstRunEpochMillis` in the sandbox `jujutsu.xml` (under
  `JujutsuApplicationSettings`) to more than 14 days ago, restart → a sticky balloon fires
  once; clicking **Sponsor** opens `https://github.com/sponsors/kkkev` in the default browser
- [ ] Restart again → the balloon does not reappear
- [ ] Repeat the backdate in a fresh sandbox, click **Don't show again** instead → restart →
  the balloon never reappears
- [ ] The Working Copy signpost above still fires independently and is unaffected by the
  sponsor ask (both read/write disjoint fields in `JujutsuApplicationSettingsState`)

#### Status Bar Widget (Switch Working Copy)

- [ ] jj-idea-fmrj: click the Jujutsu widget in the IDE status bar to open the "Switch Working
  Copy" popup, in a repo with at least one local bookmark and one tag
- [ ] Hover a bookmark or tag row — tooltip shows the bookmark/tag chip (icon + name, correct
  accent color) followed by `(changeid)`, with **no broken-image glyph**
- [ ] Hover a change row — tooltip shows change id, commit id, author (as a mailto link),
  timestamp and description, each on its own line
- [ ] Move the pointer between rows without leaving the list — tooltip content updates to the
  newly hovered row
- [ ] jj-idea-wp12: hover a row with enough entries that the list scrolls, then scroll it
  (mouse wheel or scrollbar) without moving the pointer — the tooltip disappears and does not
  reappear until the pointer moves
- [ ] Regression: hover a commit row in the Jujutsu log — its tooltip still renders bookmark/tag
  chips correctly and still reflows/scrolls for a commit with many bookmarks (jj-idea-szn8)

### MT-DIFF

**Diff viewing across file surfaces**

**Code:** `vcs/diff/JujutsuDiffProvider.kt`, `actions/filechange/`, `vcs/annotate/`, `vcs/history/JujutsuHistoryProvider.kt`, `actions/file/OpenInRemoteFromEditorGroup.kt`, `actions/filechange/OpenFileInRemoteGroup.kt`
**Also re-run:** MT-DIFF-PREVIEW; MT-DIFFBASE (a configured diff base changes what `Annotate`
below annotates against); see [Known gaps](#known-gaps) for jj-idea-7d9p/zvzk, which recur across every surface in this section

#### Diffs

- [ ] Show diff for a directory with changed files shows diffs for each file in the directory
- [ ] Show diff for a directory with no changed files does nothing (no crash)
- [ ] Diff for unchanged file shows no changes (before view has same content, shows content as identical)
- [ ] Diff for a single-parent file shows the correct before/current pairing for each change
      type — verify in Project Tool Window, Editors for Current Files, and Editors for
      Historical Versions too, the assertion is identical across all four: modified
      (before=parent, current=selected), deleted (before=parent, current=empty), added
      (before=empty, current=@), renamed (before=@- with previous filepath, current=@)
- [ ] Diff from working copy shows before = parent, current from working copy
- [ ] jj-idea-zmse: right-clicking inside a diff viewer's editor pane shows "Annotate" exactly
      once (not once at top level and again under "Jujutsu")
- [ ] Right-hand diff pane is editable when it contains the working copy, read-only when
      it contains a historical version
- [ ] "Open in -> remote": for a single parent, opens that parent (resolves to pushed
      ancestor); hidden when no pushed ancestor exists; for an unpushed historical version,
      resolves to the nearest pushed ancestor — verify in Working Copy Panel, Project Tool
      Window, Editors for Current Files, and Editors for Historical Versions
- [ ] jj-idea-c4tp: right-click a file (editor or Working Copy panel) to open "Open in Remote"
      immediately after opening the project (first menu open, cold cache) — submenu still lists
      remotes correctly, with no `Synchronous execution under ReadAction` warning in Help → Show
      Log

#### Project Tool Window

- [ ] File in tool window has a Jujutsu menu with "Show Diff" and "Compare with Another Commit..."
- [ ] jj-idea-lo7u: menu also has "Compare Before with Another Commit..." for a historical
      selection; hidden for the working-copy entry and for a root commit
- [ ] Show diff for multiple files opens multiple editors
- [ ] Menu has Open in -> remote (see Diffs above for the single-parent/no-ancestor cases)

#### Editors for Current Files

- [ ] Jujutsu menu has "Show Diff", "Compare with Another Commit", and "Annotate"
- [ ] Annotate fetches annotations for the correct revision
- [ ] "Annotate Previous Revision" on a line owned by a single-parent commit re-annotates at that commit's parent
- [ ] "Annotate Previous Revision" on a line owned by a merge commit is unavailable/no-op (no incorrect ancestor shown)
- [ ] jj-idea-xssw: "Annotate Previous Revision" on a line whose own change added the file (no
      earlier version exists) declines gracefully — no raw error, no logged ReadAction-violation
      warning (`idea.log`/Help → Show Log)
- [ ] Annotate on a file whose working copy is a merge commit succeeds (no "resolved to more than one revision" error)
- [ ] Annotate on a merge commit with a resolved conflict shows no "line count" warning, and correctly attributes lines inherited from each parent plus the conflict-resolution line(s) to the merge commit itself
- [ ] Annotate on a merge commit where the file exists in only some parents (e.g. a criss-cross merge) succeeds (no "No such path" error), attributing blame from whichever parents have the file
- [ ] jj-idea-mn1a: with the IDE on a **light** theme, the change-id column in the annotation
      gutter is readable, not washed-out
- [ ] Has open in -> remote (see Diffs above)

#### Editors for Historical Versions

- [ ] Has title including change id, and a Jujutsu menu with diff and "compare with another commit"
- [ ] Compare with another commit opens that commit on LHS, editor's version on RHS
- [ ] jj-idea-lo7u: Jujutsu menu also has "Compare Before with Another Commit..."; it opens the
      parent of the editor's revision on LHS and the chosen commit on RHS; hidden for the
      working-copy entry and for a root commit
- [ ] jj-idea-hq4d: "Annotate" is enabled (both via right-click and the Jujutsu menu) for a file
      opened from a historical commit — it used to be greyed out
- [ ] Annotate fetches annotations for the correct revision
- [ ] Has open in -> remote (see Diffs above, including the unpushed-historical-version case)

#### File History

- [ ] jj-idea-hq4d: opening a file from the File History panel (right-click a file → "Show File
      History", pick an older revision, open the file) enables "Annotate"; it produces a blame
      gutter for that revision's content
- [ ] jj-idea-qrne: open a file's platform history tab (editor's Jujutsu submenu → "Show
      History") — Date, Author, and Committer columns are populated for every revision, not
      blank
- [ ] jj-idea-a1fh: in that same tab, click the "Commit Time" column header twice — rows
      order chronologically (oldest→newest, then newest→oldest), not alphabetically by
      committer name

### MT-DIFF-PREVIEW

**Diff preview-tab behavior**

**Code:** `ui/common/JujutsuEditorTabDiffPreview.kt`
**Referenced by:** MT-LOG-DETAILS (Details Changes Panel), MT-WORKINGCOPY (Working Copy Panel),
MT-DIFF

This is the canonical diff preview-tab check, deduplicated from the three surfaces above —
verify it once per surface it's referenced from, not three times independently:

- [ ] Double-click file opens diff in a single editor tab (preview tab)
- [ ] Enter on selected file opens the same diff tab
- [ ] Clicking a different file while the diff tab is open swaps its content; tab count stays at 1
- [ ] Single click does nothing if diff tab is not open
- [ ] Single click swaps diff content if diff tab is already open
- [ ] Escape inside the diff tab closes it
- [ ] Cmd/Ctrl+D opens the same diff preview tab (routes through preview when available)
- [ ] F4 still opens the file in a regular editor tab (no "Synchronous execution on EDT" error in IDE log)
- [ ] With the diff tab open and a different (regular) editor tab focused, edit and save that file — the editor stays on it; it does not switch to the diff tab (GitHub #67)
- [ ] jj-idea-q6vn: with the diff tab open on a long file, scrolled away from the top, edit and save a *different* tracked file from outside the IDE (e.g. a terminal) — once the background refresh lands, the diff stays scrolled where it was, it does not jump to the top
- [ ] jj-idea-q6vn / jj-idea-ouul (GitHub #67): with `@` selected and its diff tab open on the working-copy side, the right-hand pane title reads "Current" (not the change id) and is editable; on a long file, scroll well away from the top, then edit and save *that same file* (the one shown in the diff, not a different one) — content updates in place and scroll position is unchanged

### MT-DIFFBASE

**Custom diff base for gutter markers and Annotate (jj-idea-fwea, GitHub #43)**

**Code:** `vcs/diffbase/`, `settings/DiffbaseStrategy.kt`, `settings/JujutsuConfigurable.kt` (Diff Base group + per-repo override)
**Also re-run:** MT-DIFF (Annotate is the other consumer of the diff base); MT-SETTINGS (the settings group itself)

Requires a repo with at least one immutable ancestor and a few mutable commits above it (e.g.
`jj new trunk()` a couple of times) so "latest immutable ancestor" differs visibly from `@-`.

- [ ] Settings → Version Control → Jujutsu → **Diff Base** defaults to "Working copy parent
      (default)" on a fresh install; gutter markers and Annotate behave exactly as before this
      feature existed
- [ ] jj-idea-fwea: the "Working copy parent" and "Latest immutable ancestor" radio buttons show
      a "(?)" icon after the label — hovering (or focusing) it shows the underlying revset
      (`@-` / `latest(ancestors(@-) & immutable())`); the labels themselves stay short, no raw
      revset text visible without hovering
- [ ] Select "Latest immutable ancestor (trunk)", click Apply — without touching the editor,
      every open file's gutter markers expand to the full diff vs trunk (not just vs `@-`)
- [ ] Annotate the same file (Jujutsu → Annotate). **Alignment check:** every blame line lines
      up with the correct source line; lines changed anywhere in the stack read as
      unattributed, not shifted onto the wrong line — this is the bug this feature exists to
      prevent (gutter base and Annotate base must never disagree)
- [ ] Select "Custom revset", type `trunk()`, click **Test** — reports success per repo; gutter
      and Annotate match the "Latest immutable ancestor" case above
- [ ] Type an invalid revset (e.g. `zz(`) and click **Test** — the raw jj error wraps instead
      of widening the panel (same pattern as the Log revset's Test button)
- [ ] Type a revset that matches more than one revision (e.g. `heads(mutable())` in a repo with
      concurrent branches) and click **Test** — reports it resolves to N revisions, not one,
      as an error rather than success; Apply is blocked the same way an unresolvable revset is
- [ ] Leaving "Custom revset" selected with an empty field shows a validation error on Apply
- [ ] **Live update with an editor already open** (the scenario this feature exists to get
      right): with a file's Annotate gutter already showing (from a prior "Latest immutable
      ancestor" run), switch the setting back to "Working copy parent" and click Apply —
      *without* closing the editor or manually re-running Annotate, the gutter's blame updates
      in place to the new base and stays correctly aligned (no line showing the wrong change's
      author). Repeat switching between all three strategies with the gutter left open each
      time — each switch is reflected immediately, never requiring a close/reopen to correct
      itself
- [ ] Multi-repo project: set a per-repo override (Repository Settings → the repo's group →
      "Override diff base") on one repo only — confirm only that repo's files use the
      override, in both the gutter and Annotate; the other repo keeps using the project default
- [ ] jj-idea-fwea: with at least one repo (so the "Repository Settings" group renders — the
      automated `JujutsuConfigurablePanelTest` fixture has none and can't cover this), the
      per-repo "Override diff base" combo box shows short strategy names, not the raw revset
      text; a grey comment line below it explains what `@-` and "Latest immutable ancestor"
      resolve to. Opening Settings → Version Control → Jujutsu at its default size shows no
      horizontal scrollbar with this group expanded (see jj-idea-bwdk's width checklist above)
- [ ] Edge cases: a repo with no immutable ancestor falls back to `@-` (no error dialog, no
      crash); an ignored or unversioned file gets no gutter markers and no error; a file opened
      from the log or File History (a historical version) is unaffected; the **Local Changes** /
      **Working copy** panel keeps showing changes vs `@-` regardless of this setting

### MT-CONFLICT

**Conflict resolution**

**Code:** `jj/conflict/`, `vcs/merge/JujutsuConflictResolver.kt`, `vcs/merge/JujutsuMergeProvider.kt`, `ui/common/JujutsuConflictsNode.kt`, `actions/file/ResolveSelectedConflictsAction.kt`, `actions/file/ResolveAllConflictsAction.kt`, `actions/change/resolveConflictsAction.kt`, `actions/change/resolveConflictsAvailability.kt`
**Fixture:** FX-CONFLICT (content conflicts), FX-MD-CONFLICT (modify/delete conflicts)
**Also re-run:** MT-CROSS (multi-repo scoping)

#### Detection

- [ ] `file.txt` appears in the Working Copy panel with red (MERGED_WITH_CONFLICTS) status
- [ ] All three marker styles (git, snapshot, diff) correctly mark the file as conflicted

#### Modify/delete conflicts (jj-idea-x283)

Uses FX-MD-CONFLICT. Content conflicts (above) resolve to a merged text file either way; a
modify/delete conflict is different — one side deleted the file entirely, so "accept" on that
side must actually remove the file from disk, not leave an empty file behind.

- [ ] `a.txt` appears in the Working Copy panel as MERGED_WITH_CONFLICTS
- [ ] Opening the merge tool (via "Resolve Conflicts…") shows one pane empty (the deleted side)
- [ ] In the platform's native multi-file merge dialog, using its bulk **"Accept Yours"** (or
      **"Accept Theirs"**) button when the chosen side is the deletion **removes `a.txt` from
      disk** — confirm with `jj status` (shows `D a.txt`, no conflict), not an empty `a.txt`
- [ ] Using the jj-idea "Resolve Conflicts…" action's interactive merge tool, saving with an
      empty result pane (i.e. choosing the deleted side) also **deletes `a.txt`** rather than
      writing an empty file — confirm with `jj status`
- [ ] Saving a genuinely edited (non-empty) result through either path still writes that content
      normally
- [ ] Make the repo temporarily unwritable (e.g. `chmod -w .jj/working_copy` on the resolve
      target) and retry accept-yours/theirs from the native dialog: an **error notification**
      appears (no silent no-op)

#### Conflicts grouping node and "Resolve All Conflicts" toolbar button (GitHub #56, jj-idea-uoeg)

A reporter on GitHub #56 declined to use the Working Copy panel because, unlike the standard
Commit tool window's single "Merge Conflicts / Resolve" grouping, it required hunting for red
files one at a time. This adds an equivalent affordance directly to the Working Copy panel.

- [ ] With `file.txt` conflicted, a bold **"Merge Conflicts"** node appears at the **top** of the Working Copy changes tree, above the normal directory/repository grouping, showing a file count and a clickable **"Resolve"** link
- [ ] Clicking the node's "Resolve" link opens the merge tool for every file under that node, one after another
- [ ] Cancelling out of the merge tool from this entry point still **leaves conflict markers intact** (the GitHub #63 invariant — confirm with `jj status` after cancelling)
- [ ] After resolving the only conflicted file, the "Merge Conflicts" node **disappears** on the next automatic refresh, without pressing Refresh (exercises the same jj-idea-3cvb fix as the file-level action)
- [ ] Toggle **Group By → Directory / Repository / None** in the changes-tree toolbar: the "Merge Conflicts" node stays pinned at the top in all three modes, with the chosen grouping nested *inside* it
- [ ] Multi-repo project with conflicts in two jj roots: a single "Merge Conflicts" node contains both roots' conflicted files (grouped by repository underneath, if that grouping is active)
- [ ] Collapse the "Merge Conflicts" node, restart the IDE: it's still collapsed. Then create/resolve a conflict so the file count changes: it's **still collapsed** (the node's persisted collapse-state key must not embed the count)
- [ ] Right-click the "Merge Conflicts" node itself → "Resolve Conflicts…": acts on every conflicted file under it (same as clicking the inline "Resolve" link)
- [ ] The changes-tree toolbar has a **"Resolve All Conflicts…"** button, visible only when the working copy has at least one conflict
- [ ] Select a **non-conflicted** file in the tree, with `file.txt` still conflicted elsewhere: the toolbar button **stays visible and works** (it must not depend on tree selection — this is the specific regression the button's separate action implementation exists to prevent)
- [ ] Clicking the toolbar button resolves every conflicted file in the working copy, same as the node's link
- [ ] With no conflicts at all: neither the "Merge Conflicts" node nor the toolbar button appear
- [ ] Mixed jj + Git project: the node contains only jj conflicts, never Git-tracked conflicts from a co-located Git root

#### "Resolve Conflicts" context menu action (selection-scoped)

There is a single `Jujutsu.ResolveSelectedConflicts` action behind "Resolve Conflicts…";
it's wired into both the Working Copy panel / commit details pane's file context menu and
the Project view / editor "Jujutsu" submenu. It always resolves an explicit selection when
there is one, and otherwise falls back to the single focused file (editor/project view) or
every conflicted file inherited from the working copy's ancestors (see "Inherited conflicts"
below) — there is no separate "resolve every conflicted file regardless of context" action at
the file level (for that, see "Log row context menu" further down, which resolves every
conflicted file reachable from the working copy).

- [ ] Right-clicking a **non-conflicted** file in the Working Copy panel: "Resolve Conflicts…" is **not visible**, even when other, unrelated files elsewhere in the repo are conflicted
- [ ] Right-clicking `file.txt` (conflicted): "Resolve Conflicts…" **is visible**
- [ ] Invoking it opens the merge tool for **only** `file.txt`, not unrelated files
- [ ] Multi-select: selecting one conflicted + one non-conflicted file → only the conflicted file's merge tool opens
- [ ] Multi-select: selecting two conflicted files → both open in turn (second opens after the first is resolved)
- [ ] Multi-select: selecting two **non-conflicted** files (with some other file elsewhere in the repo conflicted): "Resolve Conflicts…" is **not visible**
- [ ] Right-clicking a **directory**, or the project root, in the Project view → Jujutsu submenu: "Resolve Conflicts…" is **not present** (no single file in scope to act on — there is no "resolve every conflicted file in the project" entry point at the file/project-view level; use the log row context menu for that, see below)
- [ ] Right-clicking `file.txt` itself in the Project view → Jujutsu → Resolve Conflicts…: opens the merge tool for `file.txt`
- [ ] Opening `file.txt` in the editor, right-clicking → Jujutsu → Resolve Conflicts…: opens the merge tool for **only** `file.txt` (scoped to the focused editor file, not every conflicted file)
- [ ] **On IntelliJ/RustRover 2026.2 (build 262) specifically**: triggering "Resolve Conflicts…" opens the merge tool at all (jj-idea-qfgl / GitHub #55 — this used to silently do nothing)

#### Three-way merge tool — content correctness

- [ ] Left pane ("Yours") shows "ours" content (`changed by A`, the rebased change)
- [ ] Right pane ("Theirs") shows "theirs" content (`changed by B`, the destination)
- [ ] Center pane is editable; initially shows a proposed merge result (not identical to left or right)
- [ ] Left and right panes are **not** identical — conflict regions are highlighted
- [ ] Works correctly for all three marker styles (git, snapshot, diff)

#### Resolving via the merge tool

- [ ] Edit the center pane to a desired resolution and click Apply Changes
- [ ] After closing the tool, `file.txt` content on disk reflects the resolution (no conflict markers)
- [ ] `file.txt` disappears from the Working Copy panel's conflict list **automatically**, without pressing Refresh (jj-idea-3cvb: a stale conflict decoration used to survive even a manual Refresh)
- [ ] Right-clicking `file.txt` again (now resolved): "Resolve Conflicts…" is **not visible**, and if triggered anyway does not throw

#### Editor notification banner (jj-idea-aunm, GitHub #56)

- [ ] Open `file.txt` in the editor: a warning-colored banner appears at the top with text like "This file has merge conflicts" and a **"Resolve"** action link
- [ ] Clicking "Resolve" opens the merge tool for `file.txt` (same three-way merge tool as the other entry points)
- [ ] Cancelling out of the merge tool from this entry point still **leaves conflict markers intact** (the GitHub #63 invariant)
- [ ] After resolving `file.txt` via the banner (or via any other entry point while the file is open in the editor), the banner **disappears automatically**, without switching tabs or reopening the file
- [ ] Open a **non-conflicted** jj-tracked file: no banner appears
- [ ] Mixed jj + Git project: opening a file with a **Git** conflict shows no jj banner (and vice versa)
- [ ] Open a conflicted file that is **outside** any jj repo (e.g. an unrelated Git-only root in a multi-root project): no jj banner appears

#### Cancelling must never discard a side (GitHub #63 — critical regression check)

- [ ] Open the merge tool for `file.txt` and close it via the window's `x` button **without** touching anything: `file.txt` **stays conflicted** — content on disk still has its original conflict markers, and it still shows red (MERGED_WITH_CONFLICTS) in the Working Copy panel
- [ ] Same, but click the **Cancel** button instead of `x`: same result — file stays fully conflicted
- [ ] Resolve only *some* of the conflict's hunks in the center pane, then close via `x` (don't click Apply): `file.txt` **stays fully conflicted** on disk (no partial write, original markers intact) — not partially resolved, not resolved-by-discarding
- [ ] Repeat all three checks above for each marker style (git, snapshot, diff)
- [ ] Multi-file: with two conflicted files, cancel the merge tool for the first → the second file's merge tool **never opens** and remains conflicted untouched

The native Commit tool window's own "Resolve" link is a known gap for this invariant — see
jj-idea-ddcd in [Known gaps](#known-gaps).

#### Accept Yours / Accept Theirs (in the merge tool)

- [ ] In the three-way merge tool, click **Accept Left** (yours): `file.txt` on disk contains "ours" content with no conflict markers
- [ ] `file.txt` leaves conflicted state automatically, without pressing Refresh
- [ ] **Accept Right** (theirs) analogously writes "theirs" content

#### Log details pane (commit selected in log table)

Use the same conflict setup above. The test repo has a conflicted commit that is **not** the working copy (e.g., run `jj new` to create an empty working copy on top of the conflicted change).

- [ ] Selecting the **conflicted historical commit** in the log: conflicted file appears in the details panel with red (MERGED_WITH_CONFLICTS) status
- [ ] Selecting the **conflicted historical commit**: "Resolve Conflicts… (edit this change first)" is **visible but disabled** in the details panel context menu (jj-idea-sm1s: resolution requires this commit to become the working copy first)
- [ ] Selecting the **working copy commit** (empty, inherits conflict): "Resolve Conflicts…" **is visible and enabled** in the details panel context menu and opens the merge tool for the inherited conflicted files

#### Log row context menu

- [ ] Right-clicking the **working copy entry** when conflicts exist: "Resolve Conflicts…" appears in the context menu, enabled
- [ ] Right-clicking the **working copy entry** when no conflicts exist: "Resolve Conflicts…" is **not visible** (hidden, not just disabled)
- [ ] Right-clicking a **non-conflicted, non-working-copy entry**: "Resolve Conflicts…" is **not visible**
- [ ] Right-clicking a **conflicted, non-working-copy entry** (jj-idea-sm1s — e.g. a merge commit, or a child that only *inherits* the conflict): "Resolve Conflicts… (edit this change first)" is **visible but disabled**, with a tooltip/description prompting the user to `jj edit` the change first
- [ ] Invoking "Resolve Conflicts…" from the log row context menu opens the merge tool for all conflicted files, one after another

#### Inherited conflicts (jj-idea-sm1s)

Extend the setup above: with the working copy on the conflicted commit, run `jj new` to create an empty child (the child now inherits the parent's unresolved conflict).

- [ ] Selecting the **conflicted merge/parent commit** in the log: "Resolve Conflicts… (edit this change first)" is visible but disabled (it is no longer the working copy)
- [ ] Selecting the **child commit** (working copy, inherits the conflict, empty diff of its own): "Resolve Conflicts…" is visible and **enabled**, and resolves the inherited conflict correctly
- [ ] `jj edit` back onto the conflicted parent commit: it becomes the working copy and "Resolve Conflicts…" becomes enabled for it; the previously-child commit (now not the working copy) shows the disabled hint instead

#### Multi-repo scoping

In a project with two jj roots each having conflicts:

- [ ] Right-clicking a conflicted file in root A's Working Copy panel → merge tool opens only for root A's conflicts
- [ ] Right-clicking a conflicted file in root B's → merge tool opens only for root B's conflicts
- [ ] Global action (VCS menu) → merge tool opens for conflicts from both roots, one after another

### MT-IGNORE

**.gitignore file status and file tracking**

**Code:** `vcs/ignore/GitignoreCache.kt`, `vcs/ignore/JujutsuIgnoreService.kt`, `vcs/ignore/JujutsuIgnoredFilesService.kt`, `vcs/ignore/JujutsuTrackedFilesService.kt`, `vcs/changes/JujutsuIgnoredFileProvider.kt`, `actions/file/TrackedToggleAction.kt`, `actions/file/trackUntrackAvailability.kt`

**Setup**: open this project itself in `./gradlew runIde` — it has a `.gitignore` with `build/`, `.gradle/`, etc.

#### Project Tool Window — ignored file coloring

- [ ] `build/` and `.gradle/` show grayed-out (IGNORED) color in the Project tree, including
      nested files (e.g. `build/classes/Main.class` — parent propagation); `src/` and tracked
      source files are NOT grayed out

→ automate: jj-idea-aah2 (.gitignore coloring/propagation checks above are file-classification
logic, testable without rendering)

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
- [ ] Even if `.gitignore` contained a pattern matching `CHANGELOG.md`, a tracked file would not be
      grayed (not independently verifiable in the IDE — tracked files are never passed to the
      ignore check in the first place; this is a code-level invariant, not a UI behavior to click through)

#### Ignore-scan watchdog (jj-idea-la8w)

The watchdog (5s) aborts the in-progress full ignore-scan instead of merely logging. This is
mostly covered by `GitignoreScanTest.kt` (code-level scale test); the disable escape hatch
remains manually verifiable:

- [ ] Settings / Version Control / Jujutsu → per-repo "disable ignored-file scanning" checkbox
      still works: enable it, edit `.gitignore`, confirm the Ignored Files node stops updating
- [ ] If you have access to a very large repo: a slow scan should show the "ignore scan slow"
      notification once per repo per session, with "disable" and "report" actions still
      functioning; the IDE should not hang waiting for the scan to finish after the watchdog
      fires
- [ ] jj-idea-ixju: global "disable ignored-file scanning (all repositories)" default and its
      interaction with the per-repo override — see the batch-2 checklist under MT-SETTINGS

#### Large ignored-file set cap (jj-idea-cvqz)

→ automate: jj-idea-s0ab (the cap itself only needs a large synthetic ignored-file set,
not a real >50k-entry repo)

Ignored files are reported via `ChangelistBuilder.processIgnoredFile` inside the CLM refresh
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

#### Tracked toggle (jj-idea-i9ol, GitHub #42)

Right-click on selected files in Project view, editor, Working Copy panel, or a Commit view → a
single **Tracked** checkbox item (wrapping `jj file track --include-ignored` / `jj file untrack`)
appears when at least one selected file matches an ignore rule. Checked means jj currently tracks
the file; unchecked means it doesn't; clicking flips it. Unlike an earlier build of this feature,
there's no separate Track/Untrack pair and no text hint about which one might fail — tracked
status is always determined reliably via `jj file list` (see
`docs/jj-track-untrack-model.md`), never guessed. The item is hidden entirely on ordinary,
non-ignored files (there's nothing meaningful to toggle there), on **any selection containing a
folder** (no tri-state checkbox exists in IntelliJ's menu system, and folders are out of scope for
this feature — see jj-idea-i9ol's design notes), and in the Commit details panel (historical,
non-working-copy context — tracking only applies to the working copy).

Tracked status is resolved via a small async cache (`JujutsuTrackedFilesService`) rather than a
direct query, since `update()`/`isSelected()` run under a read action even on background threads
and IntelliJ forbids blocking subprocess calls there. **The first time you right-click a
previously-unseen file *without clicking the checkbox*, it may briefly show unchecked (a safe
default) while the cache populates in the background (~300ms)** — right-clicking (or looking)
again shortly after should show the accurate state. This is expected, not a bug. Once you actually
**click** the checkbox, its state is written immediately and is authoritative from that point on —
no such delay applies to a click.

→ automate: jj-idea-me8m (tracked-toggle cache semantics below are async-cache state logic,
testable without rendering)

- [ ] Add a pattern to `.gitignore`, create a matching file that was never tracked. Right-click it
      in Project view, Working Copy panel, and a Commit view → **Tracked** checkbox appears,
      unchecked
- [ ] Check it → the checkbox flips to **checked immediately and stays checked** (right-click the
      same file again right after — still checked; this is the regression check for an earlier
      build where the checkbox could silently revert because `isSelected()` re-read a
      not-yet-updated cache). A brief progress indicator appears in the status bar while the
      command runs, and a notification balloon confirms completion ("Track — 'foo.txt'"). The file
      now actually appears in the working copy / `jj file list` (regression check: an even earlier
      build's Track action silently no-op'd on ignored files because it was missing
      `--include-ignored`); log refreshes
- [ ] Uncheck it → checkbox flips to unchecked immediately; notification balloon reads
      "Untrack — 'foo.txt'"; file becomes untracked again
- [ ] Multi-select one already-tracked-and-ignored file plus one untracked-and-ignored file →
      checkbox shows **unchecked** (mixed selection reads as "something left to track"); checking
      it tracks only the untracked one (notification summarizes the count, e.g. "Track — 1 file"),
      leaving the already-tracked one alone
- [ ] Untrack (uncheck) a file, then immediately try unchecking an already-untracked one in the
      same multi-select → the notification balloon's message includes jj's own
      `Warning: No matching entries for paths: ...` text for the no-op member (regression check:
      an earlier build silently swallowed this warning entirely)
- [ ] Force a failure — e.g. manually remove a file from `.gitignore` so it's no longer ignored,
      then try unchecking it anyway → a **non-blocking error notification** balloon appears (not a
      modal dialog) showing jj's "not ignored" message, and the checkbox **reverts** to its true
      (checked) state rather than staying on the failed change
- [ ] Right-click a normal, non-ignored, tracked file (anywhere: Project view, editor, Working
      Copy panel, Commit view) → **no Tracked checkbox appears at all**
- [ ] Right-click a file in the **Commit details panel** (historical, non-working-copy context)
      → no Tracked checkbox appears, even on an ignored file
- [ ] Right-click a **directory** that itself matches an ignore rule (e.g. `build/`) → no Tracked
      checkbox appears, in Project view and Working Copy panel alike. Also check a mixed selection
      (one file + one directory) → checkbox likewise hidden (regression check: an earlier build
      would have shown a permanently-inert checkbox for a directory selection)
- [ ] Right-click an ignored file you've never interacted with before → confirm no
      `Synchronous execution under ReadAction` (or similar) error appears in the IDE log
      (Help → Show Log) — this was a real crash in an earlier build, caused by querying
      `jj file list` directly inside the checkbox's `update()`/`isSelected()`

### MT-GIT

**Git push / fetch dialogs**

**Code:** `actions/git/GitPushDialog.kt`, `actions/git/GitPushAction.kt`, `actions/git/gitRemoteActions.kt`, `actions/git/GitFetchDialog.kt`, `actions/git/GitFetchAction.kt`, `actions/git/RadioScopeBinding.kt`

#### Git Push Dialog

Setup: have a local bookmark that has never been pushed to the remote.

- [ ] Open push dialog (VCS menu → Push) → "Tracking bookmarks (default)" selected → OK → push completes (shows success notification)
- [ ] Open push dialog → "Tracking bookmarks (default)" → if new bookmark exists, confirmation dialog appears asking whether to create remote bookmark → confirm → push succeeds
- [ ] Open push dialog → "Specific bookmark" → select an untracked bookmark → OK → push succeeds
- [ ] Open push dialog → "All bookmarks" → OK → pushes all bookmarks
- [ ] Cancel push dialog → no push occurs
- [ ] The scope radio group (Default / Specific bookmark / All bookmarks) has the correct
  option selected by default when the dialog opens, the bookmark combo box enables only when
  "Specific bookmark" is selected, and clicking between all three options multiple times before
  OK always pushes according to the last-selected option (scope selection is hand-wired via
  action listeners, not the platform's declarative binding, as of the 2026.2 platform-compat
  work — jj-idea-gu9q)
- [ ] (jj-idea-idm0) With a repository that has 2+ Git remotes: open the push dialog, switch the
  **Remote** combo to a different remote — the bookmark combo must immediately show a real
  bookmark (never blank), select "Specific bookmark" → OK → dialog closes and pushes to the
  newly selected remote/bookmark. Repeat switching remotes several times before pressing OK.
  Check Help → Show Log afterwards for any `NullPointerException` from `GitPushDialog` — there
  must be none (previously the Push button appeared completely inert after a remote switch)
- [ ] Push a bookmark that's already up to date with the remote → the notification shows jj's
  own "Nothing changed." message rather than a bare "Push complete"
- [ ] Every bookmark tracked against the selected remote appears **exactly once** in the
  "Specific bookmark" dropdown — none of them also shows a duplicate "(new)" entry
  (jj-idea-ehki — `GitPushDialog.currentBookmarks()`/`mergeBookmarks` used to dedupe by full
  `Bookmark` equality, which never matched because `tracked` differs between the two source
  lists)
- [ ] (jj-idea-ehki) Delete a local bookmark that's still tracked on the remote
  (`jj bookmark delete <name>`) so it's a pending deletion. Right-click the **commit** it used
  to sit on in the log (not the bookmark chip) → Push… → "Specific bookmark" → the dropdown
  must include `<name> (deleted)` in grey italic, even though the push was opened scoped to
  that revision. Select it → OK → the "will be deleted from the remote" confirmation appears →
  confirm → push succeeds and the bookmark disappears from the remote (`jj bookmark list
  --all-remotes` shows it gone, not just `(deleted)` locally)

#### `--change` push scope (jj-idea-fmzr, GitHub #65; multi-select/toolbar jj-idea-ikof)

A fourth scope, "Create bookmark for change \<id\>", runs `jj git push --change <rev>` —
auto-generating a `push-<change-id>`-style remote bookmark for the target revision, per jj's own
recommended workflow. The dialog resolves the target itself; there's no revision picker in the
dialog. Available from three entry points — the toolbar/VCS-menu Push button (`GitPushAction`),
the log's right-click Push (`gitPushAction()`), and the per-bookmark Push submenu (which never
offers this scope — it's always "Specific bookmark") — and reads the **log table's current
selection** wherever one exists.

- [ ] Toolbar Push (VCS menu icon or log toolbar button) with **nothing selected in the log** (or
  invoked from a context with no log at all) → the fourth radio names `@`'s own change id (or
  `@-` if `@` is empty — see below) → OK → a new `push-<id>` bookmark appears on the remote
  (`jj bookmark list --all-remotes`)
- [ ] Select a **single commit** in the log, then click the toolbar Push button → the fourth
  radio names that commit's own change id, not `@` — confirms the toolbar button now reads the
  log selection (previously it always ignored it)
- [ ] `jj commit` so `@` is empty, then toolbar Push with nothing selected → the fourth radio now
  names `@-`'s change id instead
- [ ] Right-click a **specific commit** in the log → Push… → the fourth radio names that
  commit's own change id, regardless of where `@` currently is
- [ ] **Multi-select** several commits in one repo, then either right-click → Push… or the
  toolbar Push button → the fourth radio reads "Create bookmarks for N changes" (plural) → OK →
  one `push-<id>` bookmark per selected commit appears on the remote, all in one confirmation
- [ ] **Multi-repo project**: select commits spanning two different repos, then toolbar Push →
  dialog opens normally, but the fourth radio is **absent** (not just disabled) — the other
  three scopes work as usual via the dialog's own Repository selector
- [ ] Still with that cross-repo selection: right-click → Push… (context menu) is **entirely
  disabled** — unchanged from before this scope existed, since the context menu's Push has
  always required a single repo
- [ ] With a single-repo selection and the fourth radio chosen, switch the dialog's own
  **Repository** combo to a different repo → the fourth radio disappears and the scope silently
  reverts to "Tracking bookmarks (default)" — no crash, no stale target left selected
- [ ] Push the same change twice via this scope → the second push does not create a second
  `push-*` bookmark (confirm via `jj bookmark list --all-remotes` — same bookmark name both
  times)
- [ ] This scope's dry-run does **not** spuriously trigger the "will create a new remote
  bookmark" confirmation that the default/tracking scope shows (jj only refuses new remote
  bookmarks for that scope, not `--change`)

#### Git Fetch Dialog

Setup: a repository with 2+ remotes (the scope radio group only appears in this case).

- [ ] Open fetch dialog (VCS menu → Fetch) → "Specific remote" selected by default, remote combo
  box enabled → OK → fetches from the selected remote
- [ ] Switch to "All remotes" → remote combo box disables → OK → fetches from every remote
- [ ] Switch back to "Specific remote" → combo box re-enables with the previously selected remote
  → OK → fetches just that one
- [ ] Cancel fetch dialog → no fetch occurs
- [ ] (jj-idea-idm0) With 2+ repositories mapped, and the repository selector visible: switch the
  **Repository** combo to a different repo — the remote combo must immediately show a real
  remote for that repo (never blank) → OK → fetches from the newly selected repo/remote

#### New/untracked bookmark push (jj-idea-dt2k, GitHub #53)

The plugin no longer uses `jj git push --allow-new` (removed in jj 0.42.0); it tracks the
bookmark against the remote first, then pushes without any special flag. Verify on **both**
jj < 0.42 (e.g. 0.37–0.41) and jj ≥ 0.42 (e.g. 0.43) — this was the #53 regression, previously
failing on 0.42+ with `error: unexpected argument '--allow-new'`:

- [ ] "Tracking bookmarks (default)" scope with a brand-new untracked bookmark at the working
  copy → confirmation dialog lists the bookmark → confirm → push succeeds and the bookmark is
  now tracked (`jj bookmark list` shows `@origin`)
- [ ] "Specific bookmark" scope, selecting an untracked bookmark → same confirmation → confirm →
  push succeeds
- [ ] Cancel either confirmation dialog → no push occurs, bookmark remains untracked

### MT-SETTINGS

**Settings panel**

**Code:** `settings/JujutsuConfigurable.kt`, `settings/JujutsuSettings.kt`, `settings/JujutsuSettingsState.kt`, `settings/JujutsuApplicationSettings.kt`
**Also re-run:** MT-DIFFBASE (its Diff Base group and per-repo override live in this same panel)

- [ ] JJ executable path can be configured, including via the file picker
- [ ] Auto-refresh toggle, change ID format preference (short/long), and log change limit
      each take effect as expected, and all settings persist across IDE restarts

#### Default push scope (jj-idea-fmzr, jj-idea-ikof)

- [ ] **General** section: a "Default push scope:" combo lists all four Push dialog scopes
      (Tracking bookmarks / Specific bookmark / All bookmarks / Create bookmark for change).
      Defaults to "Tracking bookmarks (default)" on a fresh install.
- [ ] Set it to "Create bookmark for change", click Apply, then open Push from **both** the log's
      right-click menu and the toolbar/VCS-menu button — both open with the fourth radio already
      selected (the toolbar button didn't honor this setting at all before jj-idea-ikof; confirm
      it does now). Restart the IDE — the setting persists.
- [ ] With the default set to "Create bookmark for change", right-click a **bookmark** →
      Push… (the per-bookmark entry point, jj-idea-t29z) still opens on "Specific bookmark" —
      this setting only affects the dialog's *unforced* default, not a caller that already
      knows which bookmark it wants
- [ ] With the default set to "Create bookmark for change" and a cross-repo log selection: open
      toolbar Push → the fourth scope is unavailable (see MT-GIT), so the dialog falls back to
      "Tracking bookmarks (default)" instead — no crash, no radio stuck on a hidden option

→ automate: jj-idea-ajd0 (settings persistence and column width/visibility persistence,
tracked in MT-LOG-TABLE, are state-serialization logic)

#### Settings — batch-2 escape hatches (jj-idea-isnf, ixju)

- [ ] **General** section: check "Disable ignored-file scanning (all repositories)" with no
      per-repo override set, click Apply. Confirm scanning stops in every repo. In a repo with
      its own override explicitly set to "off" (see Repository Settings below), confirm it still
      scans despite the global checkbox.
- [ ] **Log Settings** section: set "Context window" to 0, click Apply. Navigate to a revision
      outside the loaded log (e.g. via file annotation, or jump to a bookmark outside the
      current revset) — exactly one row (the target) appears, no ancestors/descendants. Set it
      back to 10 and repeat — the ~19-revision window returns.
- [ ] **Repository Settings** (multi-repo project): expand a repo's collapsible group. "Override
      context window" checkbox + field lets that repo use a different window (e.g. 0) than the
      project default — confirm only that repo's out-of-view navigation degenerates.
      "Override ignored-file scanning default" checkbox + "Disable ignored-file scanning for this
      repository" checkbox let a repo explicitly re-enable scanning even when the global default
      (General section) is "disabled everywhere" — confirm the override wins in both directions.

#### Settings — Support section

- [ ] Open **Settings → Version Control → Jujutsu**
- [ ] A **Support** group appears at the bottom of the panel with a "Sponsor this plugin on GitHub..." link
- [ ] Clicking the link opens `https://github.com/sponsors/kkkev` in the default browser
  (jj-idea-z1ld: `SPONSORS_URL` is now defined once in `ui/services/SponsorAsk.kt` and
  shared with the in-product sponsor ask under MT-WORKINGCOPY — both must point at the
  same URL)

#### Settings panel width (jj-idea-bwdk)

→ automate: `JujutsuConfigurablePanelTest` covers the panel's overall preferred width and a
long validation message, both without any repository configured; the checks below cover what
that test can't (a live dialog, and the per-repo group, which needs a real project).

- [ ] Open **Settings → Version Control → Jujutsu** at the dialog's default size — no horizontal
      scrollbar anywhere in the panel; the **Test** button next to the executable path is fully
      visible without scrolling
- [ ] Widen and narrow the Settings dialog — the executable path and revset fields grow/shrink
      with it; nothing gets clipped that wasn't already clipped before this change
- [ ] Click **Test** with a bogus executable path (e.g. `/bin/ls`) — the error message wraps
      over multiple lines instead of widening the panel
- [ ] **Log Settings**: the "Revset expression:" box is a ~3-line multi-line field whose left
      edge lines up with the "Changes to show:" and "Context window:" fields above it, and its
      guidance text below lines up with that same left edge (not the row's label). Type an
      expression longer than the box's width — it word-wraps inside the box instead of
      scrolling horizontally; pressing Enter does not submit or otherwise misbehave
- [ ] Enter a syntactically invalid revset (e.g. `zz(`) in **Log Settings** and click **Test** —
      the raw `jj` error wraps instead of widening the panel
- [ ] Expand **Installation Help** — every command row and its **Copy** button are fully visible
- [ ] In a multi-repo project, expand a repo under **Repository Settings** — the "Override
      revset expression" and "Override ignored-file scanning default" rows read correctly with
      their field/checkbox stacked under the label, the Test button is fully visible, and Apply
      still persists every override (identity, limit, revset, context window, ignore-scan)

### MT-CROSS

**Multi-repository, visual consistency, edge cases, and error handling**

**Code:** `ui/log/JujutsuRootFilterComponent.kt`, `ui/log/JujutsuRootGutterRenderer.kt`, `ui/log/RepositoryColors.kt`, `ui/common/JjNotInstalledPanel.kt`

#### Multi-Repository (if applicable)

- [ ] Root filter appears for multi-root projects and hides for single-root projects
- [ ] Root gutter column shows repo names; filtering by root works correctly
- [ ] Entries from different roots sort by timestamp
- [ ] Reference filter and graph repo scoping: see MT-LOG-FILTER's "Multi-repo scoping" subsection
      (jj-idea-1ra9) — no cross-repo ancestry or graph lines, even when repos' root commits share
      a change id

#### Visual Consistency

- [ ] Light and dark themes both render correctly: icons at correct size, row striping,
      hover highlighting, distinct selected-row highlighting, disabled actions grayed out

#### Edge Cases

- [ ] Empty repository shows an appropriate message; large repository (100+ commits) loads
      without hanging; rapid filtering doesn't cause errors
- [ ] Very long descriptions truncate with ellipsis; non-ASCII characters display correctly

#### Error Handling

- [ ] Invalid JJ path, non-JJ repository, network errors, and concurrent operations each
      show a helpful/user-friendly message rather than corrupting state or crashing
- [ ] Backend without `remote_bookmarks()` revset support (jj-idea-2wpq, GitHub #35; can't be
  reproduced with stock jj — requires a non-standard backend, e.g. Google-internal
  Piper/p4base-backed jj) loads the log and working copy successfully, minus the
  pushed-ancestor decoration (the "Open File in remote" action stays hidden) — instead of
  failing the whole load. A one-time WARN is logged on first detection; subsequent
  refreshes/loads for that repo don't re-probe or repeat the warning for the rest of the
  session
