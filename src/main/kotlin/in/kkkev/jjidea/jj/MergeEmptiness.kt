package `in`.kkkev.jjidea.jj

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import `in`.kkkev.jjidea.ui.log.TricklePolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Whether a change is empty, as far as is known right now. */
enum class Emptiness {
    EMPTY,
    NOT_EMPTY,

    /** An immutable merge whose emptiness hasn't been fetched yet (see [MergeEmptiness]). */
    PENDING
}

/**
 * Emptiness of immutable merge commits, fetched in the background (jj-idea-2570.8).
 *
 * jj's `empty` merges every parent tree of a merge, which costs ~2 s per 500 log rows on git/git (~40% of rows
 * are merges), so the log template skips it for immutable merges ([LogEntry.emptyDeferred]). Most such merges *are*
 * empty (83-100% on the scale fixtures: a clean merge equals the auto-merge of its parents), so the row can't just
 * say "not empty"; instead it renders as pending until the batch containing it lands here.
 *
 * Answers are cached forever, never invalidated: a commit id hashes the tree and parents, so emptiness is a pure
 * function of it. Failures throw (a failed jj call, or a result missing a requested id, is a bug); ids whose fetch
 * failed stay pending and are not retried, so a repaint loop can't turn one error into a flood.
 *
 * Obtain via [JujutsuRepository.mergeEmptiness].
 */
interface MergeEmptiness {
    /**
     * Non-blocking, any thread, cheap enough for a paint. A deferred entry that isn't cached is queued at **high**
     * priority (on screen right now, so ahead of any [prefetch]ed backlog, newest request first).
     */
    fun peek(entry: LogEntry): Emptiness

    /** Queues the deferred, uncached entries at **low** priority, e.g. when a page of the log lands. */
    fun prefetch(entries: Collection<LogEntry>)

    /** Definite answer for code that acts on it: one `jj` call on a cache miss. */
    @RequiresBackgroundThread
    fun resolve(entry: LogEntry): Boolean

    /** [listener] gets the commit ids each landed batch resolved (on a background thread), until [parent] is disposed (a no-op if it already is). */
    fun addListener(parent: Disposable, listener: (Set<CommitId>) -> Unit)
}

internal class RepoMergeEmptiness(
    /** Emptiness for every id, one `jj` call. Throws on failure; the result must cover every id. */
    private val fetch: (Collection<CommitId>) -> Map<CommitId, Boolean>,
    private val runNow: (Runnable) -> Unit = { AppExecutorUtil.getAppExecutorService().execute(it) },
    private val runLater: (Long, Runnable) -> Unit = { delayMs, task ->
        AppExecutorUtil.getAppScheduledExecutorService().schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }
) : MergeEmptiness {
    private val cache = ConcurrentHashMap<CommitId, Boolean>()
    private val listeners = CopyOnWriteArrayList<(Set<CommitId>) -> Unit>()

    // All guarded by `this`.
    private val high = ArrayDeque<CommitId>() // stack: most recent request first
    private val low = ArrayDeque<CommitId>() // queue
    private val lowSet = mutableSetOf<CommitId>() // the members of [low], for O(1) lookup
    private val known = mutableSetOf<CommitId>() // queued, in flight, or failed - never requested twice
    private var running = false

    override fun peek(entry: LogEntry): Emptiness {
        check(entry.emptyDeferred) { "peek() is for deferred entries; LogEntry.emptiness handles the rest" }
        cache[entry.commitId]?.let { return if (it) Emptiness.EMPTY else Emptiness.NOT_EMPTY }
        enqueue(listOf(entry.commitId), highPriority = true)
        return Emptiness.PENDING
    }

    override fun prefetch(entries: Collection<LogEntry>) =
        enqueue(entries.filter { it.emptyDeferred }.map { it.commitId }, highPriority = false)

    @RequiresBackgroundThread
    override fun resolve(entry: LogEntry): Boolean {
        check(entry.emptyDeferred) { "resolve() is for deferred entries; LogEntry.resolveEmpty handles the rest" }
        cache[entry.commitId]?.let { return it }
        return fetchAndStore(listOf(entry.commitId)).getValue(entry.commitId)
    }

    override fun addListener(parent: Disposable, listener: (Set<CommitId>) -> Unit) {
        listeners += listener
        // The parent may already be disposed (e.g. a log load landing after its panel closed): then it's a no-op.
        if (!Disposer.tryRegister(parent) { listeners -= listener }) listeners -= listener
    }

    private fun enqueue(ids: Collection<CommitId>, highPriority: Boolean) {
        val start = synchronized(this) {
            val fresh = ids.filter { !cache.containsKey(it) && known.add(it) }
            // A visible id already waiting in the backlog is promoted (it's the one the user is looking at).
            val promoted = if (highPriority) ids.filter { it in lowSet } else emptyList()
            if (fresh.isEmpty() && promoted.isEmpty()) return
            if (promoted.isNotEmpty()) {
                lowSet.removeAll(promoted.toSet())
                low.removeAll(promoted.toSet())
            }
            // Reversed so the first of a high-priority group ends up on top of the stack.
            if (highPriority) {
                (promoted + fresh).asReversed().forEach(high::addFirst)
            } else {
                low.addAll(fresh)
                lowSet.addAll(fresh)
            }
            (!running).also { if (it) running = true }
        }
        if (start) runNow(::drain)
    }

    /** Takes the next batch, high priority first. Null (and stops the worker) when nothing is queued. */
    private fun nextBatch(): Pair<List<CommitId>, Boolean>? = synchronized(this) {
        val fromHigh = high.isNotEmpty()
        val queue = if (fromHigh) high else low
        val batch = generateSequence { queue.removeFirstOrNull() }.take(BATCH_SIZE).toList()
        if (!fromHigh) lowSet.removeAll(batch.toSet())
        if (batch.isEmpty()) {
            running = false
            null
        } else {
            batch to fromHigh
        }
    }

    private fun drain() {
        val (batch, wasHigh) = nextBatch() ?: return
        val started = System.nanoTime()
        try {
            fetchAndStore(batch)
        } catch (e: Throwable) {
            // The exception goes to the executor, which reports it. The batch's ids stay in `known`, so they
            // stay pending and aren't retried; anything queued behind them is restarted by the next enqueue().
            synchronized(this) { running = false }
            throw e
        }
        if (wasHigh) {
            runNow(::drain)
        } else {
            // Low-priority backlog: same ~25% duty cycle as the log's idle trickle.
            val workMs = (System.nanoTime() - started) / 1_000_000
            runLater(TricklePolicy.delayAfter(workMs), ::drain)
        }
    }

    private fun fetchAndStore(ids: List<CommitId>): Map<CommitId, Boolean> {
        val result = fetch(ids)
        check(result.keys.containsAll(ids)) { "jj returned emptiness for ${result.keys}, expected $ids" }
        val landed = ids.associateWith { result.getValue(it) }
        cache.putAll(landed)
        listeners.forEach { it(landed.keys) }
        return landed
    }

    companion object {
        /** ~4.5 ms of jj time per merge: 50 is ~0.2 s, short enough that a high-priority request never waits long. */
        const val BATCH_SIZE = 50
    }
}
