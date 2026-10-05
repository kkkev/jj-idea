package `in`.kkkev.jjidea.ui.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import `in`.kkkev.jjidea.actions.change.workingCopyConflicts

/**
 * Keeps [JujutsuConflictEditorNotificationProvider]'s banner in step with the working copy's
 * conflicted status when it changes *outside* the banner's own accept links - most notably an
 * Undo (`jj op revert`) of an accept, which restores the markers but, unlike
 * [in.kkkev.jjidea.ui.editor.acceptSideCommand], has no file list to refresh notifications for.
 * The provider only decides whether to show a banner when the platform re-invokes it, so without
 * this the banner stayed gone after undo.
 *
 * Runs on every [ChangeListListener.changeListUpdateDone], but only re-triggers the (platform,
 * per-file) notification refresh for files whose conflicted status flipped since the last
 * update: O(conflicted files) per update, no editor churn on unrelated refreshes.
 */
class ConflictBannerRefresher(private val project: Project) : ChangeListListener {
    private var lastConflicted: Set<VirtualFile> = emptySet()

    @Synchronized
    override fun changeListUpdateDone() {
        val now = workingCopyConflicts(project).toSet()
        val notifications = EditorNotifications.getInstance(project)
        conflictStatusFlips(lastConflicted, now).forEach(notifications::updateNotifications)
        lastConflicted = now
    }
}

/** Files that are in exactly one of [before] / [after] - i.e. whose conflicted status changed. */
internal fun conflictStatusFlips(before: Set<VirtualFile>, after: Set<VirtualFile>): Set<VirtualFile> =
    (before - after) + (after - before)
