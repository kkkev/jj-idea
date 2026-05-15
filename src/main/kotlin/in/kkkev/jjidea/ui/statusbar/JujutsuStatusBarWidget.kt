package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.status.EditorBasedStatusBarPopup
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.vcs.isJujutsu
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

@Suppress("UnstableApiUsage")
class JujutsuStatusBarWidget(project: Project) : EditorBasedStatusBarPopup(project, false) {
    override fun ID() = JujutsuStatusBarWidgetFactory.ID

    override fun getWidgetState(file: VirtualFile?): WidgetState {
        if (!project.isJujutsu || file == null) return WidgetState.HIDDEN
        val repo = project.possibleJujutsuRepositoryFor(file) ?: return WidgetState.HIDDEN
        val entry = project.stateModel.repositoryStates.value.find { it.repo == repo }
            ?: return WidgetState.HIDDEN
        return widgetStateFor(entry)
    }

    override fun registerCustomListeners() {
        project.stateModel.repositoryStates.connect(this) {
            myStatusBar?.updateWidget(ID())
        }
    }

    override fun createPopup(dataContext: DataContext): ListPopup? {
        val file = dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val repo = project.possibleJujutsuRepositoryFor(file) ?: return null
        return JujutsuWorkingCopySwitcher.createPopup(repo)
    }

    override fun createInstance(project: Project) = JujutsuStatusBarWidget(project)

    companion object {
        fun displayTextFor(entry: LogEntry): String {
            val sortedBookmarks = entry.bookmarks.sortedBy { it.isRemote }
            return when {
                sortedBookmarks.isNotEmpty() -> sortedBookmarks.joinToString(", ") { it.name }
                !entry.description.empty -> entry.description.summary
                else -> "(no description set)"
            }
        }
    }

    private fun widgetStateFor(entry: LogEntry): WidgetState {
        val text = displayTextFor(entry)
        val tooltip = buildString {
            append("Jujutsu: ")
            if (entry.bookmarks.isNotEmpty()) {
                append(entry.bookmarks.sortedBy { it.isRemote }.joinToString(", ") { it.name })
                append(" ")
            }
            append("(${entry.id.short})")
            if (!entry.description.empty) append(" — ${entry.description.summary}")
            append("\nClick to switch working copy")
        }
        return WidgetState(tooltip, text, true)
    }
}
