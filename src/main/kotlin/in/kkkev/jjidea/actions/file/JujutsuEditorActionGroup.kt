package `in`.kkkev.jjidea.actions.file

import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import `in`.kkkev.jjidea.actions.file
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
        val editor = e?.getData(CommonDataKeys.EDITOR) ?: return children
        if (!DiffUtil.isDiffEditor(editor)) return children
        val annotate = ActionManager.getInstance().getAction("Annotate")
        return children.filter { it !== annotate }.toTypedArray()
    }
}
