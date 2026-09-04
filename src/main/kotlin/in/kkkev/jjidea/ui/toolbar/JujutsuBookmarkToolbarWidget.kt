package `in`.kkkev.jjidea.ui.toolbar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.impl.ExpandableComboAction
import `in`.kkkev.jjidea.actions.bookmark.bookmarkActionGroup
import `in`.kkkev.jjidea.actions.bookmark.bookmarkWidgetText
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Main-toolbar bookmark widget: shows the bookmark(s) on `@` (or the nearest ancestor bookmark
 * and its distance, e.g. `"main +3"`) and opens the same create/advance/move/rename/delete
 * /forget/track menu as the former log-toolbar widget, but always visible without opening the
 * Jujutsu log — the location ask from GitHub #62 (jj-idea-94m3). Modelled on Git's
 * `com.intellij.vcs.git.frontend.widget.GitToolbarWidgetAction`.
 *
 * Deliberately keeps no cached state: [update] re-reads [in.kkkev.jjidea.jj.JujutsuStateModel]
 * directly on every poll (its `value` fields are `@Volatile` and safe from BGT), rather than
 * caching a repo/entry snapshot in a plain field written from a subscription callback. That
 * cache-and-subscribe pattern is what caused jj-idea-xsa8's toolbar staleness bug (a BGT read of
 * an EDT-written field racing to null); polling avoids the possibility entirely.
 *
 * When the main toolbar itself is hidden or unavailable (Classic UI, or New UI with "Show main
 * toolbar" off), this widget doesn't render at all — [JujutsuBookmarkStatusBarWidget] takes over
 * as the fallback, mirroring Git's `GitBranchWidget`.
 */
class JujutsuBookmarkToolbarWidget : ExpandableComboAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !project.isJujutsu) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val wcEntries = project.stateModel.workingCopies.value.values.toList()
        val closestByRepo = project.stateModel.closestBookmarks.value
        val text = if (wcEntries.size == 1) {
            val wcEntry = wcEntries.first()
            val onWc = wcEntry.bookmarks.filterNot { it.isRemote }.map { it.name.name }
            bookmarkWidgetText(onWc, closestByRepo[wcEntry.repo])
        } else {
            ""
        }

        e.presentation.isEnabledAndVisible = true
        e.presentation.icon = JujutsuIcons.Bookmark
        e.presentation.text = text
    }

    override fun createPopup(event: AnActionEvent): JBPopup? {
        val project = event.project ?: return null
        val wcEntries = project.stateModel.workingCopies.value.values.toList()
        val bookmarksByRepo = project.stateModel.references.value.mapValues { it.value.bookmarks }
        val closestByRepo = project.stateModel.closestBookmarks.value
        val group = bookmarkActionGroup(wcEntries, bookmarksByRepo, closestByRepo)
        return JBPopupFactory.getInstance().createActionGroupPopup(
            null,
            group,
            event.dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        )
    }
}
