# Paged log loading — mechanism analysis and validation (jj-idea-2c8k, GitHub #69)

**Status: design analysis, pre-implementation.** This document is the durable record of the
research behind jj-idea-2c8k (log refresh latency scales with the configured log limit) — the
mechanism comparison, the empirical validation across real and synthetic repo shapes, the bugs
found along the way, and the open questions still being worked through. It exists independently
of whatever the final shipped design looks like, so the reasoning survives even if the
implementation plan (which lives in a session-local plan file during active work) changes.

## The problem

GitHub #69: with the log limit raised to 10,000 (repo ~7,000 commits), a single write op
takes several seconds to show up in the log. Every write currently triggers a full reload of
the whole configured window — cost scales with the configured limit, not with what actually
changed.

## Phase 1: what's actually slow

Measured against a rebuilt `jj-stress-test` fixture (`scripts/fixtures/fx-stress.sh`, gained
`SCALE=<n>` and `WITH_REMOTE=1` env knobs for this work; 5,952 commits, pushed remote). Full
log-load pipeline split by component:

| Component | Finding |
|---|---|
| `hasPushedAncestor`/`immutable` template predicates | Real (~30% of raw jj time) but not dominant |
| Kotlin-side field parsing | Cheap (tens to ~200ms even at N≈6,000) |
| `CommitGraphBuilder`/graph layout | Linear, cheap (confirmed twice — an initial 2-4s reading was a MockK call-recording artifact from the test harness, not real cost) |
| jj's own per-invocation walk over N commits | **The dominant cost**, and it barely depends on the template's field list — a signature-only template over the same N still cost most of what the full template cost |

**Conclusion:** no amount of smarter Kotlin-side diffing removes the cost of asking jj to walk
N commits. The fix has to bound N *per invocation*, independent of how much history the user
wants available — i.e. paged loading, not a smarter single fetch.

## Mechanisms considered

| Mechanism | Cold start | Post-write refresh | Scrolling (few heads) | Scrolling (many heads) | Correctness risk | Verdict |
|---|---|---|---|---|---|---|
| Today: full reload every write | O(limit) | O(limit) — the bug | O(limit) per reopen | same | None | Baseline; is the bug |
| Signature-diff reconcile (bead's original design) | unchanged | ~2x better (signature pass still walks the whole window) | N/A | N/A | New template/field/concurrency surface | Rejected — doesn't fix the order-of-magnitude problem |
| Regrow `--limit` on every `loadMore()` | O(page size) | O(page size) | **O(K² × page size)** across a K-page scroll | same, plus a huge last fetch | None | Rejected — quadratic over a scrolling session |
| Exclusion-list cursor (`revset ~ (id1\|...\|idN)`) | O(page size) | O(page size) | Correct but explodes: 500 excluded ids = 165ms, 4,500 = 3.8-4.4s | far worse | None (exact match) | Rejected — jj's revset evaluator doesn't scale for large exclusion sets |
| **Frontier cursor, seeded from all heads** (chosen) | O(page size) + one `heads()` query | O(page size) | O(page size), flat to depth 100+ | O(page size) + a bounded, depth-independent tax up to ~8-12k total heads; **hard ceiling above that (see below)** | None on every shape tested | Chosen |

## The frontier mechanism

- Compute `heads(revset)` once, up front.
- Maintain `frontier: Set<ChangeId>` across pages, seeded with all heads, rendered as
  `ChangeId.full` (offset-qualified) in every revset. Each page's revset:
  `<revset> & ((frontier) | ancestors(frontier))`, `--limit pageSize`.
- After each page, fold newly-exposed parent ids (not yet shown) into `frontier`; drop
  now-shown ids. **Carry the frontier forward between pages — never recompute it from only the
  latest page** (see bug #1 below).
- Terminates when a page returns fewer than `pageSize` rows and frontier is empty.

**Correctness invariant:** after each page, `shown == revset \ ancestors(frontier)`. Holds by
induction from the all-heads seed; each page only moves ids from `ancestors(frontier)` into
`shown`, preserving the equation. Violated only if the seed isn't a genuine, complete
`heads(revset)` — which is why it's a dedicated query, not inferred incrementally.

## Bugs found during validation (all fixed, kept here so they aren't rediscovered)

1. **Starvation from recomputing the frontier fresh each page.** An early version derived
   `frontier` only from the just-fetched page's own rows' parents, discarding anything from the
   previous frontier that didn't happen to get consumed this round. On a many-branch repo, a
   slower-growing branch's frontier id can get silently and permanently dropped — the page's
   `--limit` cap fills up with faster-growing branches before that id's own continuation is
   ever fetched. **Fix:** seed from *all* heads up front, and carry the frontier forward as a
   persistent set across pages (never recomputed from scratch).
2. **Bare change ids fail on divergence.** This project's own repo (real, not synthetic — from
   concurrent `jj-parallel-lanes` work) has a divergent change id; a bare change-id revset term
   referencing it is a **hard error** (`Change ID '...' is divergent`), not a graceful
   degradation. **Fix:** the plugin already has a convention for exactly this —
   `ChangeId.full` appends `/<offset>` when divergent (used elsewhere: `LogCache.loadContext`,
   `squashAction.kt`, `BookmarkClassifier.kt`). Using `.full` throughout (not a second,
   commit-id-based scheme — that was considered and correctly rejected as unnecessary
   complexity) fixes it with no new identifier type.
3. **A `LayoutCalculator` rendering gap, pre-existing, made more visible by paging.** Not a bug
   in the frontier mechanism itself, but found while reasoning about it (see "Open question:
   graph rendering at page/window boundaries" below): a row whose parent isn't in the currently
   loaded set today renders with **no connector at all** — identical to a true repository root —
   rather than any "more below, not loaded" indicator. This already happens today at the single
   outer edge of the configured limit; paging makes it recur at every page boundary as the user
   scrolls, which is a materially more visible occurrence of the same gap. Fixed: `RowLayout`/
   `GraphNode` now carry `hasElidedParents`; the actual paint treatment is a separate follow-up.
4. **A latent `CliLogService.parse()` bug, exposed by the first single-field template.** Every
   template field individually appends its own `"\0"` terminator, so raw jj output always ends
   with a NUL — `split(FIELD_SEPARATOR)` therefore always yields one spurious trailing empty
   element, for every template, regardless of width. For every existing (multi-field) template
   this was harmless — the resulting incomplete last chunk fails the `it.size == recordSize`
   check and gets silently filtered out. `getLogHeads`'s `changeIdOnlyTemplate` is the first
   template with `recordSize == 1`, at which point that trailing `""` is itself a syntactically
   *complete*, spurious extra record — `"".split("~")` has only 1 element where the `changeId`
   parser destructures 3, throwing `IndexOutOfBoundsException`. **Fix:** `parse()` now drops a
   trailing empty element explicitly, rather than relying on the record-size coincidence that
   silently stopped applying at width 1.
5. **A genuine infinite loop when `baseRevset` isn't closed under ancestry.** Found by the
   `bookmarks()`-composition contractTest, which hung for 11 minutes before OOMing. `bookmarks()`
   matches only specific commits, not their ancestors — so a frontier member's *true* parent can
   be real but permanently excluded by the intersection with `baseRevset`. That parent then never
   appears in any page, never moves into `shown`, and never leaves `frontier`: `isExhausted`
   never becomes true, and a caller looping until it does loops forever. **Fix:** an empty page
   now clears the frontier instead of leaving it unchanged. This is sound because `pageRevset()`
   queries the *entire* matching set before `--limit` truncates it — an empty result proves
   nothing in any currently-tracked branch's ancestry matches `baseRevset`, however deep, so
   there is provably nothing left to find by continuing. Regression-tested both at the
   `PagedLogWindow` unit level and via the real-jj contractTest that found it.
6. **`CommitTablePanel.refresh()` called `forceRefresh()` instead of `refresh()` — every write
   triggered the *expensive* path, not the cheap one.** Found via manual testing (`runIde`), not
   by any automated test — the two entry points look nearly interchangeable by name, and nothing
   in the type system catches swapping them. `CommitTablePanel.refresh()` is bound to
   `JujutsuStateModel.logRefresh` (`UnifiedJujutsuLogPanel.setupStateListener`), which fires
   after **every write**, not just an explicit user Refresh click. An earlier pass wired it to
   `forceRefresh()` (paged re-verification of every loaded page) instead of `refresh()` (the
   cheap, page-1-only path) — silently undoing the entire point of this bead: every write once
   again cost O(pages loaded), the original GitHub #69 complaint, now for both the paged *and*
   pre-paged code paths. **Fix:** `CommitTablePanel.refresh()` → `dataLoader.refresh()`; only the
   toolbar `RefreshAction.actionPerformed` → `dataLoader.forceRefresh()`. No automated test
   distinguishes these two call sites' cost profile yet — worth a targeted regression test (assert
   which `DataLoader` method a simulated `logRefresh` notify invokes) rather than relying on
   manual testing to catch a repeat.
7. **Scroll-triggered `loadMore()` didn't fire reliably.** The original listener was on
   `scrollPane.verticalScrollBar.model` (a `BoundedRangeModel`), whose change event isn't
   guaranteed to fire only after the viewport's own `viewPosition`/`extentSize` have been synced
   to match — a plausible source of the reported "auto-scroll doesn't load more" (on top of bug
   6 above, which independently could have made this hard to observe correctly during testing).
   **Fix:** listen on `scrollPane.viewport` itself (`JViewport.addChangeListener`) instead of the
   scrollbar's model — the viewport's own change event guarantees its state already reflects
   what triggered it, which is the idiomatic way to detect "the visible region changed" in Swing.
8. **Status bar's "limit" message became actively misleading with paging on.** `updateStatusBar`
   showed "Showing N changes (limit: L) — Change limit in Settings" whenever `entryCount >= L`;
   with paging, `L` is the *page size*, not a true cap, so this message — found via manual
   testing — falsely implied a hard ceiling and pointed at a Settings change that wouldn't help,
   right as the user was asking why scrolling wasn't loading more. **First fix (round 2):** when
   paging is on, show "Showing N changes — scroll for more" instead (`log.status.paged`) rather
   than the truncation message and its "change the limit" link. **Superseded in round 3:** even
   that replacement message was reported as pointless — with continuous scrolling, the correct
   amount of status-bar chrome is none; the scrollbar itself already communicates "there's more."
   Current behavior: `updateStatusBar` hides the status bar unconditionally when paging is on,
   checked before the truncation comparison; `log.status.paged` was deleted. The non-paged
   truncation message/link is unaffected.
9. **`PagedLogWindow` is not thread-safe, and nothing serialized concurrent access to it —
   a real `ConcurrentModificationException` in production.** Found via manual testing:
   `java.util.ConcurrentModificationException` at `PagedLogWindow.getEntries` (the `entries`
   getter's `recordedPages.flatten()`), thrown from inside `loadMore()`. The scroll listener can
   fire many times in quick succession — each call to `loadMore()`/`refresh()`/`forceRefresh()`
   dispatches to `runInBackground` (a plain pooled-thread submission, **not**
   `BackgroundDataLoader`'s `executeInBackground`, which is where the `loading`/`pendingRefresh`
   coalescing an earlier version of this doc claimed "for free" actually lives) — so nothing
   prevented two of these background tasks from concurrently reading/mutating the *same*
   `PagedLogWindow` instance for the same repo. **Fix:** one `ReentrantLock` per repo
   (`UnifiedJujutsuLogDataLoader.lockFor`), computed lazily in a `ConcurrentHashMap`. `loadMore()`
   uses `tryLock()` (skip a repo that's already mid-fetch — correct for eager prefetch: the next
   scroll event, or this same check re-running, tries again rather than queuing redundant work).
   `refresh()`/`forceRefresh()` use the blocking `withLock` (these must always complete, not
   skip). `forceRefresh()`'s window-building phase constructs a fresh, not-yet-shared
   `PagedLogWindow` per repo — only the read of the *old* window's `pageCount` and the final
   install into `pagedWindowByRepo` touch shared state, so only those two points are locked
   (a small residual lost-update race between them and a concurrent `refresh()`/`loadMore()` is
   accepted as low-probability and non-corrupting, not worth a full ordered multi-lock protocol
   given the time budget). **Testing gap, noted rather than silently left implicit:** no
   automated test exercises this race — it would need either a platformTest or substantial
   mocking of `JujutsuSettings`/`ApplicationManager` to construct a real
   `UnifiedJujutsuLogDataLoader`, which this fix's turnaround time didn't allow. Worth adding if
   this loader ever gets easier to construct in isolation.

   **Round 3: the same `ConcurrentModificationException` was reported again after this fix
   shipped.** Confirmed to be a **stale `runIde` sandbox build**, not a regression: the running
   sandbox JVM had started before the lock fix was compiled, and its loaded plugin jar's
   `UnifiedJujutsuLogDataLoader.class` had zero `*Locked` methods (`javap`-verified) — the fixed
   source (`lockFor`, `loadFirstPageOrFallbackLocked`, `refreshOneRepoLocked`,
   `loadMoreOneRepoLocked`) only exists in jars built after that sandbox process started. The
   reported stack trace was also structurally the pre-fix shape (`fetchOnePage ←
   loadMore$lambda$1 ← runInBackground$lambda$0`, no `loadMoreOneRepoLocked` frame), which is
   impossible post-fix since the lock's `try`/`finally` wraps that extracted method. Re-audited
   every mutator of a *published* `PagedLogWindow` and confirmed all go through `lockFor(repo)`;
   lock keys (`JujutsuRepository`, a `data class` over stable fields only) are stable across
   calls. **Two small hardening fixes landed anyway**, since trust was already shaken by two
   reports: `loadMoreOneRepoLocked` now explicitly re-checks `window.isExhausted` inside the lock
   (previously only implied by `fetchOnePage`'s own no-op-when-exhausted behavior plus a
   `pageCount` comparison) and `refreshOneRepoLocked`'s existing `?: return false` is now
   documented as the re-validation of `refresh()`'s unlocked `containsKey` filter, rather than an
   incidental side effect. Neither changes behavior — both just make the "unlocked filter is only
   a candidate hint, re-validate inside the lock" invariant explicit instead of implicit.

   **Documented, deliberately not fixed:** `mergeAndNotify()`'s read of `LogCache.all` can race a
   *different* concurrent operation's `logCache.clear()` + `logCache.store()` pair for the same
   repo (e.g. `loadExpanding`/`searchWholeRepo` running independently while a `loadMore()` is
   mid-swap) — landing in that gap sees an empty cache and falls through to a full unpaged reload
   (`LogCache.kt`'s `snapshot() == null` path). Rare (needs two independent background operations
   to overlap for the same repo), non-corrupting (worst case: one unnecessary full fetch,
   self-correcting next refresh), performance-only. A proper fix needs either a new atomic
   `LogCache.replaceAll()` primitive (real risk to a widely-shared, heavily-tested class for a
   rare edge case) or an additive `retainOnly(ids)` primitive paired with store-before-evict
   ordering — not worth it until this is observed to matter in practice. Also noted, same
   priority bucket: repo-instance churn on `initialisedRepositories` invalidation constructs
   fresh `JujutsuRepositoryImpl`s with fresh lazy `logCache`s while `pagedWindowByRepo` (keyed by
   the now-equal-but-differently-backed repo) can retain a window claiming more pages than the
   fresh cache has — low risk since `loadCommits()` already unconditionally rebuilds a fresh
   1-page window on every call.
10. **Graph rendering: the all-elided-parents case now gets a wiggly line, not nothing** (round
    3). `JujutsuGraphAndDescriptionRenderer.shouldDrawElidedStub` gates on
    `node.hasElidedParents && node.parentLanes.isEmpty()` — a row with *no* loaded parent draws a
    continuous sine-wave stub (`drawElidedParentStub`, a `Path2D` in the row's own lane) from just
    below the commit circle to the row bottom, echoing jj CLI's own `~` elision marker but as an
    unbroken wave rather than a dash pattern. See "Open question" below (now resolved for this
    case) and the cross-bead plan in `jj-idea-2c8k`'s plan file for the fuller `jj-idea-hlu3`/
    `jj-idea-xi58` nearest-visible-ancestor treatment, which this stub deliberately doesn't
    attempt (no fetch, no remap — just "isn't a true root").
11. **Scroll position jumped back to the selection on every `loadMore()`** (round 3).
    `JujutsuLogTable.setEntries()` always ended by calling `selectEntry(...)`, which always
    scrolled the selected row into view — a no-op if already visible, but a real jump if the user
    had scrolled past it (the common case once `@` is off-screen and the user is browsing older
    history). **Fix:** `selectEntry` gained a `scrollIntoView: Boolean = true` parameter;
    `setEntries()` now passes `scrollIntoView = pendingSelectionIsExplicit` — a merely
    carried-over selection (paging, or any other refresh) no longer forces the viewport back,
    while `requestSelection()`'s explicit navigation (the only place that sets
    `pendingSelectionIsExplicit = true`) still scrolls as before. `refresh()`'s page-1 splice (not
    `loadMore()`) can still shift row indices under a fixed viewport pixel position if a new
    commit appears at top — out of scope here since it wasn't the reported symptom (`loadMore()`
    only ever appends rows after everything currently loaded); a fuller viewport-anchor mechanism
    (capture top-visible row's `ChangeKey` + pixel offset, restore by looking it up post-update)
    would be needed if that turns out to matter in practice.

## Validation matrix

Tested directly (not simulated) against real and synthetic repos:

| Repo | Commits | Heads | Result |
|---|---|---|---|
| This project's own repo (real; has a divergent change id) | 829 | 38 | Exact match |
| Synthetic, many short branches | 2,103 | 1,001 | Exact match, 39-160ms/page |
| Synthetic, many short branches | 6,503 | 3,001 | Exact match, 75-363ms/page, shrinking |
| Node.js (real, shallow multi-branch clone of `github.com/nodejs/node`) | 32,057 | 469 | Exact match, 92-123ms/page, flat |
| Synthetic monorepo-proxy: 50,000 linear commits, 1 head | 50,002 | 1 | Exact match, 34-38ms/page, flat to page 100 |
| Adversarial: 1 recent 2,000-commit branch + 2,000 old shallow branches | 6,003 | 2,002 | Exact match; frontier pinned at 2,002 for 4 pages (~160-167ms/page, flat, not growing with depth) while paging the active branch, then drains |
| Adversarial, pushed further: 1,000-commit active branch + 8,000 old branches | ~17,000 | 8,002 | Exact match; ~650-660ms/page while frontier pinned — confirms **linear** scaling, ~0.08ms per frontier id, consistent from 2,002 to 8,002 heads |

Every case: exact match (paged total == true total, no duplicates, no gaps).

**On Google's Piper monorepo specifically:** not git/jj-shaped at all (virtual filesystem, no
local DAG; ~1B files, ~86TB, ~25k engineers, ~40-45k commits/day per 2015-2016 published
figures — no newer official numbers exist). A literal simulation isn't meaningful. What *is*
meaningful: Google's actual practice is trunk-based development — few concurrent branches, huge
total revision count — exactly the shape the 50k-linear-commit proxy tests, and the shape this
design handles best (flat cost, no frontier tax with few heads).

## Where it actually breaks: total repo head count, not query width

The linear scaling above (~0.08ms/frontier-id) held cleanly through 8,002 heads. Pushed further
to find the real ceiling, using **realistic** repos (total repo head count == query width, not
an artificially narrow slice of a much bigger repo):

| Total heads (realistic, non-sliced) | Baseline (`all() --limit 500`, no frontier at all) | Frontier query at max width |
|---|---|---|
| 12,000 | 246ms | ~850ms-1.35s (still ≈ 0.08-0.1ms/id, consistent) |
| 20,000 | 531ms | not measured |
| 30,000 | **~4.0-4.1s** (reproduced twice, clean re-run) | not measured (would be worse) |

**The cliff is in `heads(revset)`/`all()` itself, not specifically in wide frontier queries** —
even the simplest possible query (`jj log -r '@' --limit 1`) took ~4.3-4.5s once background
testing pushed total repo heads to 30,000 (sliced-repo control test). This means: above some
threshold between 20,000 and 30,000 total heads, **the existing, unpaged plugin would already be
slow today**, for the same underlying reason — this is a jj/backend scalability characteristic,
not something paging introduces or can fully fix.

**Separately, and lower**, there's a hard OS-level failure: constructing a revset by OR-ing
every frontier id blows past the OS argument-length ceiling (`ARG_MAX` = 1,048,576 bytes on the
machine tested) somewhere around 15,000-20,000 ids (990,024 bytes succeeded at 15,000 ids;
1,320,024 bytes failed outright with `OSError: Argument list too long` at 20,000). This is a
hard crash, not a slowdown, and it's reachable **below** the jj-internal performance cliff.

**Conclusion:** the frontier mechanism needs an explicit safety valve. Below ~8,000-10,000 total
heads (comfortably covering every real-world example found — Windows ~440 branches, Linux 539
refs, git's own "under ~10k refs generally fine" guidance, and the adversarial 8,000-old-branch
case tested clean above), it's fast and safe. Above a conservative cap (proposed: 8,000, the
most rigorously double-checked clean data point), **fall back to today's existing full-window
`reload()`** for that refresh rather than constructing a frontier query at all — simpler and
safer than attempting to cap-and-rotate the frontier (which would still need its own fallback
for the "even the baseline is slow" regime above ~20-30k heads, so building it wouldn't avoid
needing a fallback anyway). A repo that large already pays a multi-second tax on any single jj
log invocation today; paging doesn't make that better or worse, so falling back to the existing
code path for it costs nothing beyond what today's plugin already costs there, and avoids the
ARG_MAX crash entirely by never constructing an oversized command.

## Recency ordering comes free from jj, not something we need to build

Controlled test: two branches fast-imported in one operation, one with 2020-dated commits, one
with 2026-dated commits, imported in an order that would contradict a timestamp-based result if
jj ordered by import/insertion sequence. Result: jj's `--limit` truncation consistently shows
the 2026-dated commits first, regardless of import order — **jj's ordering is timestamp-driven**.
The frontier mechanism exactly reproduces whatever order `--limit` gives (page 1 is
byte-identical to a direct fetch, proven in the validation matrix), so paging inherits
recency-ordering for free — no separate time-ordering logic needed.

## Composing with the plugin's configured log revset

The plugin's own default `logRevset` is `"all()"` (`JujutsuSettingsState.kt:12`), not jj's own
terse CLI default (`builtin_log()`, which is a small curated set — `present(@) |
ancestors(immutable_heads().., 2) | trunk()`). So the "many concurrent heads" scenario is a
realistic default-configuration concern for this plugin, not an edge case reachable only via
unusual settings.

The mechanism generalizes to a custom (non-`all()`) revset exactly as expected: seed from
`heads(<configuredRevset>)`, and intersect every page's query with the *same* configured revset:
`<configuredRevset> & ((frontier) | ancestors(frontier))`. Verified directly with `bookmarks()`
(a genuinely restrictive revset in the test repo, 4 heads vs. 38 for `all()`) — `heads(bookmarks())`
correctly returns just the bookmarked heads, and the combined query correctly stays within that
restricted set. No special-casing needed for a custom revset; `revset_base` is a single
parameter threaded through both the seed and every page query.

## Status

Implemented: `PagedLogWindow`, `LogService.getLogHeads`, the loader's `refresh()`/`forceRefresh()`/
`loadMore()` split and safety valve, the eager one-page-ahead prefetch scroll listener, the
`pagedLogLoading` early-access flag (via `PreviewFeature.PAGED_LOG_LOAD`/`PreviewEntitlement`),
per-repo `ReentrantLock` serialization, the `LayoutCalculator`/`GraphNode` `hasElidedParents` data
field and the renderer's wiggly-stub paint treatment for the all-elided case, and the
`selectEntry(scrollIntoView)` fix so a merely carried-over selection no longer yanks the viewport
back on `loadMore()`. `./gradlew check` and `platformTest` pass with no regressions; the
validation matrix above and the bugs list are all covered by permanent tests
(`PagedLogWindowTest`, `PagedLogWindowContractTest`, `LayoutCalculatorTest`,
`JujutsuGraphAndDescriptionRendererTest`, `JujutsuLogTableScrollPreservationTest`), not just the
one-off scratch scripts used during design. Remaining open items are cross-referenced from "The
paint treatment for an elided parent" above and the cross-bead plan for `jj-idea-hlu3`/
`jj-idea-xi58`.

## The paint treatment for an elided parent (resolved for the all-elided case)

`LayoutCalculatorImpl` (`ui/log/graph/LayoutCalculator.kt`) tracks, per row, whether it has a
parent id not present in the currently loaded entry set (`RowLayout.hasElidedParents`, threaded
through to `GraphNode`) — distinguishing "a real parent, just not loaded yet" from "true
repository root," which previously rendered identically (no connector at all; the comment at the
old `continue` called this deliberate, to avoid "leaking a lane" for a passthrough that would
never terminate). This is not a new gap — it's already true today at the single outer edge of the
configured limit — but paging means it can recur at *every* page boundary as the user scrolls,
which is a much more visible occurrence of the same pre-existing gap, and is why fixing the data
layer was folded into this bead rather than left untouched.

**Resolved (round 3) for the common case — a row with no loaded parent at all**: see bug 10
above. `JujutsuGraphAndDescriptionRenderer` now paints a continuous wiggly line (a `Path2D` sine
wave, in the row's own lane) instead of nothing, once real manual `runIde` testing made visual
verification of a new Swing paint routine possible (the earlier round deferred this specifically
for lack of that). The eager one-page-ahead prefetch (see "When to load more" above) still means
the user rarely sees this in a settled state during normal scrolling — the wave mostly shows up
on a wide multi-branch repo where a row's true parent simply ranks low enough not to share a page
with it, independent of scroll position.

**Still open, deliberately out of scope for this bead**: a mixed merge (one parent loaded, one
elided) keeps rendering as it does today — `shouldDrawElidedStub` excludes it because a real
connector already occupies the row's own lane, and drawing the stub too needs a free lane offset.
Also open: the fuller `jj-idea-hlu3`/`jj-idea-xi58` nearest-visible-ancestor remap (a filtered-out
or never-fetched ancestor gets a wiggly line to the actual nearest visible commit, not just "to
nowhere"). Both are covered by the cross-bead plan referenced from this bead's plan file and (once
written) `docs/design/jj-idea-hlu3-xi58-elided-ancestor-rendering.md`.

### Filtering/searching to an id that isn't loaded

`LogCache.loadContext`/`UnifiedJujutsuLogDataLoader.loadExpanding` already fetch a bounded
context window (`ancestors(id, window) | id | descendants(id, window)`, default window 10)
around an arbitrary target — this is how "jump to a change id typed into the filter" already
works today, independent of the main paged window, merged in as its own bucket
(`expansionEntriesByRepo`). That existing mechanism composes with paging unchanged: it's
additive, not something paging needs to alter. It has the same boundary-rendering
question as above at *its own* edges (a context window's ancestors/descendants stop at
`window` commits back), which is pre-existing and out of scope for this bead specifically, but
is the same underlying gap as the page-boundary case, and would be fixed by the same renderer
change if one is made.

## Fixture work (durable, independent of final design)

`scripts/fixtures/fx-stress.sh` gained `SCALE=<n>` (multiplies main trunk/deep-branch/
long-branch lengths) and `WITH_REMOTE=1` (pushes bookmarks to a bare remote so
`remote_bookmarks()` is non-empty) env knobs, documented in `docs/manual-tests.md` § Fixtures.
The large/many-head fixtures used for validation above were session-scratch, built with
`git fast-import` for speed (verified not to distort jj's ordering behavior — see the recency
test) and cleaned up after use; the shell snippets to reproduce them are straightforward
(a root commit, N branches forked from it via fast-import, `jj git init --colocate`) if this
matrix needs to be re-run against a newer jj version or a different machine.
