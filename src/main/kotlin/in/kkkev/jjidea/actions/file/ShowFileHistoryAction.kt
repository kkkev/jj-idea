package `in`.kkkev.jjidea.actions.file

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.actions.singleRepoForRestore
import `in`.kkkev.jjidea.ui.history.JujutsuFileHistoryTabManager

/**
 * Action to show custom file history for a Jujutsu-managed file.
 *
 * Opens a tab in the VCS tool window showing the file's commit history
 * with the same styling as the custom log view (but without the commit graph).
 *
 * Dual-context, mirroring [in.kkkev.jjidea.actions.filechange.OpenChangeFileAction]/
 * [in.kkkev.jjidea.actions.filechange.RestoreSelectionAction]: the editor/Project View
 * context supplies a real [CommonDataKeys.VIRTUAL_FILE][com.intellij.openapi.actionSystem.CommonDataKeys],
 * but a file-change tree (commit details panel, working copy panel, compare-changes panel) only
 * supplies it when [in.kkkev.jjidea.ui.common.JujutsuChangesTree.showsLocalFiles] is true - it
 * otherwise supplies [in.kkkev.jjidea.actions.changes]/[VcsDataKeys.CHANGES][com.intellij.openapi.vcs.VcsDataKeys],
 * which is why this previously showed as invisible there (jj-idea-v9g4). [filePaths] is used
 * instead of `AnActionEvent.filePaths`'s old (pre jj-idea-c2m8) `fileList`-only behaviour so a
 * deleted file's history is still reachable; [in.kkkev.jjidea.actions.restorePaths] is
 * deliberately *not* used here, since it would return both paths of a rename and defeat the
 * `singleOrNull()` checks below.
 *
 * [hasTreeTarget]/the [filePaths] branch of [actionPerformed] takes priority over
 * [hasEditorTarget]/[AnActionEvent.file]: [filePaths] is non-empty whenever the tree has *any*
 * selection, single or multi, so checking it first correctly disables/ignores a multi-file tree
 * selection instead of silently acting on just the lead-selected file that
 * [in.kkkev.jjidea.ui.common.JujutsuChangesTree] happens to publish as `VIRTUAL_FILE`.
 *
 * [update] deliberately avoids [VcsUtil.getFilePath] (used only in [actionPerformed]) - it
 * requires a live `Application`, which unit tests for `update()` don't provide.
 */
class ShowFileHistoryAction : DumbAwareAction(
    JujutsuBundle.message("history.action.show"),
    JujutsuBundle.message("history.action.show.description"),
    AllIcons.Vcs.History
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    // Only a genuine "no tree selection at all" context (a plain editor tab, Project View) falls
    // back to VIRTUAL_FILE - see the class kdoc for why this must defer to hasTreeTarget.
    private fun hasEditorTarget(e: AnActionEvent) =
        e.filePaths.isEmpty() && e.file?.takeUnless { it.isDirectory } != null && e.repoForFile != null

    private fun hasTreeTarget(e: AnActionEvent) =
        e.filePaths.singleOrNull()?.takeUnless { it.isDirectory } != null && e.singleRepoForRestore != null

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = hasTreeTarget(e) || hasEditorTarget(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (filePath, repo) = e.filePaths.singleOrNull()?.takeUnless { it.isDirectory }?.let { path ->
            e.singleRepoForRestore?.let { repo -> path to repo }
        } ?: e.file?.takeUnless { it.isDirectory }?.takeIf { e.filePaths.isEmpty() }?.let { file ->
            e.repoForFile?.let { repo -> VcsUtil.getFilePath(file) to repo }
        } ?: return

        log.info("Opening custom file history for: ${filePath.path}")

        JujutsuFileHistoryTabManager.getInstance(project).openFileHistory(filePath, repo)
    }
}
