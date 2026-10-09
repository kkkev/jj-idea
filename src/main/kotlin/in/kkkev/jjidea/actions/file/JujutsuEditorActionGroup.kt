package `in`.kkkev.jjidea.actions.file

import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import `in`.kkkev.jjidea.actions.ManagedActions
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.vcs.diffbase.DiffbaseService
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

/**
 * Action group for Jujutsu VCS in editor context menu
 */
class JujutsuEditorActionGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.file?.let { e.project?.possibleJujutsuRepositoryFor(it) } != null
    }

    /**
     * jj-idea-zmse: the platform's own "Annotate" action already places itself directly into
     * the diff viewer's popup menu (registered on `Diff.EditorPopupMenu`, see AnnotateToggleAction's
     * plugin.xml entry), and this group is *also* added to that same menu (for its other actions),
     * so without filtering, right-clicking in a diff viewer shows "Annotate" twice: once at top
     * level, once nested under "Jujutsu". The plain code editor has no such top-level entry —
     * Jujutsu deliberately isn't registered as a StandardVcsGroup (see plugin.xml), so
     * VersionControlsGroup contributes nothing there and "Jujutsu > Annotate" is the only way to
     * reach it — so keep it in that context.
     */
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val children = super.getChildren(e)
        val annotate = ManagedActions["Annotate"]
        val hideAnnotate = e != null && (isInDiffEditor(e) || isAbsentAtDiffbase(e))
        return if (hideAnnotate) children.filter { it !== annotate }.toTypedArray() else children
    }

    private fun isInDiffEditor(e: AnActionEvent) = e.getData(CommonDataKeys.EDITOR)?.let(DiffUtil::isDiffEditor) == true

    /**
     * jj-idea-bia2: with a custom diff base, a file added after that base has nothing to annotate
     * against. The platform only hides Annotate for files new relative to `@-`, so hide it here
     * (this group is ours) rather than offering an action that can only say "nothing to annotate".
     * [getChildren] runs inside a read action even on BGT, and the platform forbids waiting on a
     * process there, so jj runs on a pooled thread and we only await the (cached per file) answer
     * with cancellation checks, so a pending write action can still cancel the update.
     */
    private fun isAbsentAtDiffbase(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        val file = e.file ?: return false
        val repo = project.possibleJujutsuRepositoryFor(file) ?: return false
        val filePath = file.filePath
        val service = DiffbaseService.getInstance(project)
        val future = ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
            service.isAbsentAtBase(repo, filePath)
        }
        return ProgressIndicatorUtils.awaitWithCheckCanceled(future)
    }
}
