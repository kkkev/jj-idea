# Design: Bookmark Management UI (jj-idea-qgz)

## Context

JJ bookmarks are analogous to Git branches — named pointers to changes. The plugin currently:
- **Parses** bookmarks from `jj log` output (including `name@remote` for remote bookmarks)
- **Renders** bookmarks in the log table (decorations column, graph inline, details panel)
- **Uses** `jj bookmark list` for the push dialog and compare popup via `logService.getBookmarks()`
- Has **no** bookmark management actions (create, delete, rename, move, set, track)

### Existing Bookmark Infrastructure

**Parsing**: `CliLogService` parses bookmarks two ways:
1. From `jj log` — each `LogEntry` has `bookmarks: List<Bookmark>` (only bookmarks on commits in the loaded revset)
2. From `jj bookmark list` — `logService.getBookmarks()` returns `List<BookmarkItem>` (ALL bookmarks in repo)

**Data types**:
- `Bookmark(name: String)` — value class implementing `Ref`, used as revset target
- `BookmarkItem(bookmark: Bookmark, id: ChangeId)` — bookmark with its target change ID

**Rendering**: `TextCanvas.append(bookmark)` shows colored icon + name. Used in graph renderer, decorations column, details panel, and reference filter.

**Current commands**: Only `CommandExecutor.bookmarkList(template?)` exists.

## Approaches Considered

### Approach A: Git-Style Branches Popup

A dedicated popup/widget (like Git's `Ctrl+Shift+\``) showing all bookmarks in a tree with prefix grouping, search, and actions. The Git plugin uses `GitBranchesTreePopupOnBackend` with a complex tree model, favoriting, prefix grouping, and integrated actions.

**Pros**: Discoverability, familiar to Git users, good for repos with many bookmarks
**Cons**: Heavy infrastructure for a simpler concept; JJ power users use CLI; bookmarks are less central than Git branches (no "current branch" concept in JJ)

### Approach B: Context Menu Actions (Recommended)

Add bookmark actions to existing surfaces — the log table context menu, the details panel, and bookmark labels wherever they appear. No new popup or panel.

**Pros**: Lightweight, fits the plugin's current architecture, fast to implement, keeps bookmark management where users already are (the log)
**Cons**: Less discoverable for new users; no central "bookmark browser"

### Approach C: Bookmarks Side Panel in Log

Like Git's branches dashboard — a collapsible left panel in the custom log showing all bookmarks, clickable to filter/navigate.

**Pros**: Central overview of all bookmarks, filtering integration
**Cons**: Significant new UI component; JJ logs already show bookmarks inline; overlaps with existing reference filter

## Recommendation: Approach B (Context Menu Actions)

This matches the target audience (JJ CLI power users who want IDE convenience) and builds on existing surfaces. A bookmark popup (Approach A) or panel (Approach C) can be added later as P3/P4 enhancements if users request them.

---

## Design

### Primary Actions (Most Common)

1. **Create bookmark** — on any change in the log
2. **Move bookmark** — move existing bookmark to selected change, with force-push protection
3. **Track remote bookmark** — start tracking an untracked remote bookmark (e.g., `main@origin`)

### Secondary Actions

4. **Delete bookmark** — remove and propagate deletion to remotes on next push
5. **Forget bookmark** — remove locally without propagating to remotes
6. **Rename bookmark**
7. **Untrack remote bookmark**

---

### 1. New CommandExecutor Methods

Add to `CommandExecutor` interface:

```kotlin
fun bookmarkCreate(name: String, revision: Revision = WorkingCopy): CommandResult
fun bookmarkDelete(name: String): CommandResult
fun bookmarkForget(name: String): CommandResult
fun bookmarkRename(oldName: String, newName: String): CommandResult
fun bookmarkSet(name: String, revision: Revision, allowBackwards: Boolean = false): CommandResult
fun bookmarkTrack(bookmark: String, remote: String? = null): CommandResult
fun bookmarkUntrack(bookmark: String, remote: String? = null): CommandResult
```

CLI mappings:
| Method | Command |
|--------|---------|
| `bookmarkCreate("feat", id)` | `jj bookmark create feat -r <id>` |
| `bookmarkDelete("feat")` | `jj bookmark delete feat` |
| `bookmarkForget("feat")` | `jj bookmark forget feat` |
| `bookmarkRename("old", "new")` | `jj bookmark rename old new` |
| `bookmarkSet("feat", id)` | `jj bookmark set feat -r <id>` |
| `bookmarkSet("feat", id, allowBackwards=true)` | `jj bookmark set feat -r <id> -B` |
| `bookmarkTrack("main", "origin")` | `jj bookmark track main@origin` |
| `bookmarkUntrack("main", "origin")` | `jj bookmark untrack main@origin` |

**Note on `bookmarkSet`**: The `allowBackwards` parameter maps to `-B`/`--allow-backwards`. The default (`false`) lets JJ refuse backwards/sideways moves. The plugin calls without `-B` first; if JJ refuses, the plugin can offer the user a force-move option and retry with `allowBackwards=true`.

**Note on delete vs forget**: `delete` marks the bookmark for deletion on the remote (next push propagates deletion). `forget` only removes the local bookmark without propagating. Both are useful — `delete` for cleaning up remote bookmarks, `forget` for removing stale local tracking.

---

### 2. Enhanced BookmarkItem with Commit ID

Currently `BookmarkItem` only stores `ChangeId`. For force-push protection, we need the Git commit ID too.

```kotlin
data class BookmarkItem(
    val bookmark: Bookmark,
    val id: ChangeId,
    val commitId: CommitId,      // NEW: Git commit hash
    val tracked: Boolean,        // NEW: is this a tracked remote ref?
    val synced: Boolean          // NEW: is local in sync with remotes?
)
```

Enhanced `jj bookmark list` template:
```
name ++ "\0" ++ normal_target.change_id() ++ "~" ++ normal_target.change_id().shortest() ++ "~"
  ++ if(normal_target.divergent(), normal_target.change_offset(), "") ++ "\0"
  ++ normal_target.commit_id() ++ "\0"
  ++ if(tracked, "T", "") ++ "\0"
  ++ if(synced, "S", "") ++ "\0"
```

Tested fields available in `jj bookmark list -T`:
- `name` — bookmark name
- `normal_target.change_id()` / `.commit_id()` — target identifiers
- `tracked` — boolean, whether this is a tracked remote ref
- `synced` — boolean, whether local is in sync with tracked remotes

For remote bookmark data, use `jj bookmark list --all-remotes` with additional `remote` field:
```
if(remote, name ++ "@" ++ remote, name) ++ "\0" ++ ...
```

This gives us ALL bookmarks including `main@origin`, `main@github`, etc., each with their own commit IDs. Essential for force-push detection.

---

### 3. Force-Push Protection (Move Bookmark)

This is the most nuanced action. Three levels of protection:

#### Level 1: JJ's Built-in Protection

`jj bookmark set` (without `-B`) refuses backwards/sideways moves based on **commit ID ancestry** in the local DAG. This catches:
- Moving a bookmark to an ancestor of its current position
- Moving a bookmark to a commit that isn't in an ancestor-descendant chain
- Moving to a rewritten commit (different commit ID) even if the change ID is the same

**Verified experimentally**: JJ's check operates on commit IDs, not change IDs. When a parent is rewritten (changing the child's commit ID), JJ correctly detects this as sideways even though the change ID is unchanged.

**Implementation**: Try `bookmarkSet(name, revision)` first. If it fails with "Refusing to move bookmark backwards or sideways", parse the error and offer force-move.

#### Level 2: Remote Force-Push Detection (Plugin-Level)

Even if JJ allows the local move (forward in local DAG), the move may still be a force push relative to **tracked remotes**. This happens when:

1. Someone else pushed changes to the remote
2. You haven't fetched, so your local DAG doesn't reflect the remote's current state
3. Your local move is "forward" locally, but sideways relative to the remote

**Detection**: Before moving, compare the new target's commit ID against tracked remote copies:

```kotlin
// Load all bookmarks including remotes
val allBookmarks = logService.getBookmarksWithRemotes()

// Find remote copies of the bookmark being moved
val remoteTargets = allBookmarks
    .filter { it.bookmark.localName == bookmarkName && it.bookmark.isRemote }
    .map { it to it.commitId }

// For each remote, check if new target is a descendant
for ((remoteBookmark, remoteCommitId) in remoteTargets) {
    if (!isAncestor(remoteCommitId, newTargetCommitId)) {
        // WARN: this would be a force push to this remote
    }
}
```

**Ancestry check**: Use JJ revset — `jj log -r 'OLD::NEW' --no-graph --limit 1 -T ''` where OLD and NEW are commit IDs. If output is non-empty, NEW is a descendant of OLD.

Alternatively, add a `CommandExecutor.isAncestor(ancestor: CommitId, descendant: CommitId): Boolean` method.

#### Level 3: Stale Remote Detection

The plugin can also warn when the local copy of a remote bookmark might be stale:
- Compare `main` (local) with `main@origin` (local cache of remote)
- If they differ in commit ID, the remote may have been updated
- Suggest `jj git fetch` before moving

**Note**: This doesn't catch the case where the remote was updated but we haven't fetched. That's inherently a TOCTOU problem — the only real protection is `jj git push` itself refusing non-fast-forward pushes.

#### Move Action UX Flow

```
User right-clicks row → "Move Bookmark Here..."
    ↓
Plugin shows popup listing all local bookmarks
    (from getBookmarks() — we have all of them)
    ↓
User picks "main"
    ↓
Plugin checks remote positions of "main":
    main@origin: commit abc123
    main@github: commit abc123
    new target:  commit def456
    ↓
Is def456 a descendant of abc123?
    ├── YES → proceed with `jj bookmark set main -r <target>`
    └── NO → show warning dialog:
            "Moving 'main' here would require a force push.
             The bookmark is at commit abc123 on origin/github,
             but the new target def456 is not a descendant.

             [Move Anyway]  [Fetch & Retry]  [Cancel]"
                ↓
            "Move Anyway" → `jj bookmark set main -r <target> -B`
            "Fetch & Retry" → `jj git fetch`, then re-check
```

---

### 4. Track Remote Bookmark Action

When the log shows remote bookmarks (e.g., `main@origin`) that aren't tracked, provide a "Track" action. Tracking creates/updates a local bookmark of the same name.

**Where it appears**:
- Log entry decorations show remote bookmarks like `main@origin`
- Right-click → Bookmarks → `main@origin` → **Track**
- Details panel: clickable `main@origin` → Track

**Data**: Remote bookmarks appear in `LogEntry.bookmarks` as `Bookmark("main@origin")`. The `isRemote` property distinguishes them. The `tracked` field from `BookmarkItem` tells us if it's already tracked.

**Implementation**:
```kotlin
fun bookmarkTrackAction(project: Project, entry: LogEntry?, bookmark: Bookmark?) =
    nullAndDumbAwareAction(
        bookmark?.takeIf { it.isRemote },
        "bookmark.action.track",
        AllIcons.Actions.Download
    ) {
        entry!!.repo.commandExecutor
            .createCommand { bookmarkTrack(target.localName, target.remote) }
            .onSuccess { entry.repo.invalidate() }
            .onFailureTellUser("bookmark.action.track.error", project, log)
            .executeAsync()
    }
```

---

### 5. Bookmark Class Enhancements

Add remote-awareness properties to `Bookmark`:

```kotlin
@JvmInline
value class Bookmark(val name: String) : Ref {
    val isRemote get() = '@' in name
    val localName get() = name.substringBefore('@')
    val remote get() = name.substringAfter('@', "")
    override fun toString() = name
}
```

These properties gate which actions are available in context menus:
- **Local bookmarks**: Create, move, delete, forget, rename
- **Remote bookmarks**: Track, untrack

---

### 6. Context Menu Integration

#### 6a. Log Table Context Menu

In `JujutsuLogContextMenuActions.createActionGroup()`, add a **Bookmarks** submenu:

```kotlin
addSeparator()

val bookmarkGroup = DefaultActionGroup("Bookmarks", true)
bookmarkGroup.templatePresentation.icon = AllIcons.Nodes.Bookmark

// Primary actions
bookmarkGroup.add(bookmarkCreateAction(project, entry))
bookmarkGroup.add(bookmarkMoveAction(project, entry))

// Per-bookmark actions (if entry has bookmarks)
entry?.bookmarks?.let { bookmarks ->
    if (bookmarks.isNotEmpty()) bookmarkGroup.addSeparator()

    bookmarks.forEach { bookmark ->
        val perBookmark = DefaultActionGroup(bookmark.name, true)
        perBookmark.templatePresentation.icon = AllIcons.Nodes.Bookmark

        if (bookmark.isRemote) {
            perBookmark.add(bookmarkTrackAction(project, entry, bookmark))
            perBookmark.add(bookmarkUntrackAction(project, entry, bookmark))
        } else {
            perBookmark.add(bookmarkRenameAction(project, entry, bookmark))
            perBookmark.add(bookmarkDeleteAction(project, entry, bookmark))
            perBookmark.add(bookmarkForgetAction(project, entry, bookmark))
        }

        bookmarkGroup.add(perBookmark)
    }
}

add(bookmarkGroup)
```

Result:
- Right-click any row → **Bookmarks** → **Create Bookmark Here** / **Move Bookmark Here...**
- Right-click row with local `main` → **Bookmarks** → **main** → Rename / Delete / Forget
- Right-click row with remote `main@origin` → **Bookmarks** → **main@origin** → Track / Untrack

#### 6b. Details Panel (Future Enhancement)

Make bookmark names in the details panel clickable, showing the same per-bookmark actions on click. Use `HyperlinkListener` on `IconAwareHtmlPane`.

---

### 7. Bookmark Name Validation

```kotlin
object BookmarkNameValidator : InputValidator {
    override fun checkInput(inputString: String) = canClose(inputString)
    override fun canClose(inputString: String) = inputString.isNotBlank()
        && '@' !in inputString
        && ' ' !in inputString
}
```

Use in `Messages.showInputDialog` calls for create and rename.

---

### 8. Data Flow for Move Action

The move action needs a list of all bookmarks to present as choices. Two options:

**Option A**: Use bookmarks already available from loaded log entries. Iterate all `LogEntry.bookmarks` across the table model. **Problem**: bookmarks on commits outside the loaded revset range won't appear.

**Option B** (preferred): Call `logService.getBookmarks()` (which runs `jj bookmark list`) to get ALL bookmarks. This is a CLI call, so run on pooled thread. **Advantage**: always complete; also provides commit IDs for force-push detection.

For the chooser UI, use `JBPopupFactory.getInstance().createListPopup()` with bookmark names, or a `JBList` in a popup.

---

### 9. Scope and Phasing

**Phase 1 (this issue)**: Core operations
- `Bookmark` class: `isRemote`, `localName`, `remote` properties
- `BookmarkItem`: add `commitId`, `tracked`, `synced` fields
- `CommandExecutor`: create, delete, forget, rename, set (with allowBackwards), track, untrack
- Action factories for each (one file per action in `vcs/actions/`)
- Log context menu: Bookmarks submenu with primary + per-bookmark actions
- JJ-level backwards protection (Level 1): try without `-B`, offer force on failure
- Bookmark name validation
- Message bundle entries
- Unit tests for Bookmark properties, name validation

**Phase 2 (follow-up)**: Force-push protection
- Remote bookmark loading (`--all-remotes` template)
- Commit ID ancestry check
- Pre-move force-push warning dialog (Level 2)
- "Fetch & Retry" flow

**Phase 3 (future)**: Enhanced UX
- Clickable bookmarks in details panel
- VCS main menu action
- Bookmark popup (Approach A) or side panel (Approach C) if requested

### 10. File Changes Summary

| File | Change |
|------|--------|
| `jj/Revset.kt` | Add `isRemote`, `localName`, `remote` to `Bookmark` |
| `jj/BookmarkItem.kt` | Add `commitId`, `tracked`, `synced` fields |
| `jj/CommandExecutor.kt` | Add 7 bookmark methods |
| `jj/cli/CliExecutor.kt` | Implement 7 bookmark methods |
| `jj/cli/CliLogService.kt` | Update bookmark list template for new fields |
| `vcs/actions/bookmarkCreateAction.kt` | New |
| `vcs/actions/bookmarkDeleteAction.kt` | New |
| `vcs/actions/bookmarkForgetAction.kt` | New |
| `vcs/actions/bookmarkRenameAction.kt` | New |
| `vcs/actions/bookmarkMoveAction.kt` | New — includes force-push protection flow |
| `vcs/actions/bookmarkTrackAction.kt` | New |
| `vcs/actions/bookmarkUntrackAction.kt` | New |
| `ui/log/JujutsuLogContextMenuActions.kt` | Add Bookmarks submenu |
| `JujutsuBundle.properties` | Add ~25 message keys |
| Tests | Bookmark properties, name validation, BookmarkItem parsing |

---

## Appendix: Research Findings

### JJ's `--allow-backwards` Behavior (Verified)

JJ's `bookmark set` and `bookmark move` refuse backwards/sideways moves by default. The check operates on **commit IDs** (Git hashes), not change IDs. This means:

- Forward move (new target is descendant): allowed
- Backward move (new target is ancestor): refused, requires `-B`
- Sideways move (no ancestor relationship): refused, requires `-B`
- **Rewrite move** (same change ID, different commit ID due to parent rewrite): **correctly refused** as sideways

Error message: `"Refusing to move bookmark backwards or sideways: <name>"` with hint `"Use --allow-backwards to allow it."`

### `jj bookmark set` vs `jj bookmark move`

- `set`: creates OR updates a bookmark. Simpler API, sufficient for most cases.
- `move`: only moves existing bookmarks. Has `--from` flag for bulk pattern-based moves (e.g., `jj bookmark move --from 'heads(::@- & bookmarks())' --to @-`).
- Both support `--allow-backwards` / `-B`.
- For the plugin, `bookmark set` is the right choice for move operations since we always know the bookmark name and new target.

### Template Fields for `jj bookmark list`

Available fields on bookmark list template context:
- `name` — bookmark name (string)
- `remote` — remote name if `--all-remotes` (string, empty for local)
- `normal_target` — target commit (Option<Commit>), has `.change_id()`, `.commit_id()`
- `tracked` — boolean, whether tracked by a local ref
- `synced` — boolean, whether in sync with tracked remotes
- `tracking_present` — boolean
- `tracking_ahead_count` — SizeHint (not directly printable as string)
- `tracking_behind_count` — SizeHint (not directly printable as string)

### Bookmark Data Sources

| Source | Scope | Has CommitId | Has Remote Info |
|--------|-------|-------|---------|
| `LogEntry.bookmarks` | Only bookmarks on commits in loaded revset | Via parent `LogEntry.commitId` | Remote bookmarks included as `name@remote` |
| `logService.getBookmarks()` | ALL bookmarks in repo | Not currently (needs enhancement) | No (local only) |
| `jj bookmark list --all-remotes` | ALL bookmarks including all remotes | Yes (via template) | Yes |

### Git Plugin Branch Architecture (for Reference)

The Git plugin has a much more elaborate system:
- **Branch popup**: `GitBranchesTreePopupOnBackend` with tree model, search, prefix grouping, favorites
- **Branches panel**: `BranchesDashboardTreeComponent` in VCS log side panel
- **Status bar widget**: Shows current branch, click opens popup
- **Ref types**: HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG with colored labels
- **Actions**: Checkout, create, rename, delete, merge, rebase, push, pull, track, compare

This is disproportionate for JJ bookmarks. The context menu approach is more appropriate for v1.
