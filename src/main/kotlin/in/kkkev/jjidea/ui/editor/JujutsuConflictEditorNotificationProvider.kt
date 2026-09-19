package `in`.kkkev.jjidea.ui.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.util.Alarm
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.resolveConflicts
import `in`.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractor
import `in`.kkkev.jjidea.jj.conflict.countConflictBlocks
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.relativePathOf
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows a banner at the top of the editor for a conflicted jj-tracked file (jj-idea-aunm, GitHub
 * #56 discoverability follow-up; upgraded to a model-accurate banner by jj-idea-lkrt, design doc
 * S1 in docs/design/jj-idea-n6fz-native-conflict-ux.md). Unlike git, a jj conflict has no
 * separate "resolved" flag distinct from the markers themselves - the working file's markers
 * *are* the conflict record, re-parsed from scratch on every snapshot - so this banner:
 *
 * - shows a **live block count**, parsed from the editor [Document] text (not just
 *   [ChangeListManager]'s binary [FileStatus.MERGED_WITH_CONFLICTS] status), that decrements as
 *   the user hand-edits markers and hides once the last block is gone - without waiting for a jj
 *   snapshot;
 * - offers **"Accept &lt;side label&gt;"** links for both sides, using jj's own commit+role
 *   labels ([in.kkkev.jjidea.jj.conflict.ExtractedConflict.currentTitle]/`lastTitle`,
 *   GitHub #112), routed through `jj resolve --tool :ours`/`:theirs` - the same call
 *   [in.kkkev.jjidea.vcs.merge.JujutsuMergeProvider.acceptFilesRevisions] makes, so modify/delete
 *   conflicts correctly delete rather than leaving empty content;
 * - keeps a secondary **"Open Merge Tool"** link that calls the existing [resolveConflicts]
 *   funnel, unchanged. It must never call
 *   [com.intellij.openapi.vcs.AbstractVcsHelper.showMergeDialog] directly; that's the exact call
 *   GitHub #63 found to silently discard a side of the conflict on cancel, and using it here
 *   would reintroduce the bug at a new entry point.
 *
 * The live count is bound to a [DocumentListener] through [DebouncedDocumentScan] - the same
 * 300ms-class debounce pattern [in.kkkev.jjidea.jj.JujutsuStateModel.scheduleRepositoryRefresh]
 * uses - rather than a naive per-keystroke rescan, per contributing.md's refresh-path rules.
 *
 * [collectNotificationData] deliberately does only the two cheap guard checks below (repo +
 * `MERGED_WITH_CONFLICTS` status) and defers every document read / marker parse to the returned
 * [Function], which the platform only invokes once it actually has a live [FileEditor] to attach
 * to - so it never touches [FileDocumentManager] or [JjMarkerConflictExtractor] on the path these
 * guards fail.
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

    private fun createPanel(project: Project, fileEditor: FileEditor, file: VirtualFile): EditorNotificationPanel? {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val bytes = document?.let { it.text.toByteArray(Charsets.UTF_8) } ?: file.contentsToByteArray()
        val initialCount = countConflictBlocks(String(bytes, Charsets.UTF_8))
        if (initialCount == 0) return null

        val conflict = JjMarkerConflictExtractor().extract(bytes)
        val model = conflictBannerModel(conflict)

        val panel = EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Warning)
        panel.text = model.textFor(initialCount)
        panel.toolTipText = JujutsuBundle.message("notification.conflict.tooltip")

        model.acceptCurrent?.let { side ->
            panel.createActionLabel(JujutsuBundle.message("notification.conflict.accept", side.displayLabel)) {
                acceptSide(project, file, side.tool)
            }.toolTipText = side.label
        }
        model.acceptLast?.let { side ->
            panel.createActionLabel(JujutsuBundle.message("notification.conflict.accept", side.displayLabel)) {
                acceptSide(project, file, side.tool)
            }.toolTipText = side.label
        }
        panel.createActionLabel(JujutsuBundle.message("notification.conflict.mergeTool")) {
            resolveConflicts(project, listOf(file))
        }

        if (document != null) {
            val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, fileEditor)
            val debounced = debouncedDocumentScan(alarm) { rescan(document, panel, model) }
            document.addDocumentListener(
                object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) = debounced.onDocumentChanged()
                },
                fileEditor
            )
        }

        return panel
    }

    /** Runs on the alarm's pooled thread - re-scans under a read action, then applies the result on the EDT. */
    private fun rescan(document: Document, panel: EditorNotificationPanel, model: ConflictBannerModel) {
        val count = ReadAction.compute<Int, Nothing> { countConflictBlocks(document.immutableCharSequence) }
        ApplicationManager.getApplication().invokeLater {
            if (count == 0) {
                panel.isVisible = false
            } else {
                panel.isVisible = true
                panel.text = model.textFor(count)
            }
        }
    }

    private fun acceptSide(project: Project, file: VirtualFile, tool: String) {
        val repo = project.possibleJujutsuRepositoryFor(file) ?: return
        repo.createCommand { resolve(listOf(repo.relativePathOf(file)), tool) }
            .onSuccess {
                invalidate(vfsChanged = true)
                EditorNotifications.getInstance(project).updateNotifications(file)
            }
            .onFailure { tellUser("notification.conflict.accept.error") }
            .executeAsync()
    }
}
