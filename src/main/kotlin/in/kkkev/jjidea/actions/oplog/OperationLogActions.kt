package `in`.kkkev.jjidea.actions.oplog

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.operationLog
import `in`.kkkev.jjidea.actions.operationRow
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.oplog.OperationLogView
import `in`.kkkev.jjidea.ui.oplog.OperationRow
import `in`.kkkev.jjidea.ui.oplog.RowActionState
import `in`.kkkev.jjidea.ui.oplog.command
import `in`.kkkev.jjidea.ui.oplog.copyState
import `in`.kkkev.jjidea.ui.oplog.restoreConfirmMessage
import `in`.kkkev.jjidea.ui.oplog.restoreState
import `in`.kkkev.jjidea.ui.oplog.revertState
import `in`.kkkev.jjidea.ui.services.performUndo
import java.awt.datatransfer.StringSelection
import javax.swing.Icon

private val log = Logger.getInstance("in.kkkev.jjidea.actions.oplog.OperationLogActions")

/** Disabled-with-reason: the reason becomes the tooltip/description while disabled. */
private fun applyState(e: AnActionEvent, state: RowActionState) {
    e.presentation.isEnabled = state.enabled
    state.disabledReason?.let { e.presentation.description = it }
}

/**
 * Base for the Operations tab's registered, keymap-assignable row actions (jj-idea-jpfx.2): reads the selected row
 * from [in.kkkev.jjidea.actions.JujutsuDataKeys.OPERATION_ROW], so toolbar, popup and shortcuts share one instance.
 * Outside the tab there is no row, so they are disabled with "Select an operation".
 */
abstract class OperationRowAction(text: String, description: String, icon: Icon?) :
    DumbAwareAction(text, description, icon) {
    // EDT: the row comes from the Swing table selection.
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    internal abstract fun state(row: OperationRow?, view: OperationLogView?): RowActionState

    protected abstract fun perform(e: AnActionEvent, row: OperationRow, view: OperationLogView?)

    override fun update(e: AnActionEvent) = applyState(e, state(e.operationRow, e.operationLog))

    override fun actionPerformed(e: AnActionEvent) {
        val row = e.operationRow ?: return
        perform(e, row, e.operationLog)
    }
}

private fun String.copyToClipboard(what: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(this))
    log.info("Copied $what to clipboard: $this")
}

class RevertOperationAction : OperationRowAction(
    JujutsuBundle.message("oplog.action.revert"),
    JujutsuBundle.message("oplog.action.revert.description"),
    AllIcons.Actions.Rollback
) {
    override fun state(row: OperationRow?, view: OperationLogView?) = revertState(row?.entry)

    override fun perform(e: AnActionEvent, row: OperationRow, view: OperationLogView?) =
        performUndo(e.project ?: row.repo.project, row.repo, row.entry.id)
}

class RestoreOperationAction : OperationRowAction(
    JujutsuBundle.message("oplog.action.restore"),
    JujutsuBundle.message("oplog.action.restore.description"),
    null
) {
    override fun state(row: OperationRow?, view: OperationLogView?) =
        restoreState(row?.entry, row?.let { view?.head(it.repo) })

    override fun perform(e: AnActionEvent, row: OperationRow, view: OperationLogView?) =
        performRestore(e.project ?: row.repo.project, row, view?.laterCount(row) ?: 0)
}

/** Confirms, then runs `jj op restore` on the row's own repository. */
internal fun performRestore(project: Project, row: OperationRow, laterCount: Int) {
    val choice = Messages.showYesNoDialog(
        project,
        restoreConfirmMessage(row.entry, laterCount),
        JujutsuBundle.message("oplog.restore.confirm.title"),
        JujutsuBundle.message("oplog.action.restore.confirm"),
        Messages.getCancelButton(),
        Messages.getWarningIcon()
    )
    if (choice != Messages.YES) return
    row.repo.createCommand { opRestore(row.entry.id) }
        .onSuccess { invalidate(select = WorkingCopy, vfsChanged = true) }
        .onFailure { tellUser("notification.oplog.restore.error") }
        .executeAsync()
}

class CopyOperationIdAction : OperationRowAction(
    JujutsuBundle.message("oplog.action.copy.id"),
    JujutsuBundle.message("oplog.action.copy.id.description"),
    AllIcons.Actions.Copy
) {
    override fun state(row: OperationRow?, view: OperationLogView?) =
        copyState(row?.entry, row?.entry?.id?.toString(), JujutsuBundle.message("oplog.action.noselection"))

    override fun perform(e: AnActionEvent, row: OperationRow, view: OperationLogView?) =
        row.entry.id.toString().copyToClipboard("operation id")
}

class CopyOperationDescriptionAction : OperationRowAction(
    JujutsuBundle.message("oplog.action.copy.description"),
    JujutsuBundle.message("oplog.action.copy.description.description"),
    AllIcons.Actions.Copy
) {
    override fun state(row: OperationRow?, view: OperationLogView?) = copyState(
        row?.entry,
        row?.entry?.description,
        JujutsuBundle.message("oplog.action.copy.description.empty")
    )

    override fun perform(e: AnActionEvent, row: OperationRow, view: OperationLogView?) =
        row.entry.description.copyToClipboard("operation description")
}

class CopyOperationCommandAction : OperationRowAction(
    JujutsuBundle.message("oplog.action.copy.command"),
    JujutsuBundle.message("oplog.action.copy.command.description"),
    AllIcons.Actions.Copy
) {
    override fun state(row: OperationRow?, view: OperationLogView?) = copyState(
        row?.entry,
        row?.entry?.command(),
        JujutsuBundle.message("oplog.action.copy.command.empty")
    )

    override fun perform(e: AnActionEvent, row: OperationRow, view: OperationLogView?) =
        row.entry.command().copyToClipboard("operation command")
}

/** Base for actions on the tab itself: hidden outside it. */
abstract class OperationLogViewAction(text: String, description: String, icon: Icon?) :
    DumbAwareAction(text, description, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    internal open fun state(view: OperationLogView): RowActionState = RowActionState(true)

    protected abstract fun perform(view: OperationLogView)

    override fun update(e: AnActionEvent) {
        val view = e.operationLog
        e.presentation.isVisible = view != null
        if (view != null) applyState(e, state(view)) else e.presentation.isEnabled = false
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.operationLog?.let(::perform)
    }
}

class RefreshOperationLogAction : OperationLogViewAction(
    JujutsuBundle.message("oplog.action.refresh"),
    JujutsuBundle.message("oplog.action.refresh.description"),
    AllIcons.Actions.Refresh
) {
    override fun perform(view: OperationLogView) = view.refresh()
}

class GoToCurrentOperationAction : OperationLogViewAction(
    JujutsuBundle.message("oplog.action.gotohead"),
    JujutsuBundle.message("oplog.action.gotohead.description"),
    AllIcons.Actions.Back
) {
    override fun state(view: OperationLogView) = view.goToHeadState()

    override fun perform(view: OperationLogView) = view.goToHead()
}
