package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.preview.PreviewEntitlement
import `in`.kkkev.jjidea.preview.PreviewFeature
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.BackgroundDataLoader
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import kotlinx.datetime.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Loads commit log data from multiple repositories in parallel and updates the table model on EDT.
 *
 * Unified loader that:
 * - Loads commits from all provided repositories concurrently
 * - Merges results using topological sort (children before parents) with timestamp as tiebreaker
 * - Updates the table model and graph on EDT
 * - Subscribes to changeSelection for handling selection requests
 *
 * jj-idea-2c8k (GitHub #69), early access: when `JujutsuSettings.state.pagedLogLoading` is on,
 * the main window is loaded a page at a time via [PagedLogWindow] instead of one full-limit
 * fetch — see docs/design/jj-idea-2c8k-paged-log-loading.md for the mechanism, its correctness
 * invariant, and validation data. [refresh] (the post-write path) only ever refetches page 1;
 * [forceRefresh] (explicit Refresh) re-walks every currently-loaded page, preserving scroll
 * depth; [loadMore] (scroll) fetches exactly one more page. Falls back to today's full
 * [LogCache.reload] per repo whenever paging isn't viable for that repo (the setting is off,
 * the configured revset is [Revset.Default], or [PagedLogWindow.FRONTIER_CAP] would be
 * exceeded) — this loader never behaves worse than before the flag existed.
 */
class UnifiedJujutsuLogDataLoader(
    private val project: Project,
    private val repositories: () -> Collection<JujutsuRepository>,
    private val panel: CommitTablePanel<Data>
) : BackgroundDataLoader(project, "Loading Jujutsu Commits") {
    private val graphBuilder = CommitGraphBuilder()

    data class Data(val entries: List<LogEntry>, val graphNodes: Map<ChangeKey, GraphNode>, val limit: Int)

    @Volatile
    private var lastLimit: Int = 0

    // Per-repo expansion entries accumulated by navigation; keyed by repo identity.
    // Never discarded by loadCommits — only cleared on explicit Refresh (clearExpansions).
    private val expansionEntriesByRepo = ConcurrentHashMap<JujutsuRepository, List<LogEntry>>()

    // Per-repo whole-repo search results (jj-idea-lpbv), accumulated the same way as
    // expansionEntriesByRepo above. Never discarded by loadCommits — only cleared on explicit
    // Refresh (clearExpansions).
    private val searchEntriesByRepo = ConcurrentHashMap<JujutsuRepository, List<LogEntry>>()

    // jj-idea-2c8k: per-repo paged-loading window state, present only for a repo currently being
    // paged (absent means "not paging this repo" - paging disabled, an unpageable revset, or a
    // fallback already happened - see loadPagedOrFallback/tryPagedForceRefresh).
    private val pagedWindowByRepo = ConcurrentHashMap<JujutsuRepository, PagedLogWindow>()

    // jj-idea-2c8k: PagedLogWindow is not thread-safe, and refresh()/forceRefresh()/loadMore()
    // can each be triggered from a different thread with no ordering guarantee between them
    // (a write's post-write refresh(), the toolbar's forceRefresh(), and the scroll listener's
    // loadMore() firing repeatedly in quick succession) - all three read-modify-write the same
    // per-repo PagedLogWindow. Without serialization this is a real ConcurrentModificationException
    // (found via manual testing: rapid scroll events each spawned an independent runInBackground
    // task racing on the same window's backing list). One lock per repo (not a single global
    // lock) so unrelated repos in a multi-root project don't serialize against each other.
    private val repoLocks = ConcurrentHashMap<JujutsuRepository, ReentrantLock>()
    private fun lockFor(repo: JujutsuRepository): ReentrantLock = repoLocks.computeIfAbsent(repo) { ReentrantLock() }

    // jj-idea-jnqi: serializes every read-modify-write of [snapshot] and graphBuilder's shared,
    // non-thread-safe layout engines (see CommitGraphBuilder.incrementalEngine's doc - "one
    // instance lays out one graph at a time" is a caller contract, not enforced). Every one of
    // mergeAndNotify()'s callers (loadMore, refresh, forceRefresh, loadExpanding,
    // searchWholeRepo) runs on raw runInBackground (not BackgroundDataLoader's coalesced
    // executeInBackground - see that class's doc), so several merges can be in flight with no
    // ordering guarantee between them - AND loadCommits() itself must take this same lock
    // around its own snapshot/graphBuilder writes (bugfix: it originally didn't, so an eager
    // loadMore() prefetch firing before the initial load finished could race it on the same
    // graphBuilder instance).
    private val mergeLock = ReentrantLock()

    // jj-idea-jnqi: the merged entry set from the last mergeAndNotify() call, kept so a
    // loadMore()-shaped append can extend it instead of re-flattening every repo's logCache
    // and re-sorting/re-laying-out the whole thing from scratch. Mutated only under mergeLock.
    private var snapshot: MergedSnapshot? = null

    private val pagedLoading = PreviewEntitlement.getInstance().isEnabled(PreviewFeature.PAGED_LOG_LOAD)

    override fun load() = loadCommits()

    private fun notify(data: Data) {
        lastLimit = data.limit
        panel.onDataLoaded(data)
        log.info("Table updated with ${data.entries.size} commits and graph layout")
    }

    /**
     * Load commits from all repositories in the background.
     */
    fun loadCommits() {
        val repos = repositories()
        val settings = JujutsuSettings.getInstance(project)
        val defaultLimit = settings.state.logChangeLimit

        if (repos.isEmpty()) {
            log.info("No repositories to load commits from")
            // jj-idea-jnqi bugfix: both snapshot and graphBuilder mutations must go through
            // mergeLock - see the doc on mergeLock's field and on graphBuilder's use below.
            mergeLock.withLock {
                snapshot = null
                graphBuilder.resetIncremental()
            }
            notify(Data(emptyList(), emptyMap(), defaultLimit))
            return
        }

        val entriesByRepo = ConcurrentHashMap<JujutsuRepository, List<LogEntry>>()
        val errors = ConcurrentHashMap<JujutsuRepository, Throwable>()
        var allEntries: List<LogEntry> = emptyList()
        var graphNodes: Map<ChangeKey, GraphNode> = emptyMap()

        executeInBackground(
            run = { indicator ->
                indicator.text = "Loading commits from ${repos.size} repositories..."
                indicator.isIndeterminate = false

                val latch = CountDownLatch(repos.size)

                repos.forEachIndexed { index, repo ->
                    runInBackground {
                        try {
                            // checkCanceled (not ProgressManager.runProcess(indicator)) - the
                            // indicator is already owned by this Task.Backgroundable's thread;
                            // wrapping it again from these pooled threads made CoreProgressManager
                            // report "already running under this indicator" as a data race. This
                            // check only skips repos that haven't started yet on cancellation; an
                            // in-flight jj call still runs to completion (killing it is jj-idea-1a4c's job).
                            indicator.checkCanceled()
                            indicator.text2 = "Loading from ${repo.displayName}..."
                            indicator.fraction = index.toDouble() / repos.size

                            val loadedEntries = loadFirstPageOrFallback(repo, settings)
                            entriesByRepo[repo] = loadedEntries
                            log.info("Loaded ${loadedEntries.size} commits from ${repo.displayName}")
                        } catch (e: ProcessCanceledException) {
                            log.info("Loading commits from $repo cancelled")
                        } catch (e: Exception) {
                            errors[repo] = e
                            log.warn("Exception loading commits from $repo: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                if (!awaitCancellably(latch, indicator, TimeUnit.MINUTES.toMillis(5))) {
                    log.info("Loading commits cancelled while waiting for repositories")
                    throw ProcessCanceledException()
                }

                if (entriesByRepo.isEmpty() && errors.isNotEmpty()) {
                    log.warn("All repositories failed to load - jj may not be installed")
                    return@executeInBackground
                }

                val correctionsByRepo = bookmarkCorrectionsByRepo()
                allEntries = topologicalSort(entriesByRepo.values.flatten())
                    .map { entry -> enrichBookmarks(entry, correctionsByRepo[entry.repo] ?: EMPTY_CORRECTIONS) }
                log.info("Merged ${allEntries.size} commits from ${entriesByRepo.size} repositories")
                allEntries.groupBy { it.repo }.forEach { (repo, entries) -> repo.logCache.store(entries) }
                // jj-idea-jnqi bugfix: graphBuilder.buildGraph() and the snapshot write must be
                // one atomic unit under mergeLock, exactly like fastMergeAndNotify/
                // fullMergeAndNotify already do - loadCommits() previously called buildGraph()
                // (which mutates graphBuilder's shared incremental-engine state) *outside* the
                // lock, so a concurrently-running mergeAndNotify() (e.g. an eager loadMore()
                // prefetch firing before this initial load finishes) could race it on the same
                // engine instance.
                mergeLock.withLock {
                    // buildGraph() also reseeds graphBuilder's incremental engine (jj-idea-jnqi)
                    // for a later loadMore()'s appendGraph() to build on.
                    graphNodes = graphBuilder.buildGraph(allEntries)
                    snapshot = MergedSnapshot(
                        allEntries,
                        allEntries.mapTo(HashSet()) { it.key },
                        allEntries.minOfOrNull { it.sortTimestamp() } ?: Instant.DISTANT_FUTURE,
                        correctionsByRepo
                    )
                }
            },
            onSuccess = {
                // A cancelled wait throws ProcessCanceledException above, which routes to onCancel()
                // instead of here - so reaching onSuccess means the load actually completed.
                if (entriesByRepo.isEmpty() && errors.isNotEmpty()) return@executeInBackground
                notify(Data(allEntries, graphNodes, defaultLimit))
            }
        )
    }

    /**
     * The initial page-1 load for one repo (also reused by [loadCommits] itself): a fresh
     * [PagedLogWindow] seeded and fetched for exactly one page when paging is enabled and viable
     * for this repo, else today's full [LogCache.reload]. Always leaves [repo]'s [LogCache] in
     * sync with whatever was loaded (paged or not), so [in.kkkev.jjidea.jj.LogCache.loadContext]
     * and friends keep working unchanged.
     */
    private fun loadFirstPageOrFallback(repo: JujutsuRepository, settings: JujutsuSettings): List<LogEntry> =
        lockFor(repo).withLock { loadFirstPageOrFallbackLocked(repo, settings) }

    private fun loadFirstPageOrFallbackLocked(repo: JujutsuRepository, settings: JujutsuSettings): List<LogEntry> {
        if (!pagedLoading) {
            pagedWindowByRepo.remove(repo)
            return repo.logCache.reload()
        }
        val revset = settings.resolvedLogRevset(repo)
        if (revset == Revset.Default) {
            // heads() of an *implicit* default revset has no revset syntax to express - can't
            // seed a frontier for it. Rare (the setting defaults to the explicit "all()").
            pagedWindowByRepo.remove(repo)
            return repo.logCache.reload()
        }
        val pageSize = settings.logChangeLimit(repo)
        val window = PagedLogWindow(revset, pageSize)
        val entries = fetchOnePage(repo, window)
        if (entries == null) {
            pagedWindowByRepo.remove(repo)
            return repo.logCache.reload()
        }
        pagedWindowByRepo[repo] = window
        repo.logCache.clear()
        repo.logCache.store(entries)
        return entries
    }

    /**
     * Fetches exactly one more page into [window] — seeding first if it has no pages yet.
     * Returns `null` (leaving [window] unusable for this attempt) if the seed/fetch fails or the
     * frontier has grown too wide to safely query (jj-idea-2c8k's safety valve — see
     * [PagedLogWindow.FRONTIER_CAP] and docs/design/jj-idea-2c8k-paged-log-loading.md § "Where
     * it actually breaks"). Callers must fall back to a full reload for this repo on `null`.
     */
    private fun fetchOnePage(repo: JujutsuRepository, window: PagedLogWindow): List<LogEntry>? {
        if (window.pageCount == 0) {
            val heads = repo.logService.getLogHeads(window.baseRevset).getOrElse {
                log.warn("Failed to compute log heads for ${repo.displayName}: ${it.message}")
                return null
            }
            window.seed(heads)
        }
        if (window.isExhausted) return window.entries
        if (window.frontierTooWide(PagedLogWindow.FRONTIER_CAP)) {
            log.warn(
                "Log frontier for ${repo.displayName} exceeded ${PagedLogWindow.FRONTIER_CAP} ids - " +
                    "falling back to a full reload for this refresh (see " +
                    "docs/design/jj-idea-2c8k-paged-log-loading.md § \"Where it actually breaks\")"
            )
            return null
        }
        val page = repo.logService.getLog(revset = window.pageRevset(), limit = window.pageSize).getOrElse {
            log.warn("Failed to fetch log page for ${repo.displayName}: ${it.message}")
            return null
        }
        window.recordPage(page)
        return window.entries
    }

    // onMissing (GitHub #76): loadContext returns empty when changeId no longer exists (abandoned
    // or rewritten outside the currently loaded log window). Without a way to signal that back,
    // the caller has nothing to distinguish "still loading" from "gone", and JujutsuLogTable would
    // keep re-requesting the same dead id on every refresh. Called on EDT via runLater so it can
    // safely touch UI state (e.g. clearing a pending selection).
    //
    // jj-idea-2c8k: this is also the existing mechanism that transparently covers a paged
    // refresh() whose cheap page-1-only reconcile didn't happen to include a write's target
    // (e.g. a bookmark moved on a commit deep in history) — JujutsuLogTable.requestSelection
    // already falls back to loadExpanding when the requested key isn't in the table, so no
    // separate "select missing" escalation is needed in refresh() itself.
    fun loadExpanding(repo: JujutsuRepository, changeId: ChangeId, onMissing: () -> Unit = {}) {
        val window = JujutsuSettings.getInstance(project).logContextWindow(repo)
        runInBackground {
            val expansion = repo.logCache.loadContext(changeId, window)
            if (expansion.isEmpty()) {
                runLater { onMissing() }
            } else {
                expansionEntriesByRepo[repo] = expansion
                mergeAndNotify()
            }
        }
    }

    /**
     * Runs the whole-repo search revset (jj-idea-lpbv) against every repository and merges any
     * commits found into the loaded set, so results outside the log window (e.g. a pasted Git
     * hash or a match in an older commit's description) become visible. One `jj log -r` call per
     * repository, same cost envelope as [loadExpanding].
     *
     * [onComplete] is invoked on EDT after the table has been updated, with the number of
     * returned entries that were not already part of the currently loaded set — so the caller
     * can distinguish "found nothing" from "found only what was already showing".
     */
    fun searchWholeRepo(
        query: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWords: Boolean,
        onComplete: (Int) -> Unit
    ) {
        val revset = logSearchRevset(query, useRegex, matchCase, wholeWords)
        if (revset == null) {
            onComplete(0)
            return
        }
        val repos = repositories()
        val settings = JujutsuSettings.getInstance(project)
        runInBackground {
            val alreadyLoaded = repos.flatMap { it.logCache.all + (expansionEntriesByRepo[it] ?: emptyList()) }
                .mapTo(HashSet()) { it.key }
            val resultsByRepo = fetchSearchResults(repos, revset) { repo -> settings.logChangeLimit(repo) }
            var newCount = 0
            resultsByRepo.forEach { (repo, entries) ->
                repo.logCache.store(entries)
                searchEntriesByRepo[repo] = entries
                newCount += entries.count { it.key !in alreadyLoaded }
            }
            mergeAndNotify()
            runLater { onComplete(newCount) }
        }
    }

    /**
     * Rebuilds the merged (loaded + expansion + search) entry set for every repo, sorts it, and
     * notifies the panel on EDT. The single choke point for every post-initial-load path
     * ([loadMore], [refresh], [forceRefresh], [loadExpanding], [searchWholeRepo]) — all accumulate
     * additive per-repo buckets on top of [JujutsuRepository.logCache], never discarded except by
     * [clearExpansions] on an explicit Refresh. Must be called from a background thread, since it
     * reads [JujutsuRepository.logCache].
     *
     * jj-idea-jnqi: [delta] is the append-only fast path, supplied only by [loadMore] - whose
     * pages are always fetched strictly older than everything already merged, in per-repo
     * topological order. When every guard in [appendGuardHolds] passes, this extends the
     * previous [snapshot] (an O(delta)-ish sort + [CommitGraphBuilder.appendGraph]'s O(delta +
     * bounded window) layout) instead of re-flattening every repo's cache and recomputing from
     * scratch. `null` (every other caller) always takes the full-recompute path below, which
     * also reseeds the incremental engine (via [CommitGraphBuilder.buildGraph]) so a later
     * append has a valid base to build on. Any guard failing falls back to the same full
     * recompute - never incorrect, only slower.
     *
     * Applies [enrichBookmarks] here too (jj-idea-lc43, GitHub #110) — [loadCommits]'s own
     * application at its `allEntries` assignment only covers the very first load; every path that
     * lands here (a paged "Load more", a post-write refresh, a search) would otherwise leave a
     * remote-tracking row's post-deletion garbage ahead/behind count, or a stale divergence
     * number (jj-idea-ks5k), uncorrected.
     * [bookmarkCorrectionsByRepo] is resolved once for every repo, not once per entry.
     */
    private fun mergeAndNotify(delta: List<LogEntry>? = null) = mergeLock.withLock {
        val correctionsByRepo = bookmarkCorrectionsByRepo()
        val current = snapshot
        val hasExpansionOrSearch = expansionEntriesByRepo.isNotEmpty() || searchEntriesByRepo.isNotEmpty()
        val data = if (delta != null &&
            current != null &&
            appendGuardHolds(delta, current, correctionsByRepo, hasExpansionOrSearch)
        ) {
            fastMergeAndNotify(delta, current, correctionsByRepo)
        } else {
            fullMergeAndNotify(correctionsByRepo)
        }
        runLater { notify(data) }
    }

    private fun fastMergeAndNotify(
        delta: List<LogEntry>,
        current: MergedSnapshot,
        correctionsByRepo: Map<JujutsuRepository, BookmarkCorrections>
    ): Data {
        val enrichedDelta = delta.map { enrichBookmarks(it, correctionsByRepo[it.repo] ?: EMPTY_CORRECTIONS) }
        val mergedEntries = current.entries + enrichedDelta
        val graphNodes = graphBuilder.appendGraph(enrichedDelta)
        val deltaMinTimestamp = enrichedDelta.minOfOrNull { it.sortTimestamp() } ?: current.minTimestamp
        snapshot = MergedSnapshot(
            mergedEntries,
            current.keys + enrichedDelta.mapTo(HashSet()) { it.key },
            minOf(current.minTimestamp, deltaMinTimestamp),
            correctionsByRepo
        )
        return Data(mergedEntries, graphNodes, lastLimit)
    }

    private fun fullMergeAndNotify(correctionsByRepo: Map<JujutsuRepository, BookmarkCorrections>): Data {
        val allEntries = repositories().flatMap { r ->
            val regular = r.logCache.all
            val expanded = expansionEntriesByRepo[r] ?: emptyList()
            val searched = searchEntriesByRepo[r] ?: emptyList()
            (regular + expanded + searched).map { entry ->
                enrichBookmarks(entry, correctionsByRepo[r] ?: EMPTY_CORRECTIONS)
            }
        }
        val merged = topologicalSort(allEntries.distinctBy { it.key })
        val graphNodes = graphBuilder.buildGraph(merged)
        snapshot = MergedSnapshot(
            merged,
            merged.mapTo(HashSet()) { it.key },
            merged.minOfOrNull { it.sortTimestamp() } ?: Instant.DISTANT_FUTURE,
            correctionsByRepo
        )
        return Data(merged, graphNodes, lastLimit)
    }

    /**
     * [BookmarkCorrections] per repo, off the already-cached
     * [in.kkkev.jjidea.jj.JujutsuStateModel.references] state (BGT only — see
     * [in.kkkev.jjidea.util.NotifiableState.immediateValue]). Fetched as a single whole-map read,
     * not one [in.kkkev.jjidea.util.NotifiableState.immediateValue] call per repo: on a cold cache
     * that accessor's synchronous load only sets its `hasLoaded` flag, not its cached `value` — a
     * second call in the same pass would see `hasLoaded = true` and return the still-empty start
     * value instead of the just-loaded data.
     */
    private fun bookmarkCorrectionsByRepo(): Map<JujutsuRepository, BookmarkCorrections> =
        project.stateModel.references.immediateValue.mapValues { (_, refs) ->
            val bookmarks = refs.bookmarks.map { it.bookmark }
            BookmarkCorrections(bookmarks.deletedLocalNames(), bookmarks.associateBy { it.name })
        }

    override fun clearExpansions() {
        expansionEntriesByRepo.clear()
        searchEntriesByRepo.clear()
    }

    /**
     * Post-write refresh (jj-idea-2c8k): the cheap path. When paging is viable for every
     * currently-paging repo, refetches *only page 1* per repo (reseeding the frontier fresh) and
     * splices it ahead of whatever deeper pages were already loaded — O(page size), never O(total
     * scrolled depth). This is the fix for GitHub #69. Falls back to [loadCommits] (today's full
     * reload) for any repo that isn't currently being paged (paging off, unpageable revset, or a
     * previous fallback already happened).
     */
    override fun refresh() {
        log.info("Refreshing unified log")
        val settings = JujutsuSettings.getInstance(project)
        if (!pagedLoading) {
            loadCommits()
            return
        }
        val repos = repositories()
        val pagedRepos = repos.filter { pagedWindowByRepo.containsKey(it) }
        if (pagedRepos.isEmpty()) {
            // No repo is currently paged (first load hasn't happened yet, or every repo already
            // fell back) - loadCommits() re-establishes paging where viable.
            loadCommits()
            return
        }
        runInBackground {
            var anyUpdated = false
            for (repo in pagedRepos) {
                if (lockFor(repo).withLock { refreshOneRepoLocked(repo) }) anyUpdated = true
            }
            if (anyUpdated) {
                mergeAndNotify()
            } else {
                // Every previously-paged repo fell back this round - a full reload is the only
                // remaining option for a coherent view.
                runLater { loadCommits() }
            }
        }
    }

    /**
     * The per-repo body of [refresh], run while holding [lockFor]. Returns whether it updated.
     *
     * Re-validates [refresh]'s unlocked `containsKey` candidate filter here inside the lock (the
     * `?: return false` below) rather than trusting it - that filter can go stale between being
     * read and the lock being acquired (e.g. a concurrent [loadMoreOneRepoLocked] removing the
     * window on a fetch failure).
     */
    private fun refreshOneRepoLocked(repo: JujutsuRepository): Boolean {
        val window = pagedWindowByRepo[repo] ?: return false
        val olderPages = window.pages.drop(1)
        window.reset()
        val freshPage1 = fetchOnePage(repo, window)
        if (freshPage1 == null) {
            pagedWindowByRepo.remove(repo)
            return false
        }
        olderPages.forEach { window.recordPage(it) }
        repo.logCache.clear()
        repo.logCache.store(window.entries)
        return true
    }

    /**
     * Explicit Refresh (jj-idea-2c8k): paged re-verification. Re-walks *every currently-loaded
     * page* per repo from scratch (fresh seed, page 1, page 2, ... up to however many pages were
     * loaded before), preserving scroll depth instead of collapsing back to page 1. Cost is
     * O(pages already loaded) — same order as today's single fetch for that total, decomposed
     * into flat per-page calls — paid only on a deliberate Refresh click, never on a write.
     * Falls back to [loadCommits] if paging isn't viable for every repo (kept simple —
     * a partial per-repo fallback would be a fragile hybrid for a rare edge case).
     */
    override fun forceRefresh() {
        log.info("Force-refreshing unified log (explicit Refresh)")
        val settings = JujutsuSettings.getInstance(project)
        val repos = repositories()
        if (!pagedLoading || repos.isEmpty()) {
            loadCommits()
            return
        }
        runInBackground {
            val freshWindows = tryPagedForceRefresh(repos, settings)
            if (freshWindows == null) {
                runLater { loadCommits() }
            } else {
                freshWindows.forEach { (repo, window) ->
                    lockFor(repo).withLock {
                        pagedWindowByRepo[repo] = window
                        repo.logCache.clear()
                        repo.logCache.store(window.entries)
                    }
                }
                mergeAndNotify()
            }
        }
    }

    /**
     * Attempts a full paged re-verification of every repo, each re-walked for as many pages as
     * it had loaded before (at least 1). Returns the fresh windows on success, or `null` if any
     * repo can't be paged (an unpageable revset, a fetch failure, or the safety valve) — the
     * whole multi-repo refresh falls back together in that case, rather than mixing paged and
     * fallback repos in one view.
     *
     * Builds each repo's replacement window as a fresh, not-yet-shared object — the only touch
     * of shared state during the build is reading the *old* window's [PagedLogWindow.pageCount]
     * (to match how many pages to re-walk), locked per repo so it can't race a concurrent
     * mutation of that same old window by [refresh]/[loadMore].
     */
    private fun tryPagedForceRefresh(
        repos: Collection<JujutsuRepository>,
        settings: JujutsuSettings
    ): Map<JujutsuRepository, PagedLogWindow>? {
        val freshWindows = mutableMapOf<JujutsuRepository, PagedLogWindow>()
        for (repo in repos) {
            val revset = settings.resolvedLogRevset(repo)
            if (revset == Revset.Default) return null
            val pageSize = settings.logChangeLimit(repo)
            val targetPageCount = lockFor(repo).withLock {
                (pagedWindowByRepo[repo]?.pageCount ?: 0).coerceAtLeast(1)
            }
            val window = PagedLogWindow(revset, pageSize)
            repeat(targetPageCount) {
                if (!window.isExhausted && fetchOnePage(repo, window) == null) return null
            }
            freshWindows[repo] = window
        }
        return freshWindows
    }

    /**
     * Load one more page (jj-idea-2c8k) — scroll-triggered, or called eagerly ahead of the
     * visible viewport (see the panel's scroll listener). No-op if paging is off, or for any
     * repo not currently being paged or already exhausted.
     *
     * The scroll listener can fire many times in quick succession before the first `loadMore()`
     * finishes, each spawning an independent background task — without per-repo serialization
     * these raced on the same [PagedLogWindow]'s backing list, a real
     * `ConcurrentModificationException` found via manual testing. Uses [ReentrantLock.tryLock]
     * (skip, don't block) rather than [withLock] deliberately: an in-flight fetch for a repo
     * means the *next* scroll event (or this same eager-prefetch check re-running once it
     * completes) will simply try again — no need to queue up redundant work behind a busy repo.
     */
    override fun loadMore() {
        if (!pagedLoading) return
        val candidates = repositories().filter { repo ->
            pagedWindowByRepo[repo]?.let { !it.isExhausted } == true
        }
        if (candidates.isEmpty()) return
        runInBackground {
            // jj-idea-jnqi: collect every repo's newly fetched page into one delta and pass it
            // to mergeAndNotify's append-only fast path, instead of a bare re-merge signal.
            val delta = mutableListOf<LogEntry>()
            for (repo in candidates) {
                val lock = lockFor(repo)
                if (!lock.tryLock()) continue
                try {
                    loadMoreOneRepoLocked(repo)?.let { delta += it }
                } finally {
                    lock.unlock()
                }
            }
            if (delta.isNotEmpty()) mergeAndNotify(delta)
        }
    }

    /**
     * The per-repo body of [loadMore], run while holding [lockFor]. Returns the newly fetched
     * page (jj-idea-jnqi: [loadMore]'s delta for [mergeAndNotify]'s append-only fast path), or
     * `null` if nothing new was fetched.
     *
     * Re-validates both of [loadMore]'s candidate-filter checks (`pagedWindowByRepo` still has
     * this repo, and the window isn't exhausted) here inside the lock, rather than trusting the
     * unlocked filter that built the candidate list - that filter can go stale between being
     * read and the lock being acquired (another thread's [refreshOneRepoLocked] could have
     * removed or replaced the window in between). [fetchOnePage] already no-ops on an exhausted
     * window and the `pageCount` comparison below already made this safe in practice, but the
     * explicit check makes the invariant hold without relying on that as an implementation
     * detail future callers would need to rediscover.
     */
    private fun loadMoreOneRepoLocked(repo: JujutsuRepository): List<LogEntry>? {
        val window = pagedWindowByRepo[repo] ?: return null
        if (window.isExhausted) return null
        val before = window.pageCount
        val entries = fetchOnePage(repo, window)
        if (entries == null) {
            pagedWindowByRepo.remove(repo)
            return null
        }
        if (window.pageCount <= before) return null
        val newPage = window.pages.last()
        repo.logCache.clear()
        repo.logCache.store(entries)
        return newPage
    }
}

/**
 * Waits for [latch] to reach zero, polling [indicator] for cancellation instead of blocking for
 * the full [timeoutMs] (jj-idea-c4tp): a plain `latch.await(timeoutMs, ...)` inside a cancellable
 * [com.intellij.openapi.progress.Task.Backgroundable] ignores `indicator.isCanceled` entirely, so
 * closing the project while the log is still loading could stall for up to [timeoutMs].
 *
 * Returns `true` if the latch counted down in time, `false` if cancelled or the deadline passed.
 */
internal fun awaitCancellably(
    latch: CountDownLatch,
    indicator: ProgressIndicator,
    timeoutMs: Long,
    pollMs: Long = 100
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (latch.await(pollMs, TimeUnit.MILLISECONDS)) return true
        if (indicator.isCanceled) return false
    }
    return latch.count == 0L
}

/**
 * Runs [revset] against each of [repos] via `jj log -r`, capped per-repo at [limitFor]. One
 * process invocation per repo — O(#repos), independent of the number of commits loaded or the
 * repo's total history size.
 *
 * `quiet = true` because a whole-repo search revset failing to resolve (e.g. the query happens
 * to look like a `present(...)` revision that doesn't exist) is an expected, user-triggered
 * outcome, not a bug — logged at INFO rather than WARN (same convention as
 * [in.kkkev.jjidea.jj.RepoLogCache.fetchOne]). A repo whose fetch fails is simply omitted from
 * the result map rather than failing the whole search.
 */
internal fun fetchSearchResults(
    repos: Collection<JujutsuRepository>,
    revset: Expression,
    limitFor: (JujutsuRepository) -> Int
): Map<JujutsuRepository, List<LogEntry>> = repos.mapNotNull { repo ->
    repo.logService.getLog(revset = revset, limit = limitFor(repo), quiet = true)
        .getOrNull()
        ?.takeUnless { it.isEmpty() }
        ?.let { repo to it }
}.toMap()

/**
 * jj-idea-jnqi: the merged (loaded + expansion + search) entry set from the last successful
 * [UnifiedJujutsuLogDataLoader.mergeAndNotify]/[UnifiedJujutsuLogDataLoader.loadCommits] call,
 * kept so a `loadMore()`-shaped append can extend it instead of re-flattening every repo's
 * [in.kkkev.jjidea.jj.JujutsuRepository.logCache] and re-sorting the whole thing from scratch.
 *
 * @param minTimestamp the lowest [sortTimestamp] across [entries] ([Instant.DISTANT_FUTURE] when empty) -
 *   an append guard: a delta entry newer than this could win [topologicalSort]'s timestamp
 *   tiebreak over an already-merged entry, so it can't simply be appended after everything.
 */
internal class MergedSnapshot(
    val entries: List<LogEntry>,
    val keys: Set<ChangeKey>,
    val minTimestamp: Instant,
    val correctionsByRepo: Map<JujutsuRepository, BookmarkCorrections>
)

/** Same tiebreak expression [topologicalSort]'s `PriorityQueue` comparator uses. */
internal fun LogEntry.sortTimestamp(): Instant = authorTimestamp ?: committerTimestamp ?: Instant.DISTANT_PAST

/**
 * jj-idea-jnqi's [UnifiedJujutsuLogDataLoader.mergeAndNotify] append-only fast path guards -
 * any failing falls back to a full recompute (never incorrect, only slower). [delta]/[current]
 * compare raw (pre-enrichment) [ChangeKey]s - [enrichBookmarks] never touches those.
 * A top-level function (rather than a loader method) so it's directly unit-testable without
 * the platform dependencies [UnifiedJujutsuLogDataLoader] itself needs.
 *
 * @param hasExpansionOrSearch whether an expansion ([UnifiedJujutsuLogDataLoader.loadExpanding])
 *   or whole-repo search ([UnifiedJujutsuLogDataLoader.searchWholeRepo]) bucket is in play -
 *   those can insert anywhere in history, not just at the tail, so their presence alone means
 *   the merged set isn't append-only.
 */
internal fun appendGuardHolds(
    delta: List<LogEntry>,
    current: MergedSnapshot,
    correctionsByRepo: Map<JujutsuRepository, BookmarkCorrections>,
    hasExpansionOrSearch: Boolean
): Boolean {
    if (hasExpansionOrSearch) return false
    // Changes what enrichBookmarks does to *already-merged* entries too, not just delta - the
    // cached prefix would need re-enriching, which the fast path skips.
    if (correctionsByRepo != current.correctionsByRepo) return false
    // A delta entry that's a CHILD of an already-merged entry (one of its parents is already
    // in the snapshot) can't simply be appended - topologicalSort would need to place it
    // before that parent, not after everything.
    if (delta.any { entry -> entry.parentKeys.any { it in current.keys } }) return false
    // A delta entry newer than the merged set's oldest entry would win topologicalSort's
    // timestamp tiebreak over that old entry, so it can't just be appended after it.
    if (delta.any { it.sortTimestamp() > current.minTimestamp }) return false
    return true
}

/**
 * Everything a log entry's bookmark chips need from [in.kkkev.jjidea.jj.JujutsuStateModel.references],
 * computed once per repo rather than once per entry: which local bookmark names are
 * pending-deletion (see [enrichBookmarks]'s first correction), and every bookmark's already-derived
 * ahead/behind ([byName], keyed by full name including `@remote`) from
 * [in.kkkev.jjidea.jj.withDerivedDivergence] — the same numbers the bookmarks panel renders
 * (jj-idea-ks5k, GitHub #110). Equality matters: it's the cache key [appendGuardHolds] compares to
 * decide whether the fast append-only path is still valid.
 */
internal data class BookmarkCorrections(val deletedLocalNames: Set<String>, val byName: Map<BookmarkName, Bookmark>)

internal val EMPTY_CORRECTIONS = BookmarkCorrections(emptySet(), emptyMap())

/**
 * Injects pending-deletion local bookmarks, zeroes out garbage ahead/behind counts, and overwrites
 * every bookmark's ahead/behind with [corrections]' already-derived numbers, so a log entry's chips
 * always agree with the bookmarks panel's for the same bookmark (jj-idea-ks5k, GitHub #110) instead
 * of each surface computing (or, for a local ref, failing to compute) its own.
 *
 * When a local bookmark is deleted (`jj bookmark delete foo`) but the remote `foo@origin` still exists,
 * `jj log` omits the deleted local from the entry (it has no target) while `foo@origin` reports a huge
 * `tracking_ahead_count` (distance from absent local to root). This function corrects both problems:
 * - Injects `Bookmark("foo", tracked=true, deleted=true)` at the entry that carries `foo@origin`
 * - Replaces `foo@origin` with zeroed ahead/behind counts (the original values are meaningless)
 */
internal fun enrichBookmarks(entry: LogEntry, corrections: BookmarkCorrections): LogEntry {
    val deletedNames = corrections.deletedLocalNames
    val remotes = entry.bookmarks.filter { it.isRemote && it.localName in deletedNames }
    // Idempotency (jj-idea-lc43): with mergeAndNotify() now also calling this, an entry that
    // already got a local injected by an earlier pass (or that genuinely has both a live local
    // and a stale remote-tracking row of the same name — jj allows that combination) must not
    // grow a second deleted-local Bookmark for the same name.
    val existingLocalNames = entry.bookmarks.filter { !it.isRemote }.mapTo(mutableSetOf()) { it.localName }
    val injectedLocals = remotes.map { it.localName }.distinct()
        .filter { it !in existingLocalNames }
        .map { Bookmark(it, tracked = true, deleted = true) }
    val cleanedRemotes = remotes.map { it.zeroedIfLocalDeleted(deletedNames) }
    val remaining = entry.bookmarks.filter { !it.isRemote || it.localName !in deletedNames }
    val bookmarks = (remaining + injectedLocals + cleanedRemotes).map { bookmark ->
        val derived = corrections.byName[bookmark.name] ?: return@map bookmark
        if (derived.aheadCount == bookmark.aheadCount && derived.behindCount == bookmark.behindCount) {
            bookmark
        } else {
            bookmark.copy(aheadCount = derived.aheadCount, behindCount = derived.behindCount)
        }
    }
    return if (bookmarks == entry.bookmarks) entry else entry.copy(bookmarks = bookmarks)
}

/**
 * Topologically sort log entries so children appear before parents.
 *
 * Uses Kahn's algorithm with timestamp as tiebreaker for unrelated entries.
 * This ensures the graph layout algorithm receives entries in the expected order
 * (children before parents) while maintaining a sensible visual ordering.
 *
 * @param entries List of log entries from one or more repositories
 * @return Entries sorted topologically (children before parents), with newer entries first among siblings
 */
internal fun topologicalSort(entries: List<LogEntry>): List<LogEntry> {
    if (entries.isEmpty()) return emptyList()

    // Build lookup maps
    val entryByKey = entries.associateBy(LogEntry::key)
    val entryKeys = entryByKey.keys

    // Count children for each entry (only counting children that are in our set)
    val childCount = mutableMapOf<ChangeKey, Int>()
    for (entry in entries) {
        childCount[entry.key] = 0
    }
    for (entry in entries) {
        for (parentKey in entry.parentKeys) {
            if (parentKey in entryKeys) {
                childCount[parentKey] = childCount.getValue(parentKey) + 1
            }
        }
    }

    // Priority queue ordered by timestamp (newest first) for tiebreaking
    val ready = PriorityQueue<LogEntry>(compareByDescending { it.authorTimestamp ?: it.committerTimestamp })

    // Start with entries that have no children in the set
    for (entry in entries) {
        if (childCount[entry.key] == 0) {
            ready.add(entry)
        }
    }

    // Process entries in topological order
    val result = mutableListOf<LogEntry>()
    while (ready.isNotEmpty()) {
        val entry = ready.poll()
        result.add(entry)

        // Decrement child count for each parent
        for (parentKey in entry.parentKeys) {
            if (parentKey in entryKeys) {
                val newCount = childCount.getValue(parentKey) - 1
                childCount[parentKey] = newCount
                if (newCount == 0) {
                    ready.add(entryByKey.getValue(parentKey))
                }
            }
        }
    }

    return result
}
