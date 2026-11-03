package `in`.kkkev.jjidea.jj

import java.util.concurrent.ConcurrentHashMap

/**
 * The current operation id of each repository, as last seen through jj itself (jj-idea-j2vl).
 *
 * Unlike [OpHeadsTracker], which lists `op_heads/heads` on disk, this asks jj (`jj op log -n1`), so it
 * also works where operation state is not a local file at all (custom backends, network mounts; GitHub #115).
 * Cost: one cheap jj process per [read]. The automatic fetch and the op-head poll share it, so an
 * operation one of them has already seen does not trigger a second refresh from the other.
 */
object OpHeadIds {
    /** Short id of an operation, enough to tell heads apart. */
    const val TEMPLATE = "self.id().short(12)"

    private val last = ConcurrentHashMap<String, String>()

    /** The repo's current operation id, or null if jj failed or printed nothing. Never snapshots or writes. */
    fun read(executor: CommandExecutor): String? =
        (executor.opLog(1, TEMPLATE) as? CommandExecutor.CommandResult.Success)
            ?.stdout?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Records [head] as [repoPath]'s current operation. Returns true only if it differs from the one
     * recorded before - never on first sight, which just primes.
     */
    fun record(repoPath: String, head: String): Boolean {
        val previous = last.put(repoPath, head)
        return previous != null && previous != head
    }

    internal fun clear() = last.clear()
}
