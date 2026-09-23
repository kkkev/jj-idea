package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.CommonBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.components.Filter
import `in`.kkkev.jjidea.ui.components.RevisionChoice
import `in`.kkkev.jjidea.ui.components.RevisionChoicePanel
import `in`.kkkev.jjidea.ui.components.buildRevisionChoices
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

object JujutsuWorkingCopySwitcher {
    internal val defaultFilter = Filter(includeRemote = false, includeLogEntries = true)

    fun createPopup(repo: JujutsuRepository): JBPopup {
        val panel = SwitcherPanel(repo)
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel.searchField)
            .setTitle(JujutsuBundle.message("statusbar.switcher.title"))
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .also { panel.setPopup(it) }
        panel.loadData()
        return popup
    }

    internal enum class SwitchMode { EDIT, NEW, CANCEL }

    internal fun chooseSwitchMode(project: Project, entry: LogEntry): SwitchMode {
        val title = JujutsuBundle.message("statusbar.switch.confirm.title")
        val cancel = CommonBundle.getCancelButtonText()
        val newBtn = JujutsuBundle.message("statusbar.switch.confirm.new")
        val icon = Messages.getQuestionIcon()
        return if (entry.immutable) {
            val msg = JujutsuBundle.message(
                "statusbar.switch.confirm.immutable.message",
                entry.id.short,
                entry.description.summary
            )
            if (Messages.showDialog(project, msg, title, arrayOf(newBtn, cancel), 0, icon) == 0) {
                SwitchMode.NEW
            } else {
                SwitchMode.CANCEL
            }
        } else {
            val msg = JujutsuBundle.message(
                "statusbar.switch.confirm.message",
                entry.id.short,
                entry.description.summary
            )
            val edit = JujutsuBundle.message("statusbar.switch.confirm.edit")
            when (Messages.showDialog(project, msg, title, arrayOf(edit, newBtn, cancel), 0, icon)) {
                0 -> SwitchMode.EDIT
                1 -> SwitchMode.NEW
                else -> SwitchMode.CANCEL
            }
        }
    }

    private class SwitcherPanel(repo: JujutsuRepository) : RevisionChoicePanel(repo, defaultFilter) {
        override fun buildItems(filter: Filter) =
            buildRevisionChoices(repo, filter).filter { it !is RevisionChoice.Change || !it.entry.isWorkingCopy }

        override fun onSelect(item: RevisionChoice) {
            val project = repo.project
            val revision = when (item) {
                is RevisionChoice.Change -> item.entry.commitId
                is RevisionChoice.Ref -> item.item.ref
                is RevisionChoice.FreeForm -> item.revision
            }
            runInBackground {
                try {
                    val resolved = repo.logCache[revision]
                    runLater { switchWorkingCopyTo(project, repo, resolved) }
                } catch (_: Exception) {
                    runLater {
                        Messages.showErrorDialog(
                            project,
                            JujutsuBundle.message("statusbar.switch.resolve.error.message"),
                            JujutsuBundle.message("statusbar.switch.resolve.error.title")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Switch the working copy to [entry]: confirms via [JujutsuWorkingCopySwitcher]'s existing
 * immutable-aware dialog (offering "New on Top" instead of a raw edit for an immutable target),
 * then delegates to [editWorkingCopy]/[newChangeOnTop]. The status-bar popup's own click path -
 * unlike the `@`-marker drag gesture (`ui/dnd/DropPerformers.kt`, jj-idea-pk2c/-d3u5) - has no
 * zone geometry to read the intended operation from, so it still needs this dialog to ask.
 */
internal fun switchWorkingCopyTo(project: Project, repo: JujutsuRepository, entry: LogEntry) {
    when (JujutsuWorkingCopySwitcher.chooseSwitchMode(project, entry)) {
        JujutsuWorkingCopySwitcher.SwitchMode.EDIT -> editWorkingCopy(repo, entry)
        JujutsuWorkingCopySwitcher.SwitchMode.NEW -> newChangeOnTop(repo, entry)
        JujutsuWorkingCopySwitcher.SwitchMode.CANCEL -> Unit
    }
}

/**
 * Runs `jj edit <entry>` with undo tracking. Shared by [switchWorkingCopyTo] (after its dialog
 * resolves to EDIT) and the `@`-marker drag gesture's centre-band drop (`ui/dnd/DropPerformers.kt`,
 * jj-idea-d3u5), which calls this directly with no dialog - [in.kkkev.jjidea.ui.dnd.DragContext]
 * already rejects an immutable [entry] before the drag path ever reaches here, so this itself does
 * not (and must not) re-check immutability.
 */
internal fun editWorkingCopy(repo: JujutsuRepository, entry: LogEntry) {
    repo.createCommand { edit(entry.commitId) }
        .onSuccess { invalidate(select = entry.commitId, vfsChanged = true) }
        .onFailure { tellUser("statusbar.switch.edit.error") }
        .addUndoTracking("statusbar.switch.edit.undo")
        .executeAsync()
}

/**
 * Runs `jj new <entry>` (positional parent) with undo tracking. Shared by [switchWorkingCopyTo]
 * (after its dialog resolves to NEW) and the `@`-marker drag gesture's top-band drop
 * (`ui/dnd/DropPerformers.kt`, jj-idea-d3u5), which calls this directly with no dialog and
 * regardless of [entry]'s immutability - `jj new` only ever adds a child, never rewrites its
 * parent, so there is nothing for a guard to reject here.
 */
internal fun newChangeOnTop(repo: JujutsuRepository, entry: LogEntry) {
    repo.createCommand { new(Description.EMPTY, listOf(entry.commitId)) }
        .onSuccess { invalidate(select = WorkingCopy, vfsChanged = true) }
        .onFailure { tellUser("statusbar.switch.new.error") }
        .addUndoTracking("statusbar.switch.new.undo")
        .executeAsync()
}
