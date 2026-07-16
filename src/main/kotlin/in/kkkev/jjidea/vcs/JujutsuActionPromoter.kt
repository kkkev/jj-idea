package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.vcs.VcsDataKeys
import `in`.kkkev.jjidea.actions.JujutsuDataKeys

/**
 * Promotes jj actions over IntelliJ's built-in actions they deliberately shadow, but only in the
 * specific context where the shadowing is intentional - everywhere else the platform action keeps
 * its shortcut:
 * - [in.kkkev.jjidea.actions.filechange.ShowDiffAction] over Compare.SameVersion/LastVersion
 *   (Ctrl+D) when genuine VCS/log data is present (changes or a log entry in context).
 * - [in.kkkev.jjidea.actions.change.NewChangeAction] over GotoFile/NewScratchFile (Ctrl+Shift+N)
 *   when the Jujutsu log is the focused context (a log selection is in the data context). This is
 *   what lets the log reuse a mnemonic shortcut without stealing it from the editor.
 */
class JujutsuActionPromoter(
    private val getActionId: (AnAction) -> String? = { ActionManager.getInstance().getId(it) }
) : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val hasVcsData = context.getData(VcsDataKeys.SELECTED_CHANGES)?.isNotEmpty() == true ||
            context.getData(VcsDataKeys.CHANGES)?.isNotEmpty() == true ||
            context.getData(JujutsuDataKeys.LOG_ENTRY) != null
        val hasLogData = context.getData(JujutsuDataKeys.LOG_ENTRIES)?.isNotEmpty() == true ||
            context.getData(JujutsuDataKeys.LOG_ENTRY) != null

        return actions
            .promoteOver("Jujutsu.ShowChangesDiff", BUILTIN_DIFF_ACTIONS, hasVcsData)
            .promoteOver("Jujutsu.NewChange", BUILTIN_NEW_ACTIONS, hasLogData)
    }

    /**
     * Moves the action identified by [promotedId] to the front of the list, but only when it's
     * present, at least one rival in [builtinIds] is also present, and [condition] holds.
     * Otherwise returns the list unchanged.
     */
    private fun List<AnAction>.promoteOver(
        promotedId: String,
        builtinIds: Set<String>,
        condition: Boolean
    ): List<AnAction> {
        val promoted = firstOrNull { getActionId(it) == promotedId } ?: return this
        if (!condition || none { getActionId(it) in builtinIds }) return this
        return listOf(promoted) + filter { it !== promoted }
    }

    companion object {
        private val BUILTIN_DIFF_ACTIONS = setOf("Compare.SameVersion", "Compare.LastVersion")
        private val BUILTIN_NEW_ACTIONS = setOf("GotoFile", "NewScratchFile")
    }
}
