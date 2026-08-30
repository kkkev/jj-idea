package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
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
 */
fun filterLogToBookmarkAction(repo: JujutsuRepository, name: String) = nullAndDumbAwareAction(
    name,
    "bookmarks.panel.action.filter",
    AllIcons.General.Filter
) { repo.project.stateModel.filterToReference.notify(name) }

fun navigateLogToBookmarkAction(repo: JujutsuRepository, id: ChangeId?) = nullAndDumbAwareAction(
    id,
    "bookmarks.panel.action.navigate",
    AllIcons.Actions.Find
) { repo.project.stateModel.changeSelection.notify(ChangeKey(repo, target)) }
