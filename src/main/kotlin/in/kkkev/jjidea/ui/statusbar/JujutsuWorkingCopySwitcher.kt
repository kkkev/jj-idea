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

    private enum class SwitchMode { EDIT, NEW, CANCEL }

    private fun chooseSwitchMode(project: Project, entry: LogEntry): SwitchMode {
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

                    runLater {
                        when (chooseSwitchMode(project, resolved)) {
                            SwitchMode.EDIT -> {
                                repo.commandExecutor
                                    .createCommand { edit(resolved.commitId) }
                                    .onSuccess { repo.invalidate(select = resolved.commitId, vfsChanged = true) }
                                    .onFailure { tellUser(project, "statusbar.switch.edit.error") }
                                    .executeAsync()
                            }

                            SwitchMode.NEW -> {
                                repo.commandExecutor
                                    .createCommand { new(Description.EMPTY, listOf(resolved.commitId)) }
                                    .onSuccess { repo.invalidate(select = WorkingCopy, vfsChanged = true) }
                                    .onFailure { tellUser(project, "statusbar.switch.new.error") }
                                    .executeAsync()
                            }

                            SwitchMode.CANCEL -> {}
                        }
                    }
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
