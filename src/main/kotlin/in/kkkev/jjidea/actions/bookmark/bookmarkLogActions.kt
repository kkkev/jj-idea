package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.bookmarkTarget
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.stateModel

/**
 * "Filter log to…" / "Navigate log to…" — the bookmarks panel's (jj-idea-b2ae) two explicit
 * selection actions. Modelled on git4idea's Branches dashboard, where a plain tree selection is
 * inert and these are opt-in actions instead (`BranchesDashboardTreeSelectionHandler`).
 *
 * Both go through existing [in.kkkev.jjidea.jj.JujutsuStateModel] notifiers rather than a direct
 * callback into the log panel, reusing the same routing a bookmark chip's click already uses:
 * [in.kkkev.jjidea.jj.JujutsuStateModel.filterToReference] is consumed by
 * [in.kkkev.jjidea.ui.common.CommitTablePanel] (which toggles the reference filter), and
 * [in.kkkev.jjidea.jj.JujutsuStateModel.changeSelection] is consumed by
 * [in.kkkev.jjidea.ui.log.UnifiedJujutsuLogPanel], whose [in.kkkev.jjidea.ui.log.JujutsuLogTable]
 * already triggers a context-expanding load when the target isn't in the currently loaded window.
 *
 * Each also has a keymap-assignable, registered counterpart below (jj-idea-ib1i) that reads its
 * target from [in.kkkev.jjidea.actions.JujutsuDataKeys.BOOKMARK_TARGET] rather than a fixed
 * closure - both call the same `perform…` function, so there's one implementation behind the two
 * entry points (mirrors [in.kkkev.jjidea.actions.change.rebaseAction]/`performRebase`).
 */
internal fun performFilterLogToBookmark(repo: JujutsuRepository, name: String) =
    repo.project.stateModel.filterToReference.notify(name)

fun filterLogToBookmarkAction(repo: JujutsuRepository, name: String) = nullAndDumbAwareAction(
    name,
    "bookmarks.panel.action.filter",
    AllIcons.General.Filter
) { performFilterLogToBookmark(repo, target) }

internal fun performNavigateToBookmark(repo: JujutsuRepository, id: ChangeId) =
    repo.project.stateModel.changeSelection.notify(ChangeKey(repo, id))

fun navigateLogToBookmarkAction(repo: JujutsuRepository, id: ChangeId?) = nullAndDumbAwareAction(
    id,
    "bookmarks.panel.action.navigate",
    AllIcons.Actions.Find
) { performNavigateToBookmark(repo, target) }

/**
 * Registered, keymap-assignable form of [filterLogToBookmarkAction] (jj-idea-ib1i, GitHub #48
 * split 1/3): reads its target from the bookmarks panel's [in.kkkev.jjidea.actions.JujutsuDataKeys.BOOKMARK_TARGET]
 * instead of a fixed closure, so it appears in Settings > Keymap and can be rebound.
 */
class FilterLogToBookmarkAction : DumbAwareAction(
    JujutsuBundle.message("bookmarks.panel.action.filter"),
    JujutsuBundle.message("bookmarks.panel.action.filter.tooltip"),
    AllIcons.General.Filter
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.bookmarkTarget != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val target = e.bookmarkTarget ?: return
        performFilterLogToBookmark(target.repo, target.bookmark.name.name)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/**
 * Registered, keymap-assignable form of [navigateLogToBookmarkAction] (jj-idea-ib1i). Bound to
 * Enter by default in plugin.xml, so double-clicking a bookmark row (routed through
 * [in.kkkev.jjidea.actions.invokeEnterBoundAction]) navigates to its change the same way
 * double-clicking a log row shows its diff.
 */
class NavigateToBookmarkAction : DumbAwareAction(
    JujutsuBundle.message("bookmarks.panel.action.navigate"),
    JujutsuBundle.message("bookmarks.panel.action.navigate.tooltip"),
    AllIcons.Actions.Find
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.bookmarkTarget?.id != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val target = e.bookmarkTarget ?: return
        val id = target.id ?: return
        performNavigateToBookmark(target.repo, id)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
