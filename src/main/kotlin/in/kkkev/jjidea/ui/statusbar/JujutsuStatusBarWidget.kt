package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas
import `in`.kkkev.jjidea.ui.components.RevisionChoice
import `in`.kkkev.jjidea.ui.components.TextCanvasPanel
import `in`.kkkev.jjidea.ui.components.append
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class JujutsuStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val panel = WidgetPanel()
    private var currentRepo: JujutsuRepository? = null

    companion object {
        private const val MAX_DISPLAY_LEN = 40

        fun displayTextFor(entry: LogEntry): String {
            val sortedBookmarks = entry.bookmarks.sortedBy { it.isRemote }
            val text = when {
                sortedBookmarks.isNotEmpty() -> sortedBookmarks.joinToString(", ") { it.name.name }
                entry.tags.isNotEmpty() -> entry.tags.joinToString(", ") { it.name }
                !entry.description.empty -> entry.description.summary
                else -> "(no description set)"
            }
            return if (text.length > MAX_DISPLAY_LEN) text.take(MAX_DISPLAY_LEN - 1) + "…" else text
        }
    }

    override fun ID() = JujutsuStatusBarWidgetFactory.ID
    override fun getComponent(): JComponent = panel
    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null

    override fun install(statusBar: StatusBar) {
        panel.onClick = ::openPopup

        project.stateModel.workingCopies.connect(this) { _ -> refresh() }

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
            }
        )

        refresh()
    }

    private fun refresh() {
        val selectedFile = FileEditorManager.getInstance(project).selectedEditor?.file
        val repo = JujutsuWidgetSupport.currentRepository(project, selectedFile)
        val entry = repo?.let { project.stateModel.workingCopies.value[it.directory.path] }
        currentRepo = repo
        panel.update(repo, entry, isMultiRoot = project.stateModel.initialisedRepositories.value.size > 1)
    }

    private fun openPopup() {
        val repo = currentRepo ?: return
        JujutsuWidgetSupport.rememberRecentRoot(project, repo.directory.path)
        JujutsuWorkingCopySwitcher.createPopup(repo).showUnderneathOf(panel)
    }

    override fun dispose() {
        currentRepo = null
    }

    private class WidgetPanel : JPanel(BorderLayout()) {
        var onClick: (() -> Unit)? = null
        private val content = TextCanvasPanel()
        private val arrow = JLabel(" ▾")
        private var hovered = false

        init {
            border = JBUI.Borders.empty(0, 4)
            isOpaque = false
            add(content, BorderLayout.CENTER)
            add(arrow, BorderLayout.EAST)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick?.invoke() ?: Unit
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            if (hovered) {
                g.color = UIUtil.getPanelBackground().darker()
                g.fillRect(0, 0, width, height)
            }
            super.paintComponent(g)
        }

        fun update(repo: JujutsuRepository?, entry: LogEntry?, isMultiRoot: Boolean) {
            val canvas = FragmentRecordingCanvas()
            entry?.let { canvas.append(RevisionChoice.Change(it)) }
            content.renderFrom(canvas)
            isVisible = repo != null
            toolTipText = if (entry != null) buildTooltip(entry, repo, isMultiRoot) else null
            revalidate()
            repaint()
        }

        private fun buildTooltip(entry: LogEntry, repo: JujutsuRepository?, isMultiRoot: Boolean) = buildString {
            append("Jujutsu: ")
            if (entry.bookmarks.isNotEmpty()) {
                append(entry.bookmarks.sortedBy { it.isRemote }.joinToString(", ") { it.name.name })
                append(" ")
            }
            if (entry.tags.isNotEmpty()) {
                append(entry.tags.joinToString(", ") { it.name })
                append(" ")
            }
            append("(${entry.id.short})")
            if (!entry.description.empty) append(" — ${entry.description.summary}")
            if (isMultiRoot && repo != null) append("\nRoot: ${repo.directory.name}")
            append("\nClick to switch working copy")
        }
    }
}
