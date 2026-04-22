package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.LogEntry
import javax.swing.Icon

/**
 * Action on a historical version. Only visible for non-working copy changes.
 */
abstract class HistoricalVersionAction(
    resourceKeyPrefix: String, icon: Icon
) : DumbAwareAction(
        JujutsuBundle.message(resourceKeyPrefix),
        JujutsuBundle.message("$resourceKeyPrefix.description"),
        icon
    ) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = e.logEntry
        val changes = e.changes

        val visible = entry?.let(::isVisible) ?: false
        // Enabled when at least one file has afterRevision (not deleted) and is not working copy
        val enabled = visible && changes.any { it.after?.isWorkingCopy == false }

        e.presentation.isVisible = visible
        e.presentation.isEnabled = enabled
    }

    // Hidden in working copy context or when no entry
    open fun isVisible(entry: LogEntry) = !entry.isWorkingCopy
}
