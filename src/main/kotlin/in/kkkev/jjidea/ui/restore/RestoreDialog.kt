package `in`.kkkev.jjidea.ui.restore

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.runRecoverableInBackground
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog letting the user adjust which files get restored before confirming (GitHub #84).
 *
 * Modeled on [in.kkkev.jjidea.ui.split.SplitDialog]'s [FileSelectionPanel] usage, but without a
 * diff preview - restore is a checklist-only confirmation, not a content-picking operation.
 * [changes] is every file that differs from [targetLabel]'s revision; [preSelectedPaths] (the
 * files the user had selected when invoking the action) start ticked, everything else starts
 * unticked so the user opts in to widening the set.
 *
 * A file's checkbox governs *both* sides of a rename/delete: [result] expands each ticked
 * [Change] via [allPaths] so restoring a renamed file restores both its old and new path,
 * matching [in.kkkev.jjidea.actions.restorePaths]'s existing behaviour. Uses the platform
 * [Change.beforeRevision]/[Change.afterRevision] directly rather than converting through
 * [in.kkkev.jjidea.jj.FileChange], which requires a jj-backed [com.intellij.openapi.vcs.changes.ContentRevision]
 * and isn't needed just to read a change's touched paths.
 */
private val Change.allPaths: List<FilePath>
    get() = listOfNotNull(beforeRevision?.file, afterRevision?.file).distinct()

class RestoreDialog(
    project: Project,
    private val targetLabel: String,
    changes: List<Change>,
    preSelectedPaths: Set<FilePath>
) : DialogWrapper(project) {
    var result: List<FilePath>? = null
        private set

    private val allChanges = changes.toList()

    internal val fileSelection = FileSelectionPanel(project)

    private val warningLabel = JBLabel(JujutsuBundle.message("dialog.restore.warning", targetLabel))

    internal val summaryLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    init {
        title = JujutsuBundle.message("dialog.restore.title")
        setOKButtonText(JujutsuBundle.message("dialog.restore.ok"))

        val initialIncluded = allChanges.filter { change -> change.allPaths.any { it in preSelectedPaths } }
        fileSelection.setChanges(allChanges, initialIncluded)
        fileSelection.addInclusionListener { updateSummary() }
        updateSummary()

        init()
    }

    override fun createCenterPanel(): JComponent {
        val outer = JPanel(BorderLayout())
        outer.border = JBUI.Borders.empty(8)

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            warningLabel.alignmentX = JPanel.LEFT_ALIGNMENT
            add(warningLabel)
        }
        outer.add(topPanel, BorderLayout.NORTH)
        outer.add(fileSelection, BorderLayout.CENTER)

        val footerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 0, 0, 0)
            add(summaryLabel)
        }
        outer.add(footerPanel, BorderLayout.SOUTH)

        outer.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(480))
        return outer
    }

    private fun updateSummary() {
        val included = fileSelection.includedChanges.size
        summaryLabel.text = if (included == 1) {
            JujutsuBundle.message("dialog.restore.summary.single")
        } else {
            JujutsuBundle.message("dialog.restore.summary.multiple", included)
        }
    }

    override fun doValidate(): ValidationInfo? =
        if (fileSelection.includedChanges.isEmpty()) {
            ValidationInfo(JujutsuBundle.message("dialog.restore.no.files"), fileSelection.changesTree)
        } else {
            null
        }

    override fun doOKAction() {
        result = fileSelection.includedChanges.flatMap { it.allPaths }.distinct()
        super.doOKAction()
    }

    @org.jetbrains.annotations.TestOnly
    internal fun performOKForTest() = doOKAction()

    @org.jetbrains.annotations.TestOnly
    internal fun doValidateForTest() = doValidate()
}

/**
 * Shared launcher for both restore entry points ([in.kkkev.jjidea.actions.file.RestoreSelectionAction]
 * and [in.kkkev.jjidea.actions.filechange.RestoreToChangeAction]): loads the candidate file list on a
 * background thread (via [runRecoverableInBackground], so a stale workspace surfaces its remedy
 * instead of an empty dialog - jj-idea-27b4), then opens [RestoreDialog] with [preSelected] pre-ticked.
 * An empty [loadChanges] result skips the dialog entirely in favor of a "nothing to restore"
 * notification, matching [in.kkkev.jjidea.actions.change.compareWithWorkingCopyAction]'s empty-diff
 * handling.
 *
 * On confirmation, runs `jj restore` scoped to exactly the ticked files and invokes [onRestored] so
 * each call site keeps its own post-restore refresh behaviour.
 */
internal fun performRestore(
    repo: JujutsuRepository,
    revision: Revision,
    targetLabel: String,
    preSelected: Set<FilePath>,
    errorMessageKey: String,
    undoLabelKey: String,
    loadChanges: () -> List<Change>,
    onRestored: (List<FilePath>) -> Unit
) {
    repo.runRecoverableInBackground(
        retry = {
            performRestore(
                repo,
                revision,
                targetLabel,
                preSelected,
                errorMessageKey,
                undoLabelKey,
                loadChanges,
                onRestored
            )
        }
    ) {
        val changes = loadChanges()

        runLater {
            val project = repo.project

            if (changes.isEmpty()) {
                JujutsuNotifications.notify(
                    project,
                    JujutsuBundle.message("dialog.restore.empty.title"),
                    JujutsuBundle.message("dialog.restore.empty.message", targetLabel),
                    NotificationType.INFORMATION
                )
                return@runLater
            }

            val dialog = RestoreDialog(project, targetLabel, changes, preSelected)
            if (dialog.showAndGet()) {
                val paths = dialog.result.orEmpty()
                if (paths.isEmpty()) return@runLater

                repo.createCommand { restore(paths, revision) }
                    .onSuccess { onRestored(paths) }
                    .onFailure { tellUser(errorMessageKey) }
                    .addUndoTracking(undoLabelKey)
                    .executeAsync()
            }
        }
    }
}
