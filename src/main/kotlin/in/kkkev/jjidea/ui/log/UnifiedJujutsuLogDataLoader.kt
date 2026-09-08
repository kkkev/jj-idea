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
                val deletedNamesByRepo = ConcurrentHashMap<JujutsuRepository, Set<String>>()

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

                            repo.logService.getBookmarks().onSuccess { bookmarkItems ->
                                deletedNamesByRepo[repo] = bookmarkItems
                                    .filter { it.bookmark.deleted && !it.bookmark.isRemote }
                                    .map { it.bookmark.localName }
                                    .toSet()
                            }
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

                allEntries = topologicalSort(entriesByRepo.values.flatten())
                    .map { entry -> enrichWithDeletedBookmarks(entry, deletedNamesByRepo[entry.repo] ?: emptySet()) }
                log.info("Merged ${allEntries.size} commits from ${entriesByRepo.size} repositories")
                allEntries.groupBy { it.repo }.forEach { (repo, entries) -> repo.logCache.store(entries) }
                graphNodes = graphBuilder.buildGraph(allEntries)
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
     * notifies the panel on EDT. Shared by [loadExpanding] and [searchWholeRepo] — both accumulate
     * additive per-repo buckets on top of [JujutsuRepository.logCache], never discarded except by
     * [clearExpansions] on an explicit Refresh. Must be called from a background thread, since it
     * reads [JujutsuRepository.logCache].
     */
    private fun mergeAndNotify() {
        val allEntries = repositories().flatMap { r ->
            val regular = r.logCache.all
            val expanded = expansionEntriesByRepo[r] ?: emptyList()
            val searched = searchEntriesByRepo[r] ?: emptyList()
            regular + expanded + searched
        }
        val merged = topologicalSort(allEntries.distinctBy { it.key })
        val data = Data(merged, graphBuilder.buildGraph(merged), lastLimit)
        runLater { notify(data) }
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
            var anyUpdated = false
            for (repo in candidates) {
                val lock = lockFor(repo)
                if (!lock.tryLock()) continue
                try {
                    if (loadMoreOneRepoLocked(repo)) anyUpdated = true
                } finally {
                    lock.unlock()
                }
            }
            if (anyUpdated) mergeAndNotify()
        }
    }

    /**
     * The per-repo body of [loadMore], run while holding [lockFor]. Returns whether it updated.
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
    private fun loadMoreOneRepoLocked(repo: JujutsuRepository): Boolean {
        val window = pagedWindowByRepo[repo] ?: return false
        if (window.isExhausted) return false
        val before = window.pageCount
        val entries = fetchOnePage(repo, window)
        if (entries == null) {
            pagedWindowByRepo.remove(repo)
            return false
        }
        if (window.pageCount <= before) return false
        repo.logCache.clear()
        repo.logCache.store(entries)
        return true
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
 * Injects pending-deletion local bookmarks into log entries and zeroes out garbage ahead/behind counts.
 *
 * When a local bookmark is deleted (`jj bookmark delete foo`) but the remote `foo@origin` still exists,
 * `jj log` omits the deleted local from the entry (it has no target) while `foo@origin` reports a huge
 * `tracking_ahead_count` (distance from absent local to root). This function corrects both problems:
 * - Injects `Bookmark("foo", tracked=true, deleted=true)` at the entry that carries `foo@origin`
 * - Replaces `foo@origin` with zeroed ahead/behind counts (the original values are meaningless)
 */
internal fun enrichWithDeletedBookmarks(entry: LogEntry, deletedNames: Set<String>): LogEntry {
    if (deletedNames.isEmpty()) return entry
    val remotes = entry.bookmarks.filter { it.isRemote && it.localName in deletedNames }
    if (remotes.isEmpty()) return entry
    val injectedLocals = remotes.map { Bookmark(it.localName, tracked = true, deleted = true) }
    val cleanedRemotes = remotes.map { it.copy(aheadCount = 0, behindCount = 0) }
    val remaining = entry.bookmarks.filter { !it.isRemote || it.localName !in deletedNames }
    return entry.copy(bookmarks = remaining + injectedLocals + cleanedRemotes)
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
