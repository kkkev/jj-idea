package `in`.kkkev.jjidea.actions.top

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.LogWindowConfig
import `in`.kkkev.jjidea.ui.log.JujutsuCustomLogTabManager
import `in`.kkkev.jjidea.vcs.isJujutsu
import java.util.UUID

/**
 * Opens a new Jujutsu log tab with a generated default name and all repositories.
 * The tab can be renamed afterward via right-click → Rename….
 *
 * Placed in two locations via plugin.xml:
 *  - `LocalChangesView.TabActions` — standalone "+" in the VCS tool window tab strip (pure-JJ projects).
 *  - `Vcs.Log.ToolWindow.TabActions.DropDown` — always reachable in the Git "▼" dropdown when both
 *    Git and Jujutsu roots coexist.
 */
class NewLogTabAction : DumbAwareAction(
    JujutsuBundle.message("log.action.newtab"),
    JujutsuBundle.message("log.action.newtab.tooltip"),
    AllIcons.General.Add
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val name = nextDefaultTabName(project)
        val config = LogWindowConfig(id = UUID.randomUUID().toString(), name = name)
        // Inherit column widths from the default window so the new tab starts consistently configured.
        JujutsuSettings.getInstance(project)
            .logWindows()
            .firstOrNull { it.id == JujutsuSettings.DEFAULT_LOG_WINDOW_ID }
            ?.columnWidths
            ?.let { config.columnWidths.putAll(it) }

        JujutsuCustomLogTabManager.getInstance(project).openNewLogTab(config)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = e.project.isJujutsu
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun nextDefaultTabName(project: Project): String {
        val base = JujutsuBundle.message("log.window.default.name")
        val existing = JujutsuSettings.getInstance(project).logWindows().map { it.name }
        val highest = existing.mapNotNull { name ->
            when {
                name == base -> 1
                else -> Regex("""^${Regex.escape(base)}\s+(\d+)$""").find(name)
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
        }.maxOrNull() ?: 1
        return "$base ${highest + 1}"
    }
}
