package `in`.kkkev.jjidea.jj

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers each repo's operation heads (the ids under `.jj/repo/op_heads/heads`) so a change can be
 * noticed without relying on the file watcher (jj-idea-2570.12).
 *
 * A working-copy edit changes nothing in jj until a snapshot, and the snapshot (taken by the next plugin
 * `jj` command, e.g. `jj status`) records a new operation only if a tracked file actually changed. Log,
 * references and the `@` row are all derived from the operation, so the operation heads are the cheap
 * signal that they need reloading. Normally the `op_heads` watch delivers it; this covers setups where it
 * can't (network drives, `idea.filewatcher.disabled`). Cost: one tiny directory listing per [update].
 */
internal class OpHeadsTracker(private val readHeads: (repoKey: String) -> Set<String>?) {
    private val seen = ConcurrentHashMap<String, Set<String>>()

    /**
     * Records [repoKey]'s current heads. Returns true only if they differ from the heads recorded
     * before - never on first sight (nothing to compare with) or when the heads are unreadable.
     */
    fun update(repoKey: String): Boolean {
        val now = readHeads(repoKey) ?: return false
        val previous = seen.put(repoKey, now)
        return previous != null && previous != now
    }
}
