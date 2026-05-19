package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext

/**
 * Promotes [in.kkkev.jjidea.actions.filechange.ShowDiffAction] over IntelliJ's built-in
 * Compare.SameVersion / Compare.LastVersion when both compete for the Ctrl+D shortcut.
 * This ensures the plugin's diff action, which produces consistent titles and uses
 * the jj parent revision correctly, is always preferred in Jujutsu projects.
 */
class JujutsuActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val am = ActionManager.getInstance()
        val showDiff = actions.firstOrNull { am.getId(it) == "Jujutsu.ShowChangesDiff" } ?: return actions
        val hasBuiltin = actions.any { am.getId(it) in BUILTIN_DIFF_ACTIONS }
        if (!hasBuiltin) return actions
        return listOf(showDiff) + actions.filter { it !== showDiff }
    }

    companion object {
        private val BUILTIN_DIFF_ACTIONS = setOf("Compare.SameVersion", "Compare.LastVersion")
    }
}
