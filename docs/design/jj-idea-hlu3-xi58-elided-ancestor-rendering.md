# Graph edge honesty and long-edge navigation — cross-bead plan

**Beads:** `jj-idea-xi58` (unloaded vs elided parents), `jj-idea-1pgy` (mixed merge),
`jj-idea-hlu3` (filtered-ancestor remap), plus the long-edge navigation bead created alongside
this revision. Supersedes this document's previous "fetch-extended remap" recommendation — see
§ Superseded approach at the end for why.

## Context

`jj-idea-2c8k` (paged log loading, GitHub #69) shipped a wiggly-line stub for rows whose parent
isn't in the loaded set, replacing a connector-less row that looked pixel-identical to a true
repository root (`JujutsuGraphAndDescriptionRenderer.shouldDrawElidedStub` /
`drawElidedParentStub`). That stub is **not** preview-gated — it fires on
`GraphNode.hasElidedParents` for every user, and `CHANGELOG.md`'s `[Unreleased]` already
advertises it.

Two problems with it:

1. **It says the wrong thing.** A wiggle echoes jj's `~`, which means *elided* — history exists
   here and has been deliberately skipped. In the paging case the parent isn't elided, it simply
   **hasn't been loaded yet**. `RowLayout.hasElidedParents` is a single Boolean flattening two
   genuinely different situations: "loaded, but hidden by the current filter" and "not fetched".
2. **It offers no way out.** A marker saying "history continues, unseen" is useless without a way
   to say *"show me where this line goes."*

The second problem generalises well beyond unresolved parents: **any** edge whose other end is
off-screen has the same question and no answer today, in both directions — down to a distant
parent, and up to a distant child. So the fix is one uniform interaction over long edges, with the
unloaded-parent case as its most degenerate instance (the other end doesn't exist locally yet, so
we load it first).

## The constraint that rules out the obvious alternatives

**The frontier can never be eliminated, only moved** — every mechanism that loads more history
creates a new boundary one layer down. And **connecting a row to a distant ancestor is actively
harmful**: on a wide repo the missing parent is much older, so in jj's timestamp ordering it sits
thousands of rows down, and drawing that connector means holding a passthrough lane across the
whole span — precisely the lane leak `LayoutCalculator.kt:89-97` deliberately refuses. This is why
jj CLI itself elides with `~` rather than connecting.

So: **don't draw the connection — state the situation honestly and offer navigation.**

Rejected on that basis, recorded so they aren't re-proposed:

| Approach | Why not |
|---|---|
| Placeholder rows for unloaded parents, holding a lane | Count is proportional to the frontier (capped at 8,000); placing them either breaks jj's recency ordering or clusters them dishonestly; reintroduces the unterminated-passthrough lane leak |
| Prefetch parents just before their children scroll into view | The eager one-page-ahead prefetch already does this. Doesn't address the real case: the design doc records the wave appearing "independent of scroll position", because the parent ranks pages away by *age*, not screen position |
| Batched resolve of pending parents for visible rows | Cheap query, but makes the data correct and the picture worse — the distant-connector lane problem above |
| Full-DAG topology preload + lazy detail fetch (what IntelliJ's own VCS Log does) | jj-idea-2c8k phase 1 measured that jj's per-invocation walk over N commits dominates and *barely depends on the template's field list* — a "cheap" ids+parents pass over the whole repo isn't cheap here. Remains the honest long-term answer if the problem persists |
| Topologically-closed pages (`ancestors(frontier, depth)`) | Abandons jj's recency ordering, which jj-idea-2c8k validated as inherited for free |

## The design

### 1. Replace the Boolean with a two-state per-parent classification (`jj-idea-xi58`)

`RowLayout.hasElidedParents: Boolean` → per-parent state on the parents that got no lane:

- **`NOT_LOADED`** — the parent isn't in `allEntries` at all (paged-window boundary, or beyond a
  non-paged configured limit). *Not* elided. Paint: a **faded straight stub** in the row's own
  lane — "the line continues, we just haven't drawn the rest".
- **`HIDDEN`** — the parent *is* in `allEntries` but filtered out of the rendered subset. Genuine
  elision, jj's `~`. Paint: keep today's **wiggle**.

`CommitGraphBuilder.buildGraph` gains the `(allEntries, visibleIds)` overload `jj-idea-hlu3`
already wanted; `LayoutCalculator` sets the state where it currently sets
`elidedParentsByEntry[...] = true` (`LayoutCalculator.kt:95-98`). The call site already has both
sets to hand — `UnifiedJujutsuLogPanel.refreshDisplayedGraph` computes `filtered` and
`model.getAllEntries()`.

**`PagedLogWindow.idsWithUnresolvedParent()` is deliberately not used.** It distinguishes
"pending, will arrive if you keep scrolling" from "beyond a hard limit, never will" — but both are
*not loaded*, both get the same stub, and both are clicked to load. Splitting them would only
change tooltip wording, so it stays unwired (and unit-tested) rather than taking the dependency.

### 2. Long-edge navigation (its own bead)

**Which edges.** An edge is *long* when its other endpoint's row is outside the current viewport —
one rule covering both "disappears out of the viewport" and "longer than N rows", self-updating as
the user scrolls. Symmetric: applies downward to parents and upward to children. A `NOT_LOADED`
parent stub is always long — it has no endpoint at all.

**Hit testing — lane-granular, not pixel-granular.** Map the pointer to a lane
(`lane = (x - startX) / laneWidth`), then ask whether this row has an edge in that lane. Lanes are
~12px apart so this still feels like clicking the line, while avoiding reproducing the sine-wave
path geometry in a hit test — the `JujutsuLogTableDnDChipTest` experience showed geometry-mirroring
assertions are fragile under CI font metrics. It also keeps the hit test a pure function,
unit-testable with no fonts. The row→lane→edge index comes from the same pass
`JujutsuGraphAndDescriptionRenderer.getRowPassthroughs` already makes, extended to record each
lane's `(childKey, parentKey)` and reusing its existing `rowPassthroughCache`.

**Hover.** Thicken the hovered edge along its whole visible span, draw an arrowhead at the viewport
boundary pointing toward the other end, and show a tooltip naming the target (target commit + how
far, or "Parent not loaded yet — click to load"). One structural difference from the existing hover
code: `JujutsuLogTable`'s current hover cue repaints only the hovered row (`repaintRow`,
`JujutsuLogTable.kt:221-227`), whereas a thickened long edge spans rows — repaint the visible span.

**Click → navigate.** `logTable.requestSelection(ChangeKey(repo, targetId))`. For an already-loaded
target this selects and scrolls to it. For a `NOT_LOADED` parent **the whole load-and-reveal path
already exists end to end and needs no new code**: `requestSelection`
(`JujutsuLogTable.kt:108-119`) finds it missing → fires `onSelectionExpansionNeeded` →
`UnifiedJujutsuLogPanel` calls `loadExpanding` (`UnifiedJujutsuLogDataLoader.kt:251`) → merges via
`expansionEntriesByRepo` → `setEntries` applies the pending selection, and because
`requestSelection` sets `pendingSelectionIsExplicit = true`, jj-idea-2c8k round 3's fix means it
scrolls into view rather than being suppressed.

Follow the existing mouse plumbing rather than inventing one: the hover listener at
`JujutsuLogTable.kt:200-228` (compute target once, drive cursor + repaint) and the click listener at
`:294-303` (dispatch on resolved target). A `HIDDEN` edge gets the tooltip but no click action — its
target can't be shown without clearing the filter, which is `jj-idea-hlu3`'s territory.

### 3. Mixed merge with one unloaded parent (`jj-idea-1pgy`)

Once state is per-parent, `shouldDrawElidedStub`'s "row has *no* loaded parent" restriction
(`JujutsuGraphAndDescriptionRenderer.kt:70-71`) is arbitrary. A merge row with one loaded and one
unloaded parent draws its real connector in its own lane, so the stub needs a free lane — allocate
one via `LayoutCalculator`'s existing `laneFor`/reservation logic, **for the stub's own row only**,
with no passthrough beyond it, so no lane leaks to the rest of the graph (the exact hazard the
`continue` comment at `LayoutCalculator.kt:89-97` guards against).

Ships with `jj-idea-xi58` in one session — it's the same renderer function and the same per-parent
state.

### `jj-idea-hlu3` is now decoupled

hlu3 draws a connector to the nearest still-visible ancestor. This plan makes *navigation*, not
connection, the answer to "where does this line go", which makes hlu3 purely **additive**: it would
later upgrade a `HIDDEN` edge's tooltip into a real remapped connector, without changing anything
built above. Its own session, whenever. (Previously this document argued hlu3 and xi58 shared
plumbing and should be sequenced together — that was true of the superseded approach, not this one.)

## Scale (per contributing.md § Performance & Scale)

- Classification: no new pass — a branch inside `LayoutCalculator`'s existing per-parent loop.
- Edge index: one pass over rendered rows × their passthrough lanes, folded into
  `getRowPassthroughs`'s existing pass and cached in `rowPassthroughCache`. Same O() as today.
- Hit test: O(1) per mouse move (lane arithmetic + one map lookup) — no scan.
- Hover repaint: bounded by the viewport, not by edge length.
- No filesystem traversal; no new per-commit loop beyond the existing cached one.

Ship an operation-count test for the edge index, following `LayoutCalculatorTest`'s existing
`operationCount` pattern (and `LaidOutCellScaleTest` for the renderer-side shape).

## Verification

- Unit: lane-granular hit test as a pure function (no fonts) — resolves to the right edge, the right
  target, and null off-lane; long-vs-short classification against a viewport range.
- Unit: `LayoutCalculatorTest` — `NOT_LOADED` vs `HIDDEN` per parent; mixed merge gets a free lane
  and leaks no passthrough.
- Renderer: extend `JujutsuGraphAndDescriptionRendererTest` — straight stub vs wiggle per state,
  thickening on hover.
- Operation-count test for the edge index (PR-blocking per CLAUDE.md).
- `./gradlew check`.
- Manual on the `fx-stress` fixture (`SCALE=6 WITH_REMOTE=1`), paged flag on: rows with unloaded
  parents show the straight stub not the wiggle; filtering to hide an ancestor shows the wiggle;
  hovering a long edge thickens it and shows the arrow; clicking jumps to the other end; clicking an
  unloaded parent loads and reveals it.

## Docs & bookkeeping when this is implemented

- **`CHANGELOG.md`'s `[Unreleased]` entry needs revising**: it currently claims the wiggle covers a
  parent "filtered out of view, **or** falls beyond the configured log limit", which this change
  makes wrong — those two cases now render differently.
- `docs/manual-tests.md`: extend MT-LOG-GRAPH, and the paged-loading subsection of MT-LOG-REFRESH.
- **Manual regression scope**: MT-LOG-GRAPH, MT-LOG-TABLE (new mouse handling in the graph column),
  MT-LOG-FILTER (the `HIDDEN` path), MT-LOG-REFRESH § Paged log loading.
- Not preview-gated — the stub it corrects already ships GA.

## Superseded approach

This document previously recommended, for `jj-idea-xi58`, extending hlu3's remap with a **targeted
fetch**: when no visible ancestor is found within `allEntries`, walk up the real parent chain with
`LogCache.loadContext`-shaped queries until hitting something loaded, then connect to that. It was
flagged as "the risky part" — a naive per-row fetch could itself become a performance problem on a
widely-branched repo.

It is superseded because it solves the wrong problem. It works to *connect* the row to a distant
ancestor, which the lane-leak constraint above makes actively undesirable even if the fetch were
free — and it still leaves the user with no way to ask "where does this go?", because the answer it
draws is a line to somewhere thousands of rows away. Navigation answers the question directly, with
no speculative fetching at all.

## Related

- `docs/design/jj-idea-2c8k-paged-log-loading.md` — the paging mechanism, its validation matrix,
  and § "The paint treatment for an elided parent" for the shipped-stub side of the story.
- `jj-idea-5gof` (paged loader lock has no automated concurrency coverage) and `jj-idea-wrza`
  (post-write `refresh()`'s page-1 splice can shift rows under a fixed viewport) — the other two
  open paged-loading follow-ups, independent of this plan.
