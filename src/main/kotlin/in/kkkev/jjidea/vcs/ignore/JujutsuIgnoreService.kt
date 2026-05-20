package `in`.kkkev.jjidea.vcs.ignore

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class JujutsuIgnoreService {
    private val caches = ConcurrentHashMap<String, GitignoreCache>()

    fun isIgnored(file: VirtualFile, repoRoot: VirtualFile) =
        getCache(repoRoot).isIgnored(File(file.path))

    fun isIgnored(filePath: FilePath, repoRoot: VirtualFile) =
        getCache(repoRoot).isIgnored(File(filePath.path))

    fun getCache(repoRoot: VirtualFile): GitignoreCache =
        caches.computeIfAbsent(repoRoot.path) { GitignoreCache(File(repoRoot.path)) }

    fun invalidate(repo: JujutsuRepository) {
        caches.remove(repo.directory.path)
    }

    companion object {
        fun getInstance(project: Project): JujutsuIgnoreService = project.service()
    }
}
