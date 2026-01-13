package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.JujutsuCustomLogTabManager
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import java.awt.datatransfer.StringSelection

/**
 * Context menu actions for the custom Jujutsu log table.
 *
 * Provides actions like Copy Change ID, Copy Description, New Change From This, etc.
 */
object JujutsuLogContextMenuActions {
    private val log = Logger.getInstance(javaClass)

    /**
     * Create the action group for the context menu.
     * Different actions are shown depending on whether the selected entry is the working copy.
     */
    fun createActionGroup(project: Project, entry: LogEntry): DefaultActionGroup = DefaultActionGroup().apply {
        add(CopyChangeIdAction(entry.changeId))
        add(CopyDescriptionAction(entry.description.actual))
        addSeparator()

        // Always offer "New Change From This"
        add(NewChangeFromThisAction(project, entry.changeId))

        // For working copy, also offer "Describe"
        if (entry.isWorkingCopy) {
            add(DescribeWorkingCopyAction(project))
        }

        // Can abandon any change including working copy
        add(AbandonChangeAction(project, entry))

        addSeparator()
        add(ShowChangesAction(project, entry))
    }

    /**
     * Copy Change ID to clipboard.
     */
    private class CopyChangeIdAction(private val changeId: ChangeId) : DumbAwareAction(
        JujutsuBundle.message("log.action.copy.changeid"),
        JujutsuBundle.message("log.action.copy.changeid.tooltip"),
        null
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val selection = StringSelection(changeId.toString())
            CopyPasteManager.getInstance().setContents(selection)
            log.info("Copied change ID to clipboard: $changeId")
        }
    }

    /**
     * Copy Description to clipboard.
     */
    private class CopyDescriptionAction(private val description: String) : DumbAwareAction(
        JujutsuBundle.message("log.action.copy.description"),
        JujutsuBundle.message("log.action.copy.description.tooltip"),
        null
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val selection = StringSelection(description)
            CopyPasteManager.getInstance().setContents(selection)
            log.info("Copied description to clipboard")
        }
    }

    /**
     * Create new change from the selected commit.
     * Uses `jj new <change-id>` to create a new working copy based on this commit.
     */
    private class NewChangeFromThisAction(
        private val project: Project,
        private val changeId: ChangeId
    ) : DumbAwareAction(
        JujutsuBundle.message("log.action.new.from"),
        JujutsuBundle.message("log.action.new.from.tooltip"),
        null
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            // Show modal dialog to get description for the new change
            val description = Messages.showMultilineInputDialog(
                project,
                "Enter description for the new change:",
                "New Change From ${changeId.short}",
                "",
                null,
                null
            ) ?: return // User cancelled

            // Allow empty description - Jujutsu permits it
            val descriptionArg = if (description.isNotBlank()) description.trim() else null

            ApplicationManager.getApplication().executeOnPooledThread {
                val vcs = JujutsuVcs.getVcsWithUserErrorHandling(project, "New Change From This")
                    ?: return@executeOnPooledThread

                val result = vcs.commandExecutor.new(message = descriptionArg, parentRevision = changeId)

                ApplicationManager.getApplication().invokeLater {
                    if (result.isSuccess) {
                        // Refresh both log and working copy tool windows
                        // The new change will be selected automatically (it becomes @)
                        refreshAfterNewChange(project, selectWorkingCopy = true)

                        log.info("Created new change from $changeId with description: ${descriptionArg ?: "(empty)"}")
                    } else {
                        Messages.showErrorDialog(
                            project,
                            "Failed to create new change:\n${result.stderr}",
                            "Error Creating Change"
                        )
                        log.warn("Failed to create new change from $changeId: ${result.stderr}")
                    }
                }
            }
        }
    }

    /**
     * Describe working copy action.
     * Opens a dialog to edit the working copy description.
     */
    private class DescribeWorkingCopyAction(private val project: Project) : DumbAwareAction(
        JujutsuBundle.message("log.action.describe"),
        JujutsuBundle.message("log.action.describe.tooltip"),
        null
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            // Load current description in background thread to avoid EDT violation
            ApplicationManager.getApplication().executeOnPooledThread {
                val vcs = JujutsuVcs.getVcsWithUserErrorHandling(project, "Describe Working Copy")
                    ?: return@executeOnPooledThread

                val currentDescription = getCurrentDescription(vcs)

                // Show dialog on EDT
                ApplicationManager.getApplication().invokeLater {
                    val newDescription = Messages.showMultilineInputDialog(
                        project,
                        "Enter description for working copy:",
                        "Describe Working Copy",
                        currentDescription,
                        null,
                        null
                    ) ?: return@invokeLater // User cancelled

                    if (newDescription.isBlank()) {
                        Messages.showWarningDialog(
                            project,
                            "Description cannot be empty",
                            "Invalid Description"
                        )
                        return@invokeLater
                    }

                    // Execute describe command in background
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val result = vcs.commandExecutor.describe(newDescription.trim())

                        ApplicationManager.getApplication().invokeLater {
                            if (result.isSuccess) {
                                // Refresh both log and working copy tool windows
                                refreshAfterNewChange(project, selectWorkingCopy = true)

                                log.info("Updated working copy description")
                            } else {
                                Messages.showErrorDialog(
                                    project,
                                    "Failed to update description:\n${result.stderr}",
                                    "Error"
                                )
                                log.warn("Failed to update description: ${result.stderr}")
                            }
                        }
                    }
                }
            }
        }

        private fun getCurrentDescription(vcs: JujutsuVcs): String {
            val result = vcs.commandExecutor.log(
                revset = `in`.kkkev.jjidea.jj.WorkingCopy,
                template = "description"
            )
            return if (result.isSuccess) result.stdout.trim() else ""
        }
    }

    /**
     * Abandon change action.
     * Removes the change from the log with confirmation if it has file modifications or a description.
     */
    private class AbandonChangeAction(
        private val project: Project,
        private val entry: LogEntry
    ) : DumbAwareAction(
        JujutsuBundle.message("log.action.abandon"),
        JujutsuBundle.message("log.action.abandon.tooltip"),
        AllIcons.General.Delete
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            // Check if confirmation is needed
            val needsConfirmation = !entry.isEmpty || !entry.description.empty

            if (needsConfirmation) {
                // Build confirmation message based on what will be lost
                val confirmMessage = when {
                    !entry.isEmpty && !entry.description.empty ->
                        JujutsuBundle.message("log.action.abandon.confirm.both")
                    !entry.isEmpty ->
                        JujutsuBundle.message("log.action.abandon.confirm.files")
                    else ->
                        JujutsuBundle.message("log.action.abandon.confirm.description")
                }

                val confirmTitle = JujutsuBundle.message("log.action.abandon.confirm.title", entry.changeId.short)

                // Show yes/no confirmation dialog
                val result = Messages.showYesNoDialog(
                    project,
                    confirmMessage,
                    confirmTitle,
                    Messages.getWarningIcon()
                )

                // If user selected No or cancelled, don't proceed
                if (result != Messages.YES) {
                    log.info("User cancelled abandon of ${entry.changeId}")
                    return
                }
            }

            // Execute abandon in background thread
            ApplicationManager.getApplication().executeOnPooledThread {
                val vcs = JujutsuVcs.getVcsWithUserErrorHandling(project, "Abandon Change")
                    ?: return@executeOnPooledThread

                val result = vcs.commandExecutor.abandon(entry.changeId)

                ApplicationManager.getApplication().invokeLater {
                    if (result.isSuccess) {
                        // Refresh log and select working copy
                        refreshAfterNewChange(project, selectWorkingCopy = true)
                        log.info("Abandoned change ${entry.changeId}")
                    } else {
                        Messages.showErrorDialog(
                            project,
                            JujutsuBundle.message("log.action.abandon.error.message", result.stderr),
                            JujutsuBundle.message("log.action.abandon.error.title")
                        )
                        log.warn("Failed to abandon change ${entry.changeId}: ${result.stderr}")
                    }
                }
            }
        }
    }

    /**
     * Show changes in this commit.
     * Displays what files were changed in this commit.
     */
    private class ShowChangesAction(
        private val project: Project,
        private val entry: LogEntry
    ) : DumbAwareAction(
        JujutsuBundle.message("log.action.show.changes"),
        JujutsuBundle.message("log.action.show.changes.tooltip"),
        null
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            // TODO: Implement showing changes for this commit
            // This will be implemented in jj-idea-4fv (VcsLogDiffHandler)
            Messages.showInfoMessage(
                project,
                "Show changes for ${entry.changeId.short} - Coming soon!",
                "Not Implemented"
            )
            log.info("Show changes requested for ${entry.changeId}")
        }
    }

    /**
     * Helper function to refresh all UI components after VCS state changes.
     *
     * @param project The project to refresh
     * @param selectWorkingCopy If true, select the working copy (@) in the log after refresh
     */
    private fun refreshAfterNewChange(project: Project, selectWorkingCopy: Boolean = false) {
        // Refresh all open custom log tabs
        JujutsuCustomLogTabManager.getInstance(project).refreshAllTabs(selectWorkingCopy)

        // Trigger VCS change list update to refresh working copy tool window
        // This marks everything as dirty and triggers ChangeListListener.changeListUpdateDone()
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
    }
}
