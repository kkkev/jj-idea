package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.stateModel
import java.util.Collections
import java.util.WeakHashMap

/**
 * Tracks mutable [JujutsuVirtualFile] instances and invalidates their cached content on each
 * [in.kkkev.jjidea.jj.JujutsuStateModel.logRefresh]. Uses weak references so that closed diff
 * tabs don't prevent GC of their virtual files.
 *
 * Immutable revisions are never registered here — their content can't change, so no invalidation
 * is needed. See [JujutsuVirtualFile.isImmutable].
 */
@Service(Service.Level.PROJECT)
class MutableContentRegistry(project: Project) : Disposable {
    private val files: MutableSet<JujutsuVirtualFile> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )

    init {
        project.stateModel.logRefresh.connect(this) {
            val snapshot = synchronized(files) { files.toList() }
            for (file in snapshot) file.invalidateContent()
        }
    }

    fun register(file: JujutsuVirtualFile) {
        files.add(file)
    }

    override fun dispose() = files.clear()

    companion object {
        fun getInstance(project: Project): MutableContentRegistry = project.service()
    }
}
