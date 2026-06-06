package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.vfs.AbstractVcsVirtualFile
import com.intellij.openapi.vcs.vfs.VcsFileSystem
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository

private val log = Logger.getInstance(JujutsuVirtualFile::class.java)

/**
 * A virtual file backed by a Jujutsu repository. Used in situations that need a [com.intellij.openapi.vfs.VirtualFile]
 * with repository contents, e.g. when opening an editor.
 *
 * Content is cached in [cached] to satisfy platform reads that arrive under a ReadAction lock.
 * [cacheContents] (called from background threads, e.g. [DiffSideImpl.init] and [OpenChangeFileAction])
 * pre-populates the cache before the platform ever asks.
 *
 * If the platform asks while the cache is cold (e.g. after [MutableContentRegistry] nulled it on
 * a logRefresh), [contentsToByteArray] returns an empty array immediately and kicks off a background
 * load. Once that load finishes it calls [VcsFileSystem.fireContentsChanged] so any open editor
 * reloads and fills in with the real bytes — no blocking, no ReadAction violation.
 */
class JujutsuVirtualFile(
    private val fileAtVersion: FileAtVersion,
    private val repo: JujutsuRepository,
    /** Seam for tests: override to simulate ReadAction being held (or not) without a real Application. */
    internal val isReadAccessAllowed: () -> Boolean = { ApplicationManager.getApplication().isReadAccessAllowed },
    /** Seam for tests: override to avoid launching real background threads from unit tests. */
    private val backgroundExecutor: (Runnable) -> Unit = { r ->
        ApplicationManager.getApplication().executeOnPooledThread(r)
    }
) : AbstractVcsVirtualFile(fileAtVersion.filePath) {
    private val logEntry = repo.getLogEntry(contentLocator)
    private val isImmutable = logEntry?.immutable == true

    // Cache content so EDT reads are non-blocking (cacheContents() pre-populates on a background thread).
    // Mutable revisions have their cache cleared by MutableContentRegistry on logRefresh.
    @Volatile private var cached: ByteArray? = null

    /** True while a background load triggered by the read-access guard is in flight. */
    @Volatile private var loading = false

    init {
        putUserData(JujutsuDataKeys.VIRTUAL_FILE_LOG_ENTRY, logEntry)
        setRevision(fileAtVersion.contentLocator.title)
        if (!isImmutable) MutableContentRegistry.getInstance(repo.project).register(this)
    }

    override fun contentsToByteArray(): ByteArray {
        cached?.let { return it }
        return if (isReadAccessAllowed()) {
            // Guard: never run a blocking subprocess while a ReadAction lock is held.
            // Kick off a background load; once it lands, fireContentsChanged will reload any open editor.
            log.warn("contentsToByteArray called cold under read access for $path — deferring fetch to background")
            if (!loading) {
                loading = true
                backgroundExecutor.invoke(Runnable { loadAndNotify() })
            }
            byteArrayOf()
        } else {
            loadAndCache()
        }
    }

    /** Called by [MutableContentRegistry] on logRefresh to evict stale content. */
    internal fun invalidateContent() {
        cached = null
    }

    /**
     * Loads content on a background thread (safe: no read access held), stores it in [cached], then
     * fires a VFS content-changed event so that any open editor reloads with the fresh bytes.
     */
    private fun loadAndNotify() {
        try {
            val before = cached
            val after = loadAndCache()
            loading = false
            if (after.contentEquals(before ?: byteArrayOf())) return
            val app = ApplicationManager.getApplication()
            app.invokeLater {
                app.runWriteAction {
                    val old = modificationStamp
                    myModificationStamp++
                    VcsFileSystem.getInstance().fireContentsChanged(this, this, old)
                }
            }
        } catch (e: Exception) {
            loading = false
            log.warn("Background content load failed for $path", e)
        }
    }

    /** Fetches and caches content; idempotent if already cached. Returns the (now-cached) bytes. */
    private fun loadAndCache(): ByteArray = cached ?: fetch().also { cached = it }

    private fun fetch() = repo.createContentRevision(fileAtVersion).content?.toByteArray() ?: byteArrayOf()

    override fun isDirectory() = false
    override fun getPresentableName() = fileAtVersion.title

    val contentLocator get() = fileAtVersion.contentLocator
}
