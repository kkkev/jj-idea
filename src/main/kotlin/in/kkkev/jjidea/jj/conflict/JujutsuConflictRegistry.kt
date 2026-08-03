package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level cache of the most recently seen [ConflictInfo] per absolute path, populated by
 * [in.kkkev.jjidea.vcs.changes.JujutsuChangeProvider] on every `jj status`/`resolve --list` pass
 * and consulted by the merge layer to tell modify/delete conflicts apart from ordinary content
 * conflicts.
 */
@Service(Service.Level.PROJECT)
class JujutsuConflictRegistry {
    private val byPath = ConcurrentHashMap<String, ConflictInfo>()

    /** Replaces all entries under [repoDir] with [conflicts]. */
    fun replace(repoDir: VirtualFile, conflicts: Collection<ConflictInfo>) {
        val prefix = repoDir.path + "/"
        byPath.keys.removeIf { it.startsWith(prefix) }
        for (info in conflicts) {
            byPath[repoDir.path + "/" + info.path] = info
        }
    }

    fun get(file: VirtualFile): ConflictInfo? = byPath[file.path]
}

val Project.conflictRegistry: JujutsuConflictRegistry get() = service()
