package `in`.kkkev.jjidea.jj

import java.util.concurrent.ConcurrentHashMap

/**
 * The current operation id of each repository, as last seen through jj itself (jj-idea-j2vl).
 *
 * Unlike [OpHeadsTracker], which lists `op_heads/heads` on disk, this asks jj (`jj op log -n1`), so it
 * also works where operation state is not a local file at all (custom backends, network mounts; GitHub #115).
 * Cost: one cheap jj process per [read]. The automatic fetch, the op-head poll, and in-IDE commands/refreshes
 * share it, so an operation one of them has already seen does not trigger a second refresh from another.
 */
object OpHeadIds {
    /** Prefix length compared across consumers; full operation ids passed to [record] are normalised to this. */
    const val ID_LENGTH = 12

    /** Short id of an operation, enough to tell heads apart. */
    const val TEMPLATE = "self.id().short($ID_LENGTH)"

    private val last = ConcurrentHashMap<String, String>()
    private val pollers = ConcurrentHashMap.newKeySet<Pair<Any, String>>()

    /** The repo's current operation id, or null if jj failed or printed nothing. Never snapshots or writes. */
    fun read(executor: CommandExecutor): String? =
        (executor.opLog(1, TEMPLATE) as? CommandExecutor.CommandResult.Success)
            ?.stdout?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Records [head] (short or full operation id) as [repoPath]'s current operation. Returns true only if it
     * differs from the one recorded before - never on first sight, which just primes.
     */
    fun record(repoPath: String, head: String): Boolean {
        val short = head.take(ID_LENGTH)
        val previous = last.put(repoPath, short)
        return previous != null && previous != short
    }

    /** Marks the repositories [owner] is actively polling, replacing any earlier set for [owner]. */
    fun setPolled(owner: Any, repoPaths: Collection<String>) {
        pollers.removeIf { it.first === owner }
        repoPaths.forEach { pollers.add(owner to it) }
    }

    /** Marks [repoPath] as actively polled by [owner]. */
    fun addPolled(owner: Any, repoPath: String) {
        pollers.add(owner to repoPath)
    }

    /** True while at least one [OpHeadPollService] is polling [repoPath]. */
    fun isPolled(repoPath: String): Boolean = pollers.any { it.second == repoPath }

    internal fun clear() {
        last.clear()
        pollers.clear()
    }
}
