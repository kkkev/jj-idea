package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.actions.abandonChangeAction
import `in`.kkkev.jjidea.vcs.actions.copyChangeIdAction
import `in`.kkkev.jjidea.vcs.actions.copyDescriptionAction
import `in`.kkkev.jjidea.vcs.actions.describeAction
import `in`.kkkev.jjidea.vcs.actions.editChangeAction
import `in`.kkkev.jjidea.vcs.actions.newChangeFromAction

/**
 * Context menu actions for the custom Jujutsu log table.
 *
 * Provides actions like Copy Change ID, Copy Description, New Change From This, etc.
 */
object JujutsuLogContextMenuActions {
    /**
     * Create the action group for the context menu.
     * Different actions are shown depending on whether the selected entry is the working copy.
     */
    fun createActionGroup(
        project: Project,
        entries: List<LogEntry>
    ): DefaultActionGroup =
        DefaultActionGroup().apply {
            val entry = entries.singleOrNull()
            add(copyChangeIdAction(entry?.changeId))
            add(copyDescriptionAction(entry?.description?.actual))
            addSeparator()

            // Always offer "New Change From This"
            add(newChangeFromAction(project, entries.map { it.changeId }))

            // Offer "Edit" for non-working-copy, non-immutable changes
            add(editChangeAction(project, entry?.takeIf { !it.isWorkingCopy && !it.immutable }?.changeId))

            // For working copy, also offer "Describe"
            add(describeAction(project, entry?.changeId))

            // Can abandon any mutable change including working copy
            // TODO Allow abandon on multiple if all entries are immutable
            add(abandonChangeAction(project, entry?.takeIf { !it.immutable }))
        }
}
