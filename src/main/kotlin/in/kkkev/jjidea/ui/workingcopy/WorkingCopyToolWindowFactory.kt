package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.vcs.JujutsuRootChecker

/**
 * Factory for creating the Jujutsu working copy tool window
 */
class WorkingCopyToolWindowFactory : ToolWindowFactory, DumbAware, Disposable.Default {
    private data class PanelAndContent(val panel: WorkingCopyPanel, val content: Content)

    // TODO Key by repo, make repos sortable by name (relative path)
    private val panels = sortedMapOf<String, PanelAndContent>()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        project.stateModel.workingCopies.connect(this) { old, new ->
            val oldByRoot = old.associateBy { it.repo }
            val newByRoot = new.associateBy { it.repo }

            // First, look for removals
            (oldByRoot.keys - newByRoot.keys).forEach { removedRoot ->
                panels[removedRoot.relativePath]?.let {
                    toolWindow.contentManager.removeContent(it.content, true)
                }
            }

            // And additions
            (newByRoot.keys - oldByRoot.keys).forEach { addedRoot -> add(project, toolWindow, addedRoot) }

            // Now figure out the changed log entries, and notify the relevant panel
            new.forEach { newEntry ->
                if (newEntry != oldByRoot[newEntry.repo]) {
                    panels[newEntry.repo.relativePath]?.panel?.update(newEntry)
                }
            }
        }
    }

    private fun add(project: Project, toolWindow: ToolWindow, repo: JujutsuRepository) {
        val panel = WorkingCopyPanel(project, repo)
        val title = repo.relativePath
        val content = ContentFactory.getInstance().createContent(panel.getContent(), title, false)
        content.setDisposer(panel)

        val old = panels.put(title, PanelAndContent(panel, content))
        if (old != null) {
            toolWindow.contentManager.removeContent(old.content, true)
        }
        val newPosition = panels.keys.indexOf(title)
        toolWindow.contentManager.addContent(content, newPosition)
    }

    // Check for .jj directory directly - this is a quick filesystem check that's safe on EDT
    // and works before VCS configuration is fully loaded.
    // The ToolWindowEnabler and JujutsuStateModel will handle dynamic updates later.
    override fun shouldBeAvailable(project: Project) =
        project.guessProjectDir()?.let { JujutsuRootChecker.isJujutsuRoot(it) } ?: false
}
