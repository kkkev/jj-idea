package `in`.kkkev.jjidea.jj

import java.util.concurrent.ConcurrentHashMap

/**
 * Why a repo's working copy currently can't be read, as reported by `jj log`/`jj status`.
 * Distinguishing [Stale] from [Unreadable] matters because they have different remedies: a
 * stale workspace is fixed in-place by `jj workspace update-stale` (jj-idea-b65g), while an
 * unreadable repo (broken/moved store, incompatible jj version - jj-idea-9ife) has no automated
 * fix and only offers Retry/reconfigure.
 */
sealed interface RepositoryHealth {
    /** jj's raw error message/detail, shown to the user and used for classification. */
    val detail: String

    /**
     * The workspace hasn't been updated since [operation] (jj's `not updated since operation
     * <id>` hint) - `jj workspace update-stale` repairs it in place.
     */
    data class Stale(override val detail: String, val operation: String?) : RepositoryHealth

    /** Broken/moved store, incompatible jj version - jj-idea-9ife's original case. */
    data class Unreadable(override val detail: String) : RepositoryHealth
}

/**
 * jj's own remedy hint - always present verbatim (`Hint: Run \`jj workspace update-stale\`
 * to ...`) on every shape of stale-workspace failure jj reports, unlike the *primary* message
 * line, which varies: "The working copy is stale (not updated since operation X)." when jj can
 * still identify the recorded operation, vs. "Could not read working copy's operation." when the
 * workspace's on-disk operation pointer itself is unreadable/corrupt (verified empirically,
 * jj-idea-b65g: `jj status` reports the latter when `.jj/working_copy/checkout` is corrupted).
 * Matching the hint instead of the primary line's wording catches both.
 */
private val STALE_MARKER = Regex("""workspace update-stale""", RegexOption.IGNORE_CASE)
private val STALE_OPERATION = Regex("""not updated since operation ([0-9a-f]+)""")

/**
 * Classifies a `jj log`/`jj status` failure [message] into a [RepositoryHealth], so
 * [loadWorkingCopies] can offer the right remedy instead of a generic "could not be read"
 * notification for every failure shape.
 */
internal fun classifyRepositoryFailure(message: String): RepositoryHealth =
    if (STALE_MARKER.containsMatchIn(message)) {
        RepositoryHealth.Stale(message, STALE_OPERATION.find(message)?.groupValues?.get(1))
    } else {
        RepositoryHealth.Unreadable(message)
    }

/**
 * Tracks which `.jj` repository roots jj itself currently can't read - a stale workspace, a
 * broken/moved store, or one created by an incompatible jj version (jj-idea-9ife, jj-idea-b65g).
 * Written by [loadWorkingCopies] on every load (readable repos clear their entry, so a repaired
 * repo stops being reported). Global and keyed by absolute repo directory path, not a per-project
 * state, because [in.kkkev.jjidea.vcs.JujutsuRootChecker.validateRoot] is an application-level
 * extension point with no project in scope, and because a repository's readability is a fact
 * about the filesystem, not about any one project's view of it.
 */
object JujutsuRepositoryHealth {
    private val unhealthy = ConcurrentHashMap<String, RepositoryHealth>()

    fun mark(repoPath: String, health: RepositoryHealth) {
        unhealthy[repoPath] = health
    }

    /** Convenience for callers that only have a raw jj error message; classifies then [mark]s. */
    fun markUnreadable(repoPath: String, detail: String) = mark(repoPath, classifyRepositoryFailure(detail))

    fun markReadable(repoPath: String) {
        unhealthy.remove(repoPath)
    }

    fun healthFor(repoPath: String): RepositoryHealth? = unhealthy[repoPath]

    fun detailFor(repoPath: String): String? = unhealthy[repoPath]?.detail

    fun isUnreadable(repoPath: String) = unhealthy.containsKey(repoPath)
}
