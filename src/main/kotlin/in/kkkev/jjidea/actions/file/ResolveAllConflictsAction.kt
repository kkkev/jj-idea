package `in`.kkkev.jjidea.actions.file

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.hasWorkingCopyConflicts
import `in`.kkkev.jjidea.actions.change.resolveConflicts
import `in`.kkkev.jjidea.actions.change.workingCopyConflicts

/**
 * Toolbar counterpart to [JujutsuConflictsNode][in.kkkev.jjidea.ui.common.JujutsuConflictsNode]'s
 * inline "Resolve" link (GitHub #56): resolves every conflicted file in the working copy,
 * regardless of what's selected in the changes tree.
 *
 * Deliberately a separate action from [Jujutsu.ResolveSelectedConflicts][ResolveSelectedConflictsAction]
 * rather than reusing it: the working-copy toolbar's target component is the changes tree, so that
 * action's selection-scoped behaviour would make this button disappear whenever the user has a
 * non-conflicted file selected.
 */
class ResolveAllConflictsAction : DumbAwareAction(
    JujutsuBundle.message("action.resolve.all.conflicts"),
    JujutsuBundle.message("action.resolve.all.conflicts.description"),
    AllIcons.Vcs.Merge
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        resolveConflicts(project, workingCopyConflicts(project))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let(::hasWorkingCopyConflicts) ?: false
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
