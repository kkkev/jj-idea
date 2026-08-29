package `in`.kkkev.jjidea.vcs.diffbase

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.ex.LocalLineStatusTracker
import com.intellij.openapi.vcs.ex.SimpleLocalLineStatusTracker
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.impl.LineStatusTrackerContentLoader
import com.intellij.openapi.vcs.impl.LineStatusTrackerContentLoader.ContentInfo
import com.intellij.openapi.vcs.impl.LineStatusTrackerContentLoader.TrackerContent
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.vcs.JujutsuVirtualFile
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import java.nio.charset.Charset

/**
 * Overrides the base content IntelliJ's `LineStatusTracker` diffs the editor against, so the
 * gutter change markers (blue/green/red bars) show changes against the configured diff base
 * (jj-idea-fwea / GitHub #43) instead of always `@-`. A no-op — this loader's [isTrackedFile]
 * returns `false` — whenever no custom diff base is configured, falling through to the
 * platform's own default loader.
 *
 * Registered on the `com.intellij.openapi.vcs.impl.LocalLineStatusTrackerProvider` extension
 * point, which is consulted *before* the platform's built-in providers
 * (`LineStatusTrackerManager.getTrackerProvider`), so returning `false` here is exactly
 * "defer to the default".
 *
 * Uses only public IntelliJ API: [SimpleLocalLineStatusTracker] (not the internal
 * `LocalLineStatusTrackerImpl`) for both the tracker type and the `setBaseRevision`/
 * `dropBaseRevision` calls.
 */
class DiffbaseContentLoader : LineStatusTrackerContentLoader {
    /**
     * Runs on the EDT (`LineStatusTrackerManager.switchTracker`), so this must be a cheap
     * settings/status check — never a `jj` invocation. [DiffbaseService.isActive] is exactly
     * that: a settings-map lookup, no CLI call.
     */
    override fun isTrackedFile(project: Project, file: VirtualFile): Boolean {
        if (project.isDisposed || file is JujutsuVirtualFile) return false
        // Ignored/unversioned files have no parent-revision entry to diff against — same guard
        // JujutsuAnnotationProvider.populateCache uses for the same reason.
        val status = ChangeListManager.getInstance(project).getStatus(file)
        if (status == FileStatus.IGNORED || status == FileStatus.UNKNOWN) return false
        return DiffbaseService.getInstance(project).isActive(file)
    }

    override fun isMyTracker(tracker: LocalLineStatusTracker<*>): Boolean = tracker is SimpleLocalLineStatusTracker

    override fun createTracker(project: Project, file: VirtualFile): LocalLineStatusTracker<*>? {
        val document: Document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return SimpleLocalLineStatusTracker.createTracker(project, document, file)
    }

    /** Runs on a pooled thread ([LineStatusTrackerManager]'s `MyBaseRevisionLoader`) — safe to resolve via jj. */
    override fun getContentInfo(project: Project, file: VirtualFile): ContentInfo? {
        val repo = project.possibleJujutsuRepositoryFor(file) ?: return null
        val baseRevision = DiffbaseService.getInstance(project).resolve(repo) ?: return null
        val contentRevision = repo.createContentRevision(file.filePath, baseRevision)
        return DiffbaseContentInfo(contentRevision, file.charset)
    }

    /** Mirrors the platform's own `BaseRevisionStatusTrackerContentLoader.shouldBeUpdated`. */
    override fun shouldBeUpdated(old: ContentInfo?, new: ContentInfo): Boolean {
        val newInfo = new as DiffbaseContentInfo
        return old !is DiffbaseContentInfo ||
            old.contentRevision.revisionNumber != newInfo.contentRevision.revisionNumber ||
            old.contentRevision.revisionNumber == VcsRevisionNumber.NULL ||
            old.charset != newInfo.charset
    }

    override fun loadContent(project: Project, contentInfo: ContentInfo): TrackerContent? {
        val text = (contentInfo as DiffbaseContentInfo).contentRevision.content ?: return null
        return DiffbaseTrackerContent(StringUtil.convertLineSeparators(text))
    }

    override fun setLoadedContent(tracker: LocalLineStatusTracker<*>, content: TrackerContent) {
        (tracker as SimpleLocalLineStatusTracker).setBaseRevision((content as DiffbaseTrackerContent).text)
    }

    override fun handleLoadingError(tracker: LocalLineStatusTracker<*>) {
        (tracker as SimpleLocalLineStatusTracker).dropBaseRevision()
    }

    private data class DiffbaseContentInfo(val contentRevision: ContentRevision, val charset: Charset) : ContentInfo

    private data class DiffbaseTrackerContent(val text: CharSequence) : TrackerContent
}
