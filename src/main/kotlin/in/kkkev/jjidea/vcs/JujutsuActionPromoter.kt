package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.vcs.VcsDataKeys
import `in`.kkkev.jjidea.actions.JujutsuDataKeys

/**
 * Promotes [in.kkkev.jjidea.actions.filechange.ShowDiffAction] over IntelliJ's built-in
 * Compare.SameVersion / Compare.LastVersion when both compete for the Ctrl+D shortcut,
 * but only in genuine VCS/log contexts (changes or log entry in data context). In a plain
 * editor the promoter is a no-op so EditorDuplicate keeps its shortcut.
 */
class JujutsuActionPromoter(
    private val getActionId: (AnAction) -> String? = { ActionManager.getInstance().getId(it) }
) : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val showDiff = actions.firstOrNull { getActionId(it) == "Jujutsu.ShowChangesDiff" } ?: return actions
        val hasBuiltin = actions.any { getActionId(it) in BUILTIN_DIFF_ACTIONS }
        if (!hasBuiltin) return actions
        val hasVcsData = context.getData(VcsDataKeys.SELECTED_CHANGES)?.isNotEmpty() == true ||
            context.getData(VcsDataKeys.CHANGES)?.isNotEmpty() == true ||
            context.getData(JujutsuDataKeys.LOG_ENTRY) != null
        if (!hasVcsData) return actions
        return listOf(showDiff) + actions.filter { it !== showDiff }
    }

    companion object {
        private val BUILTIN_DIFF_ACTIONS = setOf("Compare.SameVersion", "Compare.LastVersion")
    }
}
