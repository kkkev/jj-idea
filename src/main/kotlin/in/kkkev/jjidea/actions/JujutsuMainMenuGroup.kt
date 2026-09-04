package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * "Jujutsu" submenu in the VCS main menu (`VcsGlobalGroup`, anchored after `Vcs.Specific` —
 * matching `Git.Menu`'s own placement exactly, so "Jujutsu" sits in the same section as "Git"),
 * gathering the plugin's product-specific operations (`Jujutsu.UndoLastOperation`,
 * `Jujutsu.SetDiffBase`, plugin.xml) instead of leaving them as loose top-level items.
 *
 * `Jujutsu.Init` is deliberately *not* one of this group's children — same as `Git.Init` isn't a
 * child of `Git.Menu` — it has its own placement (`Vcs.Import`) consistent with every other VCS
 * integration. Since nothing left in this submenu needs to be reachable before a jj repo exists,
 * the group's own visibility guard can simply be "the project is already a jj repo".
 */
class JujutsuMainMenuGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project.isJujutsu
    }
}
