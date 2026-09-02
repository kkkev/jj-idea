package `in`.kkkev.jjidea.actions.undo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.services.JujutsuUndoService
import `in`.kkkev.jjidea.ui.services.performUndo

/** Pure presentation logic, extracted for testing without an [AnActionEvent]. */
internal data class UndoLastOperationPresentation(val text: String, val enabled: Boolean, val description: String?)

internal fun resolveUndoLastOperationPresentation(
    current: Pair<JujutsuRepository, JujutsuUndoService.Record>?
): UndoLastOperationPresentation = if (current == null) {
    UndoLastOperationPresentation(
        text = JujutsuBundle.message("action.undo.last.none"),
        enabled = false,
        description = JujutsuBundle.message("action.undo.last.disabled.description")
    )
} else {
    UndoLastOperationPresentation(
        text = JujutsuBundle.message("action.undo.last", current.second.label),
        enabled = true,
        description = null
    )
}

/**
 * Closes the gap left by a dismissed undo balloon: a persistent, discoverable "Undo <last
 * action>" entry naming exactly what it targets, per docs/design/undo-support-roadmap.md's
 * requirement that an undo affordance never act as a blind "Undo" button. Only ever reverts an
 * operation this plugin itself issued and identified by token - never `jj undo`'s "the repo's
 * last operation, whoever made it".
 *
 * Disabled (not hidden - see contributing.md's action-availability-hints tenet) when there is no
 * pending record, or when more than one repository has one: this action has no repo picker, so an
 * ambiguous case is treated as "nothing to undo" rather than guessing.
 */
class UndoLastOperationAction : DumbAwareAction(AllIcons.Actions.Undo) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val presentation =
            resolveUndoLastOperationPresentation(project?.let { JujutsuUndoService.getInstance(it).current() })
        e.presentation.text = presentation.text
        e.presentation.isEnabled = presentation.enabled
        e.presentation.description = presentation.description
        e.presentation.isVisible = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (repo, record) = JujutsuUndoService.getInstance(project).current() ?: return
        performUndo(project, repo, record.operation)
    }
}
