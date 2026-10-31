package `in`.kkkev.jjidea.jj

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which repositories have a plugin [CommandExecutor.Command] running right now, keyed by root path.
 *
 * Background work (the automatic fetch, jj-idea-crik) checks [isBusy] and skips its tick, so it
 * never lands in the middle of a multi-step command such as Undo (`status` -> `op log` -> `undo`).
 * It is a courtesy gate, not a lock: jj's own working-copy lock and lock-free operation store keep
 * the repo consistent regardless. Only commands run through `Command.executeAsync` /
 * `executeWithProgress` are tracked, so a gap between steps while a dialog is open (push dry run
 * -> confirm -> push) is not covered.
 */
object RepoActivity {
    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()

    fun isBusy(repoPath: String) = (inFlight[repoPath]?.get() ?: 0) > 0

    /** Runs [block] with [repoPath] marked busy; a null path (a command with no repo) is not tracked. */
    fun <T> track(repoPath: String?, block: () -> T): T {
        if (repoPath == null) return block()
        val counter = inFlight.computeIfAbsent(repoPath) { AtomicInteger() }
        counter.incrementAndGet()
        try {
            return block()
        } finally {
            counter.decrementAndGet()
        }
    }
}
