# Elided-ancestor graph rendering — cross-bead plan (jj-idea-hlu3, jj-idea-xi58, jj-idea-2c8k)

## Context

`jj-idea-2c8k` (paged log loading, GitHub #69) surfaced this problem far more visibly than
before: with a wide multi-branch repo, many rows even near the top of a page have a real parent
that simply isn't in the loaded set, and the graph renderer used to draw **no connector at all**
for that row — pixel-identical to a true repository root
(`JujutsuGraphAndDescriptionRenderer.kt`'s `drawLinesToParents`, `graphNodes[parentKey]?.lane ?:
continue`). This isn't new or paging-specific — it's the same gap two older P3 beads already
described from different angles:

- **`jj-idea-hlu3`** — "Bridge elided revisions with wiggly edges in filtered log graph": a
  *filtered-out* ancestor (still loaded, just hidden by the current filter) should get a wiggly
  connector to the nearest still-visible ancestor, not a dropped edge.
- **`jj-idea-xi58`** — "Graph shows unlinked passthrough lines when log limit cuts off commits":
  a parent that was *never fetched* (beyond the configured limit) leaves a floating,
  disconnected passthrough line.

Both are now linked via a non-blocking `relates_to` dependency in beads, and jj-idea-2c8k shipped
a minimal stub addressing the common sub-case of both (see below) behind its early-access flag.
This doc is the durable, retrievable record of how the three scenarios relate and how to
sequence finishing the job, since jj-idea-2c8k's own session plan file is discarded once that
session's implementation lands.

## The three distinct scenarios

None of these currently share code, but all render today as "no connector, looks like a root":

1. **Filtering** (`jj-idea-hlu3`, and `jj-idea-1ojh`/`jj-idea-5i6i` → `jj-idea-7jkr`, which
   already closed the recompute-on-filter-change half of this). Commit A's parent P *is* loaded
   (present in `allEntries`) but filtered out of the rendered subset. Pure in-memory problem —
   the data to walk is already resident. Fix (as hlu3 describes): a topological remap, O(n+edges)
   with memoisation, rewiring each visible commit's parents to the nearest still-visible
   ancestor; thread an `elided` flag through `GraphEntry`/`RowLayout`/`GraphNode`;
   `CommitGraphBuilder` gains a `(allEntries, visibleIds)` overload. No new I/O. Self-contained,
   unblocked, ready to implement as filed.

2. **Limit/paging cutoff** (`jj-idea-xi58`, and jj-idea-2c8k's paged-loading elided-parent need).
   Commit A's parent P was **never fetched** — not in `allEntries` at all, filtered or not.
   hlu3's remap cannot find it (there's nothing to walk to). xi58's own filing already notes
   hlu3's remap "also covers" this case but marks it explicitly out of scope. Properly fixing
   this needs the remap *extended* with a fallback: when no ancestor is found within
   `allEntries`, issue a targeted fetch (reusing the shape of `LogCache.loadContext`'s existing
   `ancestors(id, window) | id | descendants(id, window)` pattern, GitHub #76) to walk up the
   real parent chain until hitting something already loaded, then treat that as the nearest
   ancestor.

   **This is the risky part**: naively firing one fetch per elided-parent row could itself
   become a performance problem on a widely-branched repo — jj-idea-2c8k's own validation matrix
   found many rows with elided parents even on page 1 of a ~27-head repo, so this needs the same
   kind of batched/bounded query design (and the same kind of real-repo-shape measurement)
   jj-idea-2c8k's frontier cursor needed, not a naive per-row fetch loop.

3. **Mixed merge, partial elision** (found during jj-idea-2c8k's Issue 1 investigation, not
   separately filed anywhere before this doc). A row with two parents, one loaded and one
   elided — the loaded parent already occupies the row's own lane, so the elided one needs a
   free-lane offset, not just "draw somewhere in this row." Orthogonal to *finding* the nearest
   ancestor (scenarios 1-2); it's a layout question inside `LayoutCalculator`/`textStartX`
   (and its `graphTextStartX` click-hit-testing mirror) instead.

## What jj-idea-2c8k already shipped (the minimal stub)

`JujutsuGraphAndDescriptionRenderer.shouldDrawElidedStub(node)` gates on
`node.hasElidedParents && node.parentLanes.isEmpty()` — i.e. scenario 2 (and, incidentally,
scenario 1, since `LayoutCalculator` already marks `hasElidedParents` whenever a parent isn't in
the *currently rendered* set, filtered or never-fetched) **in the all-parents-elided case only**.
It draws a continuous wiggly line (a `Path2D` sine wave, `drawElidedParentStub`) in the row's own
lane from just below the commit circle to the row bottom — "history continues here, unseen"
rather than a dropped edge or a true root. It does **not** find or connect to the actual nearest
visible ancestor (scenarios 1's and 2's full fix), and it explicitly excludes scenario 3 (mixed
merge) since that needs a free lane this stub doesn't reserve.

## Recommended split across sessions

- **jj-idea-2c8k's own session**: the minimal stub above — already shipped behind the
  early-access flag, no dependency on anything below.
- **A session for `jj-idea-hlu3` alone**: implement exactly as filed — scenario 1 only, no
  fetch, no performance risk. Self-contained; `jj-idea-7jkr`'s prerequisite infrastructure is
  already done. Good candidate for a short, low-risk session on its own.
- **A separate, later session for the `xi58` extension**: design (with real-repo-shape
  measurement, the same discipline jj-idea-2c8k's own design doc used) and implement the
  fetch-extended remap for scenario 2 — this both closes `xi58` properly and upgrades the
  stub-to-nowhere into a real wiggly line to the actual nearest ancestor. Deliberately gated on
  *not* rushing: only worth doing once there's a signal the minimal stub isn't good enough in
  practice (paging is early-access, so that signal can come from real use before committing to
  this). Fold in scenario 3 (mixed-merge lane offset) here too, since both touch the same
  rendering code.

## Where this is tracked

- `jj-idea-hlu3` ↔ `jj-idea-xi58`: linked via `relates_to` (non-blocking — both stay
  independently ready).
- This doc is the durable copy of the plan; see also `docs/design/jj-idea-2c8k-paged-log-loading.md`
  § "The paint treatment for an elided parent" for the shipped-stub side of the story.
