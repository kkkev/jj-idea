# Design: Drag-and-Drop Graph Operations (jj-idea-6oeg)

## Context

GitHub issue [#93](https://github.com/kkkev/jj-idea/issues/93) (CaptaiNiveau) reports that
[gg](https://github.com/gulbanana/gg) and [VisualJJ](https://www.visualjj.com/) both
support drag-and-drop in their commit graphs — squash, rebase (onto/after/before),
duplicate, bookmark repositioning — while jj-idea is dialog-per-action. The user calls this
their top competitive gap; lazygit has a similar keybind to move commits up/down, and there
is broader community discussion at
[HN](https://news.ycombinator.com/item?id=42262047).

jj-idea-6oeg is filed as a **spike**: the acceptance criteria are a feasibility writeup
plus scoped follow-up beads, not an implementation. This document is that writeup.

### Existing infrastructure surveyed

**Command layer** (`jj/CommandExecutor.kt`, implemented in `jj/cli/CliExecutor.kt`):
`rebase(revisions, destinations, sourceMode, destinationMode)` (:288), `duplicate(revisions,
destinations, destinationMode)` (:144), `squashInto(sources, destination, filePaths, ...)`
(:345), `split(revision, filePaths, description, parallel, insertBefore)` (:396),
`bookmarkSet(name, revision, allowBackwards)` (:205), `edit(revision)` (:133). Pure arg
builders (`rebaseArgs` CliExecutor.kt:372, `duplicateArgs` :359, `squashIntoArgs` :275,
`splitArgs` :211, `bookmarkSetArgs` :65) are unit-tested independently of the executor.
`RebaseSourceMode`/`RebaseDestinationMode` enums live in `jj/Revset.kt:171-192`.

**Hit-testing**: `JujutsuLogTable.clickTargetAt(MouseEvent): LogClickTarget?`
(`ui/log/JujutsuLogTable.kt:419`) is the table's single point→target mapper, backed by
`LaidOutCell.forRow(...)`/`linkTargetAt(localX)` (`ui/log/LaidOutCell.kt:45,75`), shared
between painting and hit-testing by design. `LogClickTarget` (`ui/log/LogClickTarget.kt`)
is a sealed interface whose own KDoc states the extension principle this design leans on:
*"Adding a new link kind means adding one variant plus one branch in each of these — not
hunting through every renderer that might show a link."*

**Preview/guard logic**: `RebaseSimulator.simulate(...)` (`ui/rebase/RebaseSimulator.kt:31`)
is a pure function producing a reparented, topologically-sorted entry list, generic over
`DagNode<T>`. `GraphNode.highlightColor` (`ui/log/JujutsuCommitGraph.kt:36`) is an existing
full-row overlay hook, painted at `JujutsuGraphAndDescriptionRenderer.kt:237-243`; its only
current user is `RebasePreviewPanel.kt:92-98` (rebase-dialog source/destination tinting).
`invalidDestinationIds(entries, mode)`/`validPlacementModes(...)`
(`ui/duplicate/DuplicateImmutabilityGuard.kt:37,49`) already compute illegal destinations
per placement mode in one pass. `RebaseSimulator.excludedDestinationIds` (:87) is the
existing cycle guard.

**Rendering surfaces**: the log table (`ui/log/JujutsuLogTable.kt`), the bookmarks panel — a
plain `com.intellij.ui.treeStructure.Tree` (`ui/log/bookmarks/JujutsuBookmarksPanel.kt:52`)
installed beside the log table in a splitter
(`ui/common/CommitTablePanel.kt:installLeftComponent:639`) — with leaves already typed as
`BookmarkNode.Local`/`.Remote`/`.Tag`/`.WorkingCopy` (`BookmarkTreeModel.kt:37-77`); the
commit details panel, an `IconAwareHtmlPane : JBHtmlPane`
(`ui/components/IconAwareHtmlPane.kt:37`); and the changes tree,
`JujutsuChangesTree : AsyncChangesTreeImpl.Changes` (`ui/common/JujutsuChangesTree.kt:36`),
i.e. a platform `ChangesTree`.

**Undo**: none exists today. This is significant enough to drive a design decision below,
and is broken out into its own document — see
[Undo Support Roadmap](undo-support-roadmap.md).

**Immutability**: `LogEntry.immutable` (`jj/LogEntry.kt:27`), enforced today at the action
level (`ui/log/JujutsuLogContextMenuActions.kt:108-144`) and predicted in dialogs via
`DuplicateImmutabilityGuard`.

**Platform DnD API** (intellij-community): `com.intellij.ide.dnd.DnDSupport.createBuilder(
component).setBeanProvider{}.setTargetChecker{}.setDropHandler{}.setImageProvider{}
.setDisposableParent(disposable).install()` — the shape used by
`ChangesTreeDnDSupport.install` (`platform/vcs-impl/shared/.../ChangesTreeDnDSupport.java`).
`DnDEvent.setDropPossible(possible, reason)`, `setHighlighting(RelativeRectangle, type)`
with `DropTargetHighlightingType { RECTANGLE=1, FILLED_RECTANGLE=2, H_ARROWS=4, V_ARROWS=8
}`, `hideHighlighter()`. `RowsDnDSupport` (`platform/platform-api/.../RowsDnDSupport.java`)
is the platform's own row-drag helper — examined in detail below and found unusable here,
but its `RefinedDropSupport.Position { ABOVE, INTO, BELOW }` and its highlighting choices
(a 2px `FILLED_RECTANGLE` at the row boundary for ABOVE/BELOW, a `RECTANGLE` around the row
for INTO) are directly reused as the visual vocabulary.

## Approaches Considered

### A: Menu on drop

Every drop opens a popup listing the applicable operations for that source/target pair.

**Pros**: never ambiguous, maximally discoverable, trivially extensible to new operations.
**Cons**: adds a click after every drop, which defeats the fluidity that motivated the
issue in the first place. Also not an IntelliJ idiom — this is the Windows Explorer
right-drag pattern, not something the platform's own DnD call sites do.

### B: Graph-geometry mapping

Drop precisely on a commit's circle = onto; drop on the line/edge between two commits =
insert. The most semantically honest mapping for a *graph* specifically, and closest to
what gg does.

**Pros**: visually intuitive once learned; matches the mental model of "the graph is the
UI." **Cons**: `COMMIT_RADIUS` is 4px (`JujutsuGraphAndDescriptionRenderer.kt:38`) — a
target roughly a third the width of a mouse cursor's hotspot tolerance, and far smaller
than the already-tight edge bands discussed below. Fails Fitts's-law scrutiny worse than
any row-based scheme.

### C: Row zones + payload-typed dispatch (Recommended)

Each row is split into geometric zones (a large centre "onto" region, thin top/bottom
"insert" edges); each *kind* of thing being dragged (a commit, a bookmark chip, a file
selection, the working-copy marker) determines the operation for a given zone, rather than
requiring a modifier key to pick between unrelated operations.

**Pros**: matches the platform's own idiom (`RowsDnDSupport`'s zone-based ABOVE/INTO/BELOW,
`ChangesTreeDnDSupport`'s payload-typed handling), reuses `setHighlighting` directly for
feedback, needs no new vocabulary, and — see the payload/target model below — turns "add a
new draggable surface" into "add one hit-test," not "add a new operation."
**Cons**: the row-zone geometry at typical row heights is genuinely tight (discussed in
detail below) and needs care (hysteresis, and later, adaptive sizing) to feel good.

### D: Modifier keys for everything

Ctrl = squash, Shift = insert-before, Alt = duplicate, plain = rebase-onto, etc.

**Pros**: no geometry to get right. **Cons**: entirely invisible until memorized, and
collides with the log table's existing Ctrl/Shift multi-select semantics — a user
Ctrl-clicking to build a multi-selection before starting a drag would have that same Ctrl
reinterpreted mid-gesture.

## Recommendation: C — Row zones + payload-typed dispatch

## Design

### 1. Gesture model: a payload, a target, and one handler per pair

The correct abstraction is **not** per-surface: a bookmark chip is a bookmark chip whether
it is dragged from a log-table chip, the bookmarks panel, or (later) the details panel, and
a commit row is a commit row target regardless of which surface's drag started the gesture.
Model it as two small sealed hierarchies plus one dispatch table:

```kotlin
sealed interface DragPayload {
    data class Commit(val entries: List<LogEntry>) : DragPayload
    data class BookmarkRef(val bookmark: Bookmark) : DragPayload
    data class TagRef(val tag: Tag) : DragPayload
    object WorkingCopyRef : DragPayload
    data class Files(val changes: List<Change>) : DragPayload
}

sealed interface DropTarget {
    data class CommitRow(val entry: LogEntry) : DropTarget          // centre band: ONTO
    data class Gap(val entry: LogEntry, val edge: Edge) : DropTarget // edge band: BEFORE/AFTER
    data class RefChip(val bookmark: Bookmark) : DropTarget          // log chip OR bookmarks-panel node
}
```

Each *surface* contributes only two things: a hit-test producing a `DragPayload`, and a
hit-test producing a `DropTarget`. The operation is a pure function of `(payload, target)`,
decided in exactly one place — mirroring how `LogClickTarget.resolve` already centralizes
click resolution:

| Payload ↓ / Target → | `CommitRow` (centre/ONTO) | `Gap` (edge/BEFORE-AFTER) | `RefChip` (local) | `RefChip` (remote) |
|---|---|---|---|---|
| Commit | `rebase` (plain) / `duplicate` (copy mod) | `rebase -B` / `-A` | `bookmarkSet` | — |
| BookmarkRef (local) | `bookmarkSet` | — | — | `gitPush` *(dialog)* |
| TagRef | `tagSet` | — | — | — |
| WorkingCopyRef | `edit` | — | — | — |
| Files | `squashInto(filePaths)` *(dialog)* | `split(insertBefore)` *(dialog)* | — | — |

Payload-producing surfaces: the log-table chip and commit cell, the bookmarks-panel tree,
the working-copy `@` indicator, and the changes tree. Adding a new surface later is one
hit-test, never a new row in the operation matrix.

**Because `RefChip` is a target *kind*, not a surface, "chip → chip" push works entirely
within the log table.** Remote bookmarks are already parsed into `LogEntry.bookmarks` and
rendered as chips — `remoteBookmarkTemplate` emits `name@remote` and is joined with the
local template into each entry's ref list (`jj/cli/CliLogService.kt:328-331`), and
`Bookmark.isRemote` distinguishes the two. When local is ahead of remote, `main` and
`main@origin` sit on different rows; dragging one chip onto the other is a natural in-log
push, no bookmarks panel required. Caveats: when local and remote are in sync both chips
render on the same row (the drag is degenerate — but pushing an already-synced bookmark is
a no-op regardless), and if `main@origin` falls outside the loaded revset it simply is not
present as a target; this is an accelerator alongside the bookmarks-panel path, not a
replacement for it.

**Files dropped in a gap split rather than squash.** `splitArgs` already accepts exactly
what this gesture produces: `split -r <revision> [-B <insertBefore>] [--message]
[--parallel] <filesets>` (`jj/cli/CliExecutor.kt:211-230`). So the same zone model serves
two operations for a `Files` payload: dropping on a row's **centre** squashes those files
into that commit (`squashInto`); dropping in a **gap** splits them out of their own change
into a brand-new one at that position (`split(revision = owningChange, filePaths = files,
insertBefore = targetGap)`). One geometry, two operations, chosen by payload type — the
kind of unification the payload/target model was built to produce. Constrain it: a split
is only meaningful at a gap bordering the files' *own* change, so every other gap must be
rejected via `setDropPossible(false, "Files can only be split out next to their own
change")`.

Two surfaces need a caveat recorded rather than a bead filed against them directly:

- **Details panel** as a payload source is real future work, not free: it renders through
  `IconAwareHtmlPane : JBHtmlPane`, a `JEditorPane` with its own built-in text-selection
  drag gesture. Making a chip inside it draggable means suppressing that pane's
  `TransferHandler` for drags starting on a chip (hit-tested via `viewToModel`), while
  leaving ordinary text selection working everywhere else in the pane. This is a genuinely
  small bead *once the payload/target model exists*, because the operation logic needs no
  new code — only the hit-test does.
- **Tooltips cannot be a drag source, structurally.** `installIconAwareTooltip` drives
  `IdeTooltipManager`, which hides the tooltip on mouse move
  (`IconAwareTooltip.kt:94`, `hideCurrent(e)`) — the very gesture that would start a drag
  dismisses the tooltip first. This is a mechanical constraint of the platform tooltip API,
  not a product decision to reconsider later.

### 2. Drop zones on a commit row

A three-way vertical split of each row: **centre half → ONTO**, top quarter →
INSERT_BEFORE, bottom quarter → INSERT_AFTER. ONTO gets the largest share deliberately — it
is overwhelmingly the common operation, and a naive thirds-split would instead make it the
*hardest* target to hit, exactly backwards from what Fitts's law says a UI should do for its
most frequent action. Since before/after each own a single boundary (top or bottom) while
onto owns the entire middle, a half/quarter split gives each of the three targets a
proportionate share of the row given their relative frequency of use.

At the 22px default row height (`Jujutsu.Graph.rowHeight`,
`JujutsuGraphAndDescriptionRenderer.kt:38`), a literal quarter is ~5.5px — a genuinely tight
target. The recommended implementation is a scaled, clamped band rather than a bare
proportion:

```kotlin
val band = min(JBUI.scale(6), rowHeight / 4)   // never lets the centre drop below half
val position = when {
    dy < band            -> INSERT_BEFORE
    dy > rowHeight - band -> INSERT_AFTER
    else                  -> ONTO
}
```

**Feedback reuses stock platform primitives** — "highlight the gap between commits" is not
a bespoke invention. `RowsDnDSupport` already paints ABOVE/BELOW as a 2px
`FILLED_RECTANGLE` at the row boundary, and INTO as a `RECTANGLE` around the whole row
(`RowsDnDSupport.java:92-103`); both are exposed via `DnDEvent.setHighlighting`. This
design adopts the identical visual vocabulary — a boundary line for insert, a row outline
for onto — while implementing its own zone math (see §5 for why `RowsDnDSupport` itself
cannot be reused wholesale).

**Correctness trap, worth recording explicitly:** the log table shows a *linearised* view
of the DAG, so table-adjacent rows are not necessarily DAG-adjacent. "Insert before row N"
and "insert after row N+1" coincide only when N+1 is N's sole parent and N is N+1's sole
child — at forks, merges, and disjoint heads they do not, and conflating them would produce
the wrong rebase destination. The zone must therefore be bound **per row, not per visual
gap**: the top band of row N always means `rebase -B N`, the bottom band always means
`rebase -A N`, regardless of what happens to be drawn immediately above or below on screen.
Each of these is well-defined on its own; the indicator line is still drawn at the
corresponding row boundary for visual continuity, and
`setDropPossible(true, "Insert before abc123")` names the actual operation so there is no
ambiguity even when the visual gap is shared between two different meanings.

### 3. Immediate application vs. a confirmation dialog

Rule: **confirm iff the drop can destroy content or reach outside the repository;
otherwise apply immediately and offer undo.**

| Operation | Treatment | Why |
|---|---|---|
| Rebase (onto / -A / -B) | Immediate + undo balloon | Pure DAG move; jj materialises conflicts as state rather than failing, so there is nothing a confirmation would meaningfully ask about. |
| Duplicate | Immediate + undo balloon | Purely additive — never rewrites the source change. |
| Bookmark / tag move | Immediate + undo balloon | jj itself already refuses backwards/sideways moves; `actions/bookmark/moveBookmarkAction.kt:36-42` already parses that stderr and re-prompts with `-B`. Reused verbatim. |
| Working-copy `@` move | Immediate + undo balloon | Moves a pointer; jj snapshots first. `ui/statusbar/JujutsuWorkingCopySwitcher.kt:41-48` already confirms when the target is immutable. Reused verbatim. |
| Squash | **Dialog** — pre-filled `SquashIntoDialog` | Merges content, abandons the emptied source, and combines descriptions; genuinely destructive. |
| Split (files → gap) | **Dialog** — pre-filled `SplitDialog` | Same destructiveness class as squash — rewrites the source change's content. |
| Push | **Dialog** — pre-filled `GitPushDialog` | Network, outward-facing, can force-push. See §7. |

Rather than layering interim confirmation dialogs onto the immediate-apply operations until
undo ships (which would then have to be removed, churning the UX twice), this design makes
**undo a blocking prerequisite** of the DnD gesture beads. The idiomatic IntelliJ answer to
"an action just happened, let the user reverse it" is post-hoc: apply, then show a balloon
with an inline **Undo** action — not a pre-action modal. The notification infrastructure for
this already exists and needs no new plumbing:
`Notification.addExpiringAction(messageKey) { ... }` (`ui/services/JujutsuNotifications.kt:27`)
over the existing `Jujutsu` BALLOON notification group (`plugin.xml:70`).

Making undo safe to expose per-drop is not itself trivial — see
[Undo Support Roadmap](undo-support-roadmap.md) for the full design, including why the
naive approaches (`jj undo`, `jj op restore`) are actively unsafe under concurrent
repository access (e.g. a background AI agent), and why `jj op revert` identified by
operation-log parent linkage is the correct primitive. Only that document's Stage 1 blocks
this design; Stages 2–4 (first-class Undo/Redo actions, an operation-log browser, and
whether to bind Cmd+Z) are independent follow-on work.

### 4. Adaptive drop zones — research

Under review, the static quarter/half split raised a further question: could the zones grow
toward whichever edge the pointer is approaching, the way a human target acquisition
naturally has a fast **ballistic** phase followed by slow **corrective submovements**
(Meyer et al.'s optimized-submovement model, the mechanism underlying Fitts's law) — so that
slowing down or reversing direction near a boundary is itself a legible signal of
deliberate fine-targeting?

Surveying the published pointing-facilitation literature for the closest fit:

- **Bubble cursor** (Grossman & Balakrishnan, CHI 2005) — a dynamic-area cursor that always
  captures the *nearest* target; equivalent to a Voronoi tessellation of target space. In
  1-D with contiguous, gap-free zones, the Voronoi partition of three zone centres is
  **exactly the static split already proposed** — plain (unweighted) Voronoi contributes
  nothing new here. A *weighted* variant (an additive/power-diagram Voronoi, i.e. giving one
  zone a bigger "pull") reduces in 1-D to simply relocating the boundaries, which is the
  variable-boundary family below, reached by a more roundabout route.
- **DynaSpot** (Chapuis, Labrune & Pietriga, CHI 2009) — a speed-dependent area cursor: the
  capture area expands with pointer speed and collapses to a point when the pointer is
  slow. **This is the closest published match to what was proposed**, and its core
  insight — fast motion implies coarse intent, slow motion implies precise intent — is
  exactly the signal described.
- **Semantic pointing** (Blanch, Guiard & Beaudouin-Lafon, CHI 2004) — expands a target in
  *motor* (control) space via control-display gain reduction, while the target's *visual*
  size stays unchanged. Principled and well-studied, but its whole mechanism is
  invisibility — a poor fit here, where the drop indicator is already visible and doing the
  same signalling job openly.
- **Kinematic endpoint prediction** (Lank, Cheng & Ruiz, CHI 2007) — infers the intended
  target from the motion profile (early trajectory, deceleration curve) before the pointer
  arrives. Substantial machinery for a three-zone, single-row decision — overkill here.
- **Hysteresis / Schmitt-trigger thresholding** — hold the currently-selected zone until the
  pointer moves a fixed threshold *past* the boundary, rather than switching exactly at the
  boundary. The simplest and most predictable of the group, and composable with any of the
  above.

**Synthesis, recommended for a later bead:** a DynaSpot-style rule restated for 1-D —
while the pointer is moving quickly and monotonically down the row list, hold ONTO (the
user is evidently heading toward a row, not hunting for a boundary); once vertical speed
drops below a threshold, or the direction of travel reverses, grow the nearer edge band
from its resting `JBUI.scale(6)` toward roughly `JBUI.scale(10)`. Compose this with
hysteresis throughout so the zone assignment cannot flicker.

**Why adaptivity is affordable here, worth stating explicitly:** the drop indicator is
*always visible* during the drag. A misprediction is immediately apparent to the user and
correctable with a couple of pixels of pointer movement — which is precisely what makes
this safe in a way an invisible motor-space scheme like semantic pointing would not be for
this UI (semantic pointing's whole premise is that the user cannot see the adjustment).

**Recommendation: do not build this in v1.** Ship the static quarter/half split with
hysteresis first, so the base gesture can be validated on its own before adding behaviour
that is hard to tune and easy to make feel erratic or "possessed." File adaptive zones as
its own follow-up bead with acceptance criteria that make it testable independent of a
running IDE: a deterministic unit test driven by a **synthetic pointer trace** — a fixed
sequence of `(t, y)` samples — asserting the resulting sequence of zone selections. That
directly answers the natural objection to shipping speed-dependent UI behaviour: that it is
otherwise untestable without a human at a mouse.

### 5. Why `RowsDnDSupport` cannot be reused (correcting a natural assumption)

It is tempting to assume the platform's existing row-drag helper,
`com.intellij.ui.RowsDnDSupport`, is directly applicable — after all, it already implements
exactly an ABOVE/INTO/BELOW zone split with the highlighting behaviour this design wants.
It is not usable here, for a structural reason rather than a superficial one:
`RowsDnDSupport.install` requires the table's model to implement
`com.intellij.util.ui.EditableModel` (`addRow`/`removeRow`/`exchangeRows`/`canExchangeRows`)
— **list-reordering** semantics, where a drop means "move this row to index N." A commit
drop means "rebase onto revision X," which is not an index move at all: the log table's row
order is *derived* from the DAG topology, not an author-controlled ordering the model can
freely rewrite. `RowsDnDSupport.install` also unconditionally calls `setDragEnabled(true)`
and installs a `TransferHandler` on the component, which would conflict with
`JujutsuLogTable`'s substantial existing hand-rolled mouse-event handling
(`JujutsuLogTable.kt:190-357`).

So the design builds directly on `DnDSupport.createBuilder(...)`, exactly as
`ChangesTreeDnDSupport` already does elsewhere in the platform (it likewise implements its
own hit-testing and its own highlighting, rather than delegating to `RowsDnDSupport`). What
*is* reused either way is only the **painting** primitive, `setHighlighting`. Given that,
the zone arithmetic is identical work regardless of the split proportion chosen — quarters
vs. thirds is a one-line constant, with zero fragility difference between them:

```kotlin
val band = min(JBUI.scale(6), rowHeight / 4)   // a thirds split would instead be: rowHeight / 3
```

The genuinely new work — present at *any* split proportion, and not a consequence of
choosing quarters — is: making the table's hit-testing y-aware (`clickTargetAt` is x-only
today, see the survey above), the hysteresis logic, and expressing the drop indicator as a
`RelativeRectangle` correctly against the table's scrolled viewport. None of these are
deep, and none of them are made harder or easier by the quarter/half choice specifically.

**Will three zones on one row feel odd to use?** It should read as clearer, not odder: ONTO
draws a rectangle around the whole row, BEFORE/AFTER draw a line at the row boundary — the
same two-way visual distinction the platform's own `RowsDnDSupport` makes for exactly this
kind of gesture — plus a tooltip naming the resolved operation. Whichever zone the pointer
is in should be visually unambiguous at every moment of the drag.

### 6. Guards

All of the following are enforced inside `DnDTargetChecker.update` via
`setDropPossible(false, <reason>)`, so violations surface **during** the drag — a reject
cursor plus an inline error tooltip — rather than as a raw CLI error only discovered after
the mouse button is released:

- **Immutability** — reuse `invalidDestinationIds(entries, mode)`
  (`DuplicateImmutabilityGuard.kt:37`) and `entry.immutable` directly; no new logic needed.
- **Cycles** — reuse `RebaseSimulator.excludedDestinationIds` (`RebaseSimulator.kt:87`).
- **Cross-repository drops** — the log is multi-root (`UnifiedJujutsuLogPanel`); any drop
  where `target.repo != payload.repo` must be rejected. This is easy to overlook and is
  called out explicitly for that reason.
- **Self-drop** — source equals target is a no-op; per the comment in
  `RowsDnDSupport.java`, a drag gesture always starts with `new == old`, so this case must
  not present a rejecting cursor on gesture start, only once the pointer actually moves
  somewhere invalid.

**How an invalid drop is signalled to the user** is not something this design needs to
invent — the platform provides a complete three-part treatment for free, from
`DnDManagerImpl`:

1. A **reject cursor**, via `getAction().getRejectCursor()`, whenever
   `setDropPossible(false)` is in effect (`DnDManagerImpl.java:377`).
2. **No drop indicator** — `hideHighlighter()` is called automatically, so no line or
   rectangle is drawn for an invalid target.
3. **An inline reason tooltip** — if the message passed to `setDropPossible(false, msg)` is
   non-empty, it is rendered in an error style on a short timer
   (`isMessageProvided` → `queueTooltip` → `Highlighters.show(ERROR_TEXT, ...)`,
   `DnDManagerImpl.java:499-521`).

The platform's own existing callers (`ChangesTreeDnDSupport`, `RowsDnDSupport`) pass an
empty string and so get only the reject cursor, with no explanation. Passing a real,
specific reason — `"Cannot rebase across repositories"`, `"abc123 is immutable"`, `"That
would create a cycle"` — is a strict improvement using exactly the mechanism the API was
built for: its own fallback string, used when no reason is supplied at all, is literally
*"You cannot drop anything here"* (`DnDManagerImpl.java:575`). It also matches
contributing.md's existing tenet that action-availability hints belong in explanatory text
rather than in icon changes or silent disabling.

### 7. Push by drag — dialog-gated, never direct

The project has already made exactly this call for the closely analogous per-bookmark push
action. From `GitPushDialog`'s own KDoc on its `initialBookmark` parameter:

> *"Lets a per-bookmark push still go through this dialog's review step (mutating a remote
> is not something to fire with no confirmation) while skipping the repo/remote/bookmark
> selection clicks a fresh dialog would otherwise need."*

The precedent, the policy, and the API all already exist:
`actions/bookmark/pushBookmarkAction.kt` pre-fills `GitPushDialog(initialBookmark,
initialRemote, ...)` today. A drag-and-drop push is the identical flow with a different
trigger — drag a local bookmark chip onto its `name@remote` chip (in the log, per §1, or
onto the corresponding `BookmarkNode.Remote` in the bookmarks panel), which resolves both
the bookmark and the remote from the gesture itself, leaving the dialog open with nothing
left to choose but confirmation. "Opens a pre-filled dialog, never pushes directly" should
be treated as a hard constraint on this bead's acceptance criteria, not a preference: it is
a network operation, it is outward-facing, it can force-push, and the project's own
operating rules already require asking before any push.

### 8. Considered and rejected: a bin / drag-to-delete target

A drop target styled as a trash bin, for dragging a change onto to abandon it, was
considered and is **not recommended**. A full-tree search of `platform/` and `plugins/` in
intellij-community for drag-to-delete affordances (`dropToDelete`, `deleteOnDrop`,
`trashDrop`, `dragToDelete`, `removeOnDrop`) returned **zero matches**. IntelliJ has no
trash-can drop-target pattern anywhere in the product; deletion is always an explicit,
confirmed action (Delete key, context menu item). A bin here would introduce a vocabulary
the IDE does not otherwise use.

It is also a poor fit for this specific situation: abandoning a change is destructive, the
plugin has no in-IDE undo today (see [Undo Support Roadmap](undo-support-roadmap.md)), and
a bin is a uniquely risky UI element to add during a drag gesture precisely because it is
typically a large, stationary target that an imprecisely-aimed drag can land on by accident
— worse than any of the row zones discussed above, which are all small and require deliberate
aim. There is no capability gap to close, either:
`actions/change/abandonChangeAction.kt` already exists and already confirms with the user
whenever the target change has file modifications or a description (:21-34).

### 9. Performance

`DnDTargetChecker.update` fires on **every mouse-move event during a drag** — this is the
implementation risk most likely to bite in practice, more than any of the gesture-design
questions above. `clickTargetAt` already rebuilds a `LaidOutCell` on every call today, and a
naive "live preview of the resulting graph" would additionally re-run
`RebaseSimulator.simulate` over the entire loaded log on every pixel of pointer movement —
against a stated scale envelope of roughly 100k commits (contributing.md § Performance &
Scale).

- v1 feedback is limited to the **zone highlight plus the `setDropPossible` reason
  tooltip**, both strictly O(1) per move — no live graph re-simulation during the drag
  itself.
- If a live preview is pursued later (see the deferred bead below), it must be memoised on
  `(payload, targetId, position)` so the simulation runs once per zone *change*, not once
  per pixel of pointer movement.
- Per the contributing.md rule, any future bead that introduces a per-commit loop on this
  path must ship an operation-count test, following the existing exemplars
  (`ui/log/graph/GraphLayoutScaleTest.kt`, `jj/RepoLogCacheScaleTest.kt`) — assert on
  operation counts, never wall-clock time.
- `SmoothAutoScroller.installDropTargetAsNecessary(component)` will be needed so a drag can
  reach a row currently scrolled off-screen in a long log.

## Scope and Phasing

This document itself proposes **no implementation** — per jj-idea-6oeg's acceptance
criteria, its deliverable is this writeup plus the follow-up beads below. Each bead's
detailed scope is in its own beads description; this table is the dependency map.

**Undo track** — Stage 1 is a hard blocker for every immediate-apply drop bead; the rest is
independently valuable and does not block DnD. Full detail in
[Undo Support Roadmap](undo-support-roadmap.md).

| Bead | Depends on |
|---|---|
| Undo our own operation via `jj op revert` + balloon action | — |
| First-class Undo/Redo actions naming the operation | Undo-our-own-operation |
| Operation log UI (`jj op log`, revert per entry) | Undo-our-own-operation |
| Undo balloon for all mutating jj actions | Undo-our-own-operation |

**DnD track**:

| Bead | Depends on |
|---|---|
| DnD core: payload/target model, y-aware hit-test, zones, indicator, guards | — |
| Commit → commit: rebase (onto / before / after) | core, undo |
| Copy-modifier drag to duplicate | core, undo |
| Bookmark/tag chip → commit: move the ref | core, undo |
| Chip → chip: push a local bookmark onto its remote (log-only) | core |
| Files → commit centre: squash | core |
| Files → gap: split into a new change at that position | core, files-squash |
| Bookmarks panel as payload source and drop target | core |
| Details panel as payload source | core |
| Working-copy `@` draggable → `jj edit` | core, undo |
| Bookmark → remote node: push (bookmarks panel) | bookmarks-panel |
| Adaptive drop zones (speed/reversal-weighted bands) | core, rebase-onto shipped |
| Live graph preview during a drag | core, rebase-onto shipped |

A drag-to-delete bin (§8) was considered and rejected — recorded here rather than filed as
a bead.

## Appendix: Verified References

- jj CLI behaviour verified against locally installed `jj 0.44.0`: `jj help rebase`,
  `jj help squash`, `jj help duplicate`, `jj help split` (via `splitArgs` cross-check),
  `jj op log --help`, `jj op revert --help`, `jj op restore --help`, `jj undo --help`.
- Platform DnD API verified by reading intellij-community source directly:
  `platform/ide-core/src/com/intellij/ide/dnd/DnDEvent.java`,
  `platform/platform-impl/src/com/intellij/ide/dnd/DnDManagerImpl.java`,
  `platform/platform-api/src/com/intellij/ide/dnd/DnDSupport.java`,
  `platform/platform-api/src/com/intellij/ui/RowsDnDSupport.java`,
  `platform/vcs-impl/shared/src/com/intellij/openapi/vcs/changes/ui/ChangesTreeDnDSupport.java`.
- Drag-to-delete search: `grep -rli 'dropToDelete\|deleteOnDrop\|trashDrop\|dragToDelete\|
  removeOnDrop' platform/ plugins/` in intellij-community — zero matches.
- Undo precedent search: `grep -rln UndoManager plugins/git4idea/` — zero matches; the sole
  platform-wide `UndoableAction` consumer in a VCS context is
  `platform/vcs-impl/src/com/intellij/openapi/vcs/ex/PartialLocalLineStatusTracker.kt`.
- HCI literature cited in §4: Grossman & Balakrishnan, "The Bubble Cursor" (CHI 2005);
  Chapuis, Labrune & Pietriga, "DynaSpot" (CHI 2009); Blanch, Guiard & Beaudouin-Lafon,
  "Semantic Pointing" (CHI 2004); Lank, Cheng & Ruiz, "Endpoint Prediction Using Motion
  Kinematics" (CHI 2007); Meyer et al.'s optimized-submovement model as the standard
  account of Fitts's-law-governed pointing (ballistic phase + corrective submovements).
