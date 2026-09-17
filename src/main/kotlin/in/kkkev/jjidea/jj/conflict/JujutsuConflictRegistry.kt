package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

private const val MAX_CACHED_REVISIONS = 32

/**
 * Project-level cache of the most recently seen [ConflictInfo], populated by
 * [in.kkkev.jjidea.vcs.changes.JujutsuChangeProvider] (working copy, on every `jj status`/
 * `resolve --list` pass) and [in.kkkev.jjidea.jj.ChangeService] (arbitrary revisions, on every
 * `jj resolve --list -r <rev>` for a conflicted log entry).
 *
 * The working copy has its own slot, keyed by absolute path and never evicted - it's the hot
 * path consulted by the merge layer via [get] to tell modify/delete conflicts apart from
 * ordinary content conflicts. Other revisions live in a bounded, access-ordered cache capped at
 * [MAX_CACHED_REVISIONS] entries (one entry per revision, each holding that revision's full
 * conflicted-path map) so browsing many historical commits across a long session can't leak
 * memory indefinitely.
 */
@Service(Service.Level.PROJECT)
class JujutsuConflictRegistry {
    private val workingCopyByPath = ConcurrentHashMap<String, ConflictInfo>()

    private val byRevision = Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<String, ConflictInfo>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, ConflictInfo>>) =
                size > MAX_CACHED_REVISIONS
        }
    )

    /**
     * Replaces the cached conflicts for [revision] under [repoDir] with [conflicts]. For
     * [WorkingCopy] (the default), this preserves prior behaviour: wipes and refills the
     * absolute-path working-copy slot. For any other revision, only that revision's entry is
     * replaced; other cached revisions (and the working copy) are untouched.
     */
    fun replace(repoDir: VirtualFile, conflicts: Collection<ConflictInfo>, revision: Revision = WorkingCopy) {
        if (revision == WorkingCopy) {
            val prefix = repoDir.path + "/"
            workingCopyByPath.keys.removeIf { it.startsWith(prefix) }
            for (info in conflicts) {
                workingCopyByPath[repoDir.path + "/" + info.path] = info
            }
        } else {
            byRevision[revisionKey(repoDir, revision)] = conflicts.associateBy { it.path }
        }
    }

    /** Looks up the working-copy conflict info for [file], if any. */
    fun get(file: VirtualFile): ConflictInfo? = workingCopyByPath[file.path]

    /** Looks up the conflict info for [file] (relative to [repoDir]) at [revision]. */
    fun get(repoDir: VirtualFile, file: VirtualFile, revision: Revision): ConflictInfo? {
        if (revision == WorkingCopy) return get(file)
        val relativePath = file.path.removePrefix(repoDir.path + "/")
        return byRevision[revisionKey(repoDir, revision)]?.get(relativePath)
    }

    /** All cached conflicts for [repoDir] at [revision], keyed by path relative to [repoDir]. */
    fun conflicts(repoDir: VirtualFile, revision: Revision): Map<String, ConflictInfo> {
        if (revision == WorkingCopy) {
            val prefix = repoDir.path + "/"
            return workingCopyByPath.mapNotNull { (path, info) ->
                path.takeIf { it.startsWith(prefix) }?.let { info.path to info }
            }.toMap()
        }
        return byRevision[revisionKey(repoDir, revision)].orEmpty()
    }

    private fun revisionKey(repoDir: VirtualFile, revision: Revision) = "${repoDir.path}@$revision"

    @get:TestOnly
    internal val cachedRevisionCount: Int get() = byRevision.size
}

val Project.conflictRegistry: JujutsuConflictRegistry get() = service()
