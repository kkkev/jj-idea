package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.vcs.vfs.AbstractVcsVirtualFile
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository

/**
 * A virtual file backed by a Jujutsu repository. Used in situations that need a [com.intellij.openapi.vfs.VirtualFile]
 * with repository contents, e.g. when opening an editor.
 */
class JujutsuVirtualFile(private val fileAtVersion: FileAtVersion, private val repo: JujutsuRepository) :
    AbstractVcsVirtualFile(fileAtVersion.filePath) {
    private val logEntry = repo.getLogEntry(contentLocator)
    private val isImmutable = logEntry?.immutable == true

    // Cache content so EDT reads are non-blocking (cacheContents() pre-populates on a background thread).
    // Mutable revisions have their cache cleared by MutableContentRegistry on logRefresh.
    @Volatile private var cached: ByteArray? = null

    init {
        putUserData(JujutsuDataKeys.VIRTUAL_FILE_LOG_ENTRY, logEntry)
        setRevision(fileAtVersion.contentLocator.title)
        if (!isImmutable) MutableContentRegistry.getInstance(repo.project).register(this)
    }

    override fun contentsToByteArray() = cached ?: fetch().also { cached = it }

    /** Called by [MutableContentRegistry] on logRefresh to evict stale content. */
    internal fun invalidateContent() {
        cached = null
    }

    private fun fetch() = repo.createContentRevision(fileAtVersion).content?.toByteArray() ?: byteArrayOf()

    override fun isDirectory() = false
    override fun getPresentableName() = fileAtVersion.title

    val contentLocator get() = fileAtVersion.contentLocator
}
