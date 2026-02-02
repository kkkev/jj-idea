package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.pathRelativeTo

/**
 * Filter component for paths.
 */
class JujutsuPathsFilterComponent(private val repo: JujutsuRepository, private val tableModel: JujutsuLogTableModel) :
    JujutsuFilterComponent(JujutsuBundle.message("log.filter.paths")) {
    private val selectedPaths = mutableSetOf<String>()

    override fun getCurrentText(): String = when (selectedPaths.size) {
        0 -> ""
        1 -> selectedPaths.first().substringAfterLast('/')
        else -> JujutsuBundle.message("log.filter.multiple", selectedPaths.size)
    }

    override fun isValueSelected(): Boolean = selectedPaths.isNotEmpty()

    fun initialize() {
        addChangeListener {
            tableModel.setPathsFilter(selectedPaths)
        }
    }

    override fun createActionGroup(): ActionGroup {
        val group = DefaultActionGroup()

        // Add option to select path
        group.add(SelectPathAction())

        // Show selected paths
        if (selectedPaths.isNotEmpty()) {
            group.addSeparator()
            selectedPaths.forEach { path ->
                group.add(RemovePathAction(path))
            }
            group.addSeparator()
            group.add(ClearFilterAction())
        }

        return group
    }

    override fun doResetFilter() {
        selectedPaths.clear()
        notifyFilterChanged()
    }

    private inner class SelectPathAction : AnAction(JujutsuBundle.message("log.filter.paths.select")) {
        override fun actionPerformed(e: AnActionEvent) {
            val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().apply {
                title = JujutsuBundle.message("log.filter.paths.chooser.title")
                description = JujutsuBundle.message("log.filter.paths.chooser.description")
                roots = listOf(repo.directory)
            }

            val dialog = FileChooserFactory.getInstance().createFileChooser(descriptor, repo.project, null)
            val files = dialog.choose(repo.project, repo.directory)

            files.forEach { file ->
                val relativePath = file.pathRelativeTo(repo.directory)
                if (relativePath.isNotEmpty()) {
                    selectedPaths.add(relativePath)
                }
            }

            if (files.isNotEmpty()) {
                notifyFilterChanged()
            }
        }
    }

    private inner class RemovePathAction(private val path: String) : AnAction("Remove: $path") {
        override fun actionPerformed(e: AnActionEvent) {
            selectedPaths.remove(path)
            notifyFilterChanged()
        }
    }

    private inner class ClearFilterAction : AnAction(JujutsuBundle.message("log.filter.clear")) {
        override fun actionPerformed(e: AnActionEvent) {
            doResetFilter()
        }
    }
}
