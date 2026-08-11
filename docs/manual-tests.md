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
| Diff preview-tab helper (`ui/common/JujutsuEditorTabDiffPreview.kt`) | [MT-DIFF-PREVIEW](#mt-diff-preview), and its three referrers |
| Multi-repo scoping (root-aware actions/filters generally) | [MT-CROSS](#mt-cross), plus every section with a repo-scoped action |

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

#### Column management

- [ ] Column visibility toggle, reordering (drag-and-drop), resizing (drag separator), and
      auto-fit (double-click separator) all work
- [ ] Column widths and visibility both persist across IDE restarts

#### Responsive column sizing (jj-idea-lzq7)

- [ ] Open a fresh log tab (or one where you've never dragged a column). The Columns menu's
      "Fit Columns to Window Width" is checked by default. Drag the tool window narrow: the
      description column shrinks and no horizontal scrollbar appears until the window is very
      narrow; author/date visibly narrow (and ellipsize) before any scrollbar appears
- [ ] With that tab still narrow, dock the commit-details pane to the right (Details position):
      the table re-fits to the remaining width with no scrollbar; widen the window back out and
      the columns grow back
- [ ] In a tab with a column you dragged before this change shipped (or drag one now, then
      reopen the tab), "Fit Columns to Window Width" defaults to unchecked and the layout/
      scrollbar behavior is exactly as before
- [ ] Toggle "Fit Columns to Window Width" off and on in the Columns menu; behavior switches
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

**Code:** `ui/log/JujutsuCommitDetailsPanel.kt`, `ui/components/HtmlTextCanvas.kt`, `ui/components/TextCanvas.kt`
**Also re-run:** MT-DIFF-PREVIEW (details changes panel shares the preview-tab behavior)

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

### MT-LOG-FILTER

**Toolbar, filters, and reference filter**

**Code:** `ui/log/JujutsuFilterComponent.kt`, `ui/log/JujutsuAuthorFilterComponent.kt`, `ui/log/JujutsuDateFilterComponent.kt`, `ui/log/JujutsuReferenceFilterComponent.kt`, `ui/log/JujutsuRootFilterComponent.kt`, `ui/log/JujutsuPathsFilterComponent.kt`, `ui/log/LogFilterMatcher.kt`, `ui/common/FilterPriorityLayoutStrategy.kt`

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
- [ ] Paste a hash for a commit that isn't currently loaded in the log window — no results
      (searching outside the loaded window is not yet supported)

#### New/Edit toolbar buttons (jj-idea-e53e)

- [ ] **New** and **Edit** icon buttons appear at the left of the main log toolbar, before Refresh, each with a tooltip
- [ ] Selecting a mutable non-working-copy change and clicking **Edit** moves the working copy to it (it becomes `@`) and the log reselects it
- [ ] Selecting the working-copy change or an immutable commit disables **Edit**
- [ ] Clicking **New** with a change selected creates a new empty change on top of the selection and it becomes `@`; with no selection it stacks on the working copy
- [ ] Clearing the log selection entirely (e.g. Ctrl/Cmd-click the selected row to deselect) disables **New** (greyed out) rather than removing it from the toolbar — it stays in place, matching **Edit**'s behavior
- [ ] Open a file's history (right-click a file > Show History) — confirm its toolbar shows only Refresh/search, with no New/Edit buttons

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

### MT-CTXMENU

**Log row context menu actions**

**Code:** `actions/change/`, `ui/duplicate/DuplicateDialog.kt`, `ui/duplicate/DuplicateImmutabilityGuard.kt`, `ui/common/JujutsuCompareChangesPanel.kt`, `ui/components/RevisionSelectorPopup.kt`
**Also re-run:** MT-SQUASH, MT-SPLIT (share the commit picker); MT-DIFF (Compare with Working Copy / Show Diff in New Tab reuse the RevisionSelectorPopup and Changes-pane view)

- [ ] Right-click opens context menu
- [ ] **Copy Change ID** works and copies to clipboard
- [ ] **Copy Description** works and copies to clipboard
- [ ] **New Change From This** (primary, no dialog) creates new change directly and refreshes
- [ ] **New Change with Description...** (secondary) opens the description dialog and creates the change
- [ ] **Edit** action changes working copy
- [ ] **Describe** action opens dialog and updates description
- [ ] **Abandon** action removes change after confirmation
- [ ] **Duplicate Change** action creates an identical copy in place, with a new change ID and the same description; `@` does not move
- [ ] **Duplicate Onto...** opens a dialog to pick a destination and placement (onto/after/before), then creates the copy there
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

#### Compare with Working Copy (jj-idea-a6cz, jj-idea-vtdl)

- [ ] Right-clicking a non-working-copy commit shows **Compare with Working Copy**
- [ ] Right-clicking the working-copy entry: **Compare with Working Copy** is **not visible**
- [ ] Invoking it on a commit with differences from `@` opens the **VcsChanges** tool window with a Changes tree listing every changed file (added/modified/deleted/renamed all included), and the first file's diff open in the editor
- [ ] Selecting other files in the tree updates the diff in the same reusable editor tab
- [ ] The right (working-copy) side of the diff is editable, and edits are written through to the real file on disk
- [ ] The left (commit) side is read-only
- [ ] Right-clicking a file in the Changes tree shows the jj file-change context menu (Show Diff, Restore, etc.)
- [ ] Invoking it on a commit identical to `@` shows a "No Differences" notification instead of an empty pane

#### Show Diff in New Tab, multi-file (jj-idea-vtdl)

- [ ] Multi-selecting files (in the log's file list or a commit's changes) and choosing **Show Diff in New Tab** opens the same VcsChanges Changes-pane view as Compare with Working Copy, with the first file's diff open
- [ ] Selecting a single file still shows a title with just that file's name; multiple files show "N files"

### MT-SQUASH

**Squash Into…**

**Code:** `ui/squash/SquashIntoDialog.kt`, `actions/change/squashIntoAction.kt`, `actions/change/squashFromAction.kt`, `actions/filechange/SquashIntoFilesAction.kt`
**Fixture:** FX-STACK
**Also re-run:** MT-CTXMENU (shares the commit picker)

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

→ automate: jj-idea-ikr6 (description auto-population + validation logic below is pure
string/state logic, no rendering dependency)

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

**Code:** `ui/split/SplitDialog.kt`, `ui/split/HunkSelectionModel.kt`, `ui/split/SplitPreviewPanel.kt`, `ui/split/SplitSimulator.kt`, `diffedit/HunkDiffPicker.kt`, `diffedit/DiffEditTool.kt`, `actions/change/splitAction.kt`, `actions/filechange/SplitFilesAction.kt`
**Also re-run:** MT-CTXMENU (shares the commit picker in some flows)

Setup: create a scratch jj repo with a file that has at least **two separate** hunks of changes
(so partial selection is meaningful).

Model: **ticking a file moves it to the new child commit**; unticked files stay in the
parent. Nothing is ticked by default. "Pick Hunks…" opens a 3-way merge widget with **fixed,
never-flipping roles**: Left = "Before" (the file's state before any of this change), Right =
the child commit's label (always the source's full content — a structural invariant of
`jj split`, not a bug), Middle = editable "Parent" (seeded from any existing partial pick, or a
tick-derived default). Accepting a hunk from the **left** removes it from Parent (sends it to
the child); accepting from the **right** adds it to Parent (pulls it from the child) — both are
always available for the same hunk, so a pick can be reversed by accepting the opposite side,
as many times as needed before closing the dialog.

#### Basic hunk selection (main dialog preview)
- [ ] Right-click a mutable change → **Split…** → dialog shows changed-files list on the left (nothing ticked) and a native read-only diff preview on the right
- [ ] Click a file in the list → right panel shows a native syntax-highlighted diff titled **"Parent (all changes)"** / **"Child (no changes)"**, with an **empty diff** (nothing ticked yet, so nothing moves)
- [ ] Tick the file → titles switch to **"Parent (unchanged)"** / **"Child (all changes)"**, showing the **full diff** (the whole file's change moves to the child); untick → back to the "all changes"/"no changes" pair and empty diff
- [ ] Fully-ticked files show a filled checkbox; unticked show empty; partially-picked (see below) show a **half-checked** box
- [ ] Directory nodes containing a partial file also show a half-checked box

#### Right-click file(s) → "Split into New Child"
- [ ] Select one or more files in the working-copy / commit-details file list, right-click → **Split into New Child** → dialog opens with exactly those files **ticked** (moving to the child)
- [ ] Split → the new child commit contains only the selected files; the parent keeps the rest

#### Hunk picking with the 3-way picker
- [ ] Click **Pick Hunks…** → a merge-style dialog opens titled "Pick Hunks — <filename>", with **Before** (left), **Parent** (editable, middle), and **Child** (right) panes
- [ ] On a freshly-opened **unticked** file: Parent (middle) starts with the full content, identical to Child (right) — but differs from Before (left) at every hunk, so **left-side accept arrows are visible and clickable** (this used to show an empty, unusable diff — verify it no longer does)
- [ ] Accepting a hunk from the **left** (Before) removes it from Parent — that hunk moves to the child
- [ ] On a freshly-opened **ticked** file: Parent starts empty, matching Before (left) — but differs from Child (right) at every hunk, so **right-side accept arrows are visible**
- [ ] Accepting a hunk from the **right** (Child) adds it to Parent — that hunk stays in the parent
- [ ] **After accepting a hunk from one side, the opposite side's arrow reappears for that same hunk** — click it to reverse the pick (regression check: previously there was no way back once a hunk was accepted)
- [ ] Accept some hunks (not all, mixing both directions is fine) and click **Apply** → a platform confirmation appears first: **"Apply Changes"** / "There is one change left unprocessed. Save changes and mark the conflict resolved anyway?" / **"Continue Merge"** / **"Apply Changes and Mark Resolved"**. This wording is IntelliJ's own conflict-resolution dialog and has no supported override hook (see jj-idea-xuob) — click **"Apply Changes and Mark Resolved"** to proceed; this is expected on every genuinely-partial pick, not a bug
- [ ] After confirming → dialog closes; file shows **half-checked** in the file list; summary shows "(N partial)"
- [ ] **The file's tick state is unchanged by a partial pick** — if it was unticked before opening the picker, it's still unticked after a partial accept (regression check: previously this force-ticked the file)
- [ ] Accepting every hunk toward Parent → Apply results in a **fully ticked** file (no half-check), same as ticking it directly
- [ ] Accepting no hunks (or reversing back to nothing) → Apply results in the file being **fully ticked or unticked** to match its starting state, with no partial override left over
- [ ] Click **Cancel** → confirmation reads "Cancel Hunk Selection?" / "Discard your changes to Parent?"; confirming closes the dialog with file state (tick + any prior override) unchanged
- [ ] **Reopen "Pick Hunks…" on a file with an existing partial selection** → Parent (middle) resumes exactly where you left it (not reset to a tick-derived default) — regression check for lost persistence
- [ ] Split (linear) → child commit contains only the picked hunks; parent has the rest
- [ ] Log refreshes selecting the newly created change

→ automate: jj-idea-ygtw (validation, whole-file fast path, and binary gating below are
state/routing logic, not rendering)

#### Descriptions
- [ ] Both description fields are pre-populated with the source commit's description
- [ ] Child description field appears **above** the parent field (matching the child's position above the parent in the log)
- [ ] Editing the child description field updates the child commit; editing parent updates the parent

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

**Code:** `ui/log/JujutsuBookmarkWidget.kt`, `actions/bookmark/`, `jj/ClosestBookmarks.kt`, `jj/JjFeature.kt`
**Also re-run:** MT-LOG-REFRESH (label reactivity relies on the same auto-refresh path); MT-CROSS (multi-repo dropdown structure)

#### Single-repo project

- [ ] "Bookmark: \<name\>" label appears in the log toolbar to the left of the Reference filter when @ has a local bookmark
- [ ] `jj new` off a bookmarked change with nothing left ahead of it — label shows "Bookmark: \<name\> +1" (jj-idea-l7wd, GitHub #62), where `<name>` is the nearest ancestor bookmark and `+1` the number of changes since it; label reads "Bookmark:" with nothing after it only when @ has no bookmark anywhere in its ancestry
- [ ] Two bookmarks equally close to @ (e.g. either side of a merge) — label lists both names, comma-separated, before the shared `+N`
- [ ] Label updates reactively: run `jj bookmark create foo` in the terminal — label changes to "Bookmark: foo" within ~300 ms, without saving a file or restarting (see MT-LOG-REFRESH); `jj new` afterwards updates it to "Bookmark: foo +1" the same way
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

#### Multi-repo project

- [ ] Bookmark widget is present in the toolbar (not hidden)
- [ ] Label shows "Bookmark:" with nothing after it regardless of which bookmarks exist (the "name +N" fallback only applies to a single-repo project — see jj-idea-1ra9 for the wrong-repo-ancestry bug this must not repeat)
- [ ] Click the widget — dropdown shows one sub-menu **per repo**, named by repo display name
- [ ] Each repo sub-menu contains the same structure as the single-repo dropdown: "Create Bookmark Here…", then "Advance Bookmark Here", then the repo's bookmark sub-menus
- [ ] "Create Bookmark Here…" inside repo-a's sub-menu creates a bookmark at **repo-a's** working copy, not repo-b's (check via `jj bookmark list` in each repo)
- [ ] `jj new` past every bookmark in repo-a only (repo-b still has one on @) — repo-a's "Advance Bookmark Here" is enabled and targets repo-a's nearest bookmark; repo-b's advances repo-b's bookmark, unaffected by repo-a
- [ ] Rename/Delete/Forget in repo-b's sub-menu affects only repo-b

#### Advance Bookmark (jj-idea-l7wd, GitHub #61)

- [ ] With exactly one bookmark closest to @: clicking "Advance Bookmark Here" moves it directly to @, no dialog — confirm via `jj bookmark list` or the updated log decoration
- [ ] With two+ equidistant closest bookmarks (e.g. `jj new` off a merge of two bookmarked branches): clicking "Advance Bookmark Here" opens a picker dialog listing all of them, pre-checked; unchecking one and confirming advances only the checked ones
- [ ] The per-bookmark "Advance … to Working Copy" action (in a bookmark's own sub-menu, or via right-click on its chip in the log) moves that specific bookmark to @ regardless of distance, without opening a picker
- [ ] Advancing a bookmark that's already at @ is a no-op (no error)
- [ ] With no bookmark anywhere in @'s ancestry: "Advance Bookmark Here" is visible but disabled, with a tooltip explaining there's nothing to advance
- [ ] **Version gating**: with a jj executable below 0.39 configured (Settings → Version Control → Jujutsu → jj executable path), both "Advance Bookmark Here" and the per-bookmark Advance action are visible but disabled, with a tooltip naming the required version and your current one, and Settings → Version Control → Jujutsu → Install/Upgrade shows the correct upgrade command for your detected install method
- [ ] The disabled reason is also appended to the menu item's own text, not just its tooltip (menus don't reliably show tooltips) — e.g. "Advance Bookmark Here (needs jj 0.39+)" or "Advance 'main' to Working Copy (needs jj 0.39+)"; with no bookmark anywhere in @'s ancestry, "Advance Bookmark Here (nothing to advance)"

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

### MT-WORKINGCOPY

**Working copy panel, status bar widget, and tool window behavior**

**Code:** `ui/workingcopy/UnifiedWorkingCopyPanel.kt`, `ui/workingcopy/WorkingCopyControlsPanel.kt`, `ui/workingcopy/WorkingCopyToolWindowFactory.kt`, `ui/statusbar/JujutsuStatusBarWidget.kt`, `ui/statusbar/JujutsuWorkingCopySwitcher.kt`, `ui/services/ToolWindowEnabler.kt`, `ui/services/WorkingCopySignpost.kt`, `ui/services/JujutsuStartupActivity.kt`, `vcs/JujutsuHiddenCommitMode.kt` (Standard Commit Tool Window Suppression), `vcs/JujutsuVcsBase.kt`, `actions/top/InitAction.kt`
**Also re-run:** MT-DIFF-PREVIEW (changed-files tree shares the preview-tab behavior); MT-CROSS (colocated Git / multi-VCS project scoping)

#### Working Copy Panel

- [ ] Description text area shows current description
- [ ] jj-idea-qa8i: clicking into the description text area, typing, and pressing Enter inserts
  a newline (does not do nothing or trigger another action)
- [ ] "Describe" button updates description via `jj describe`
- [ ] "New Change" button creates new change via `jj new`
- [ ] Changed files tree shows correct status colors and file type icons
- [ ] Preview-tab behavior (double-click, Enter, tab-swap, single-click-no-op-when-closed,
      single-click-swap-when-open, Escape, Cmd/Ctrl+D, F4): see MT-DIFF-PREVIEW
- [ ] Right-click shows context menu with file actions
- [ ] jj-idea-lo7u: "Compare Before with Another Commit..." is **not** in that menu (working
      copy context — same as "Compare Before with Local")
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
**Also re-run:** MT-DIFF-PREVIEW; see [Known gaps](#known-gaps) for jj-idea-7d9p/zvzk, which recur across every surface in this section

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
- [ ] jj-idea-q6vn: with `@` selected and its diff tab open on the working-copy side, the right-hand pane title reads "Current" (not the change id) and is editable; editing and saving the file *shown in the diff* updates its content in place without resetting scroll

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

**Code:** `actions/git/GitPushDialog.kt`, `actions/git/GitPushAction.kt`, `actions/git/GitFetchDialog.kt`, `actions/git/GitFetchAction.kt`, `actions/git/RadioScopeBinding.kt`

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

- [ ] JJ executable path can be configured, including via the file picker
- [ ] Auto-refresh toggle, change ID format preference (short/long), and log change limit
      each take effect as expected, and all settings persist across IDE restarts

→ automate: jj-idea-ajd0 (settings persistence and column width/visibility persistence,
tracked in MT-LOG-TABLE, are state-serialization logic)

#### Settings — Support section

- [ ] Open **Settings → Version Control → Jujutsu**
- [ ] A **Support** group appears at the bottom of the panel with a "Sponsor this plugin on GitHub..." link
- [ ] Clicking the link opens `https://github.com/sponsors/kkkev` in the default browser

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
