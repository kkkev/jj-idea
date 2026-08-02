package `in`.kkkev.jjidea.ui.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.resolveConflicts
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows a banner ("This file has conflicts [Resolve]") at the top of the editor for a conflicted
 * jj-tracked file (jj-idea-aunm, GitHub #56 discoverability follow-up). The existing "Resolve
 * Conflicts…" editor right-click action does the same thing but isn't discoverable unless a user
 * already knows to look in the Jujutsu submenu; this mirrors git4idea's
 * `MergeConflictResolveUtil.NotificationProvider`, the platform convention for surfacing per-file
 * conflict resolution.
 *
 * The "Resolve" button funnels through [resolveConflicts] - the same #63-safe
 * [in.kkkev.jjidea.vcs.merge.JujutsuConflictResolver] entry point used everywhere else in the
 * plugin. It must never call [com.intellij.openapi.vcs.AbstractVcsHelper.showMergeDialog]
 * directly; that's the exact call GitHub #63 found to silently discard a side of the conflict on
 * cancel, and using it here would reintroduce the bug at a new entry point.
 */
class JujutsuConflictEditorNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (project.possibleJujutsuRepositoryFor(file) == null) return null
        val status = ChangeListManager.getInstance(project).getChange(file)?.fileStatus
        if (status != FileStatus.MERGED_WITH_CONFLICTS) return null

        return Function { fileEditor -> createPanel(project, fileEditor, file) }
    }

    private fun createPanel(project: Project, fileEditor: FileEditor, file: VirtualFile): EditorNotificationPanel {
        val panel = EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Warning)
        panel.text = JujutsuBundle.message("notification.conflict.text")
        panel.createActionLabel(JujutsuBundle.message("notification.conflict.resolve")) {
            resolveConflicts(project, listOf(file))
        }
        return panel
    }
}
