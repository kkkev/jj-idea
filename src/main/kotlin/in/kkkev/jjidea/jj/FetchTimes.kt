package `in`.kkkev.jjidea.jj

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * When each remote of each repository was last fetched successfully, this session (jj-idea-crik).
 * jj does not record this: a fetch that changes nothing writes no operation, so the op log cannot
 * tell. Both the manual Fetch action and the automatic fetch call [record]; the Fetch button's
 * tooltip, the Bookmarks panel's remote rows, and the automatic fetch's catch-up read it.
 * In memory only, so it is empty after an IDE restart.
 */
object FetchTimes {
    private val last = ConcurrentHashMap<Pair<String, String>, Instant>()

    /** Test seam. */
    internal var now: () -> Instant = Instant::now

    fun record(repoPath: String, remotes: Collection<String>) {
        val at = now()
        remotes.forEach { last[repoPath to it] = at }
    }

    fun lastFetched(repoPath: String, remote: String): Instant? = last[repoPath to remote]

    /** The most recent fetch of any remote of [repoPath]. */
    fun lastFetched(repoPath: String): Instant? =
        last.entries.filter { it.key.first == repoPath }.maxOfOrNull { it.value }

    /** Per-remote times for [repoPath], in remote-name order. */
    fun lastFetchedByRemote(repoPath: String): List<Pair<String, Instant>> =
        last.entries.filter { it.key.first == repoPath }.map { it.key.second to it.value }.sortedBy { it.first }

    internal fun clear() = last.clear()
}

/** The remotes a fetch with these arguments actually hit (a null [remote] without [allRemotes] means the sole remote). */
fun JujutsuRepository.fetchedRemoteNames(remote: Remote?, allRemotes: Boolean): List<String> =
    if (!allRemotes && remote != null) listOf(remote.name) else gitRemotes.map { it.name }
