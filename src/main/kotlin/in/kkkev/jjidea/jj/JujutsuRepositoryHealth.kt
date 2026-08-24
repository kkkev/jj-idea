package `in`.kkkev.jjidea.jj

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which `.jj` repository roots jj itself currently can't read — a broken/stale store, a
 * moved repo, or one created by an incompatible jj version (jj-idea-9ife). Written by
 * [loadWorkingCopies] on every load (readable repos clear their entry, so a repaired repo stops
 * being reported). Global and keyed by absolute repo directory path, not a per-project state,
 * because [in.kkkev.jjidea.vcs.JujutsuRootChecker.validateRoot] is an application-level extension
 * point with no project in scope, and because a repository's readability is a fact about the
 * filesystem, not about any one project's view of it.
 */
object JujutsuRepositoryHealth {
    private val unreadable = ConcurrentHashMap<String, String>()

    fun markUnreadable(repoPath: String, detail: String) {
        unreadable[repoPath] = detail
    }

    fun markReadable(repoPath: String) {
        unreadable.remove(repoPath)
    }

    fun detailFor(repoPath: String): String? = unreadable[repoPath]

    fun isUnreadable(repoPath: String) = unreadable.containsKey(repoPath)
}
