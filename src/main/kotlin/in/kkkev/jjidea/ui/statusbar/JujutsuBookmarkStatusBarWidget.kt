package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.ide.DataManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.actions.bookmark.bookmarkActionGroup
import `in`.kkkev.jjidea.actions.bookmark.bookmarkWidgetText
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Status-bar fallback for [in.kkkev.jjidea.ui.toolbar.JujutsuBookmarkToolbarWidget] — shown when
 * the main toolbar itself isn't available (see [JujutsuBookmarkStatusBarWidgetFactory]). Same
 * text and popup as the main-toolbar widget, built from the same shared
 * [in.kkkev.jjidea.actions.bookmark.bookmarkActionGroup] / [bookmarkWidgetText] helpers.
 */
class JujutsuBookmarkStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val panel = WidgetPanel()

    override fun ID() = JujutsuBookmarkStatusBarWidgetFactory.ID
    override fun getComponent(): JComponent = panel
    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null

    override fun install(statusBar: StatusBar) {
        panel.onClick = ::openPopup
        project.stateModel.workingCopies.connect(this) { _ -> refresh() }
        project.stateModel.closestBookmarks.connect(this) { _ -> refresh() }
        project.stateModel.references.connect(this) { _ -> refresh() }
        refresh()
    }

    private fun refresh() {
        val wcEntries = project.stateModel.workingCopies.value.values.toList()
        val closestByRepo = project.stateModel.closestBookmarks.value
        val text = if (wcEntries.size == 1) {
            val wcEntry = wcEntries.first()
            val onWc = wcEntry.bookmarks.filterNot { it.isRemote }.map { it.name.name }
            bookmarkWidgetText(onWc, closestByRepo[wcEntry.repo])
        } else {
            ""
        }
        // Data can be read on any thread; UI mutation must happen on EDT.
        runLater {
            panel.update(text)
        }
    }

    private fun openPopup() {
        val wcEntries = project.stateModel.workingCopies.value.values.toList()
        val bookmarksByRepo = project.stateModel.references.value.mapValues { it.value.bookmarks }
        val closestByRepo = project.stateModel.closestBookmarks.value
        val group = bookmarkActionGroup(wcEntries, bookmarksByRepo, closestByRepo)
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(panel),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true
            )
            .showUnderneathOf(panel)
    }

    override fun dispose() = Unit

    private class WidgetPanel : JPanel(BorderLayout()) {
        var onClick: (() -> Unit)? = null
        private val label = JLabel()
        private val arrow = JLabel(" ▾")
        private var hovered = false

        init {
            border = JBUI.Borders.empty(0, 4)
            isOpaque = false
            label.icon = JujutsuIcons.Bookmark
            add(label, BorderLayout.CENTER)
            add(arrow, BorderLayout.EAST)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onClick?.invoke() ?: Unit

                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        repaint()
                    }
                }
            )
        }

        override fun paintComponent(g: Graphics) {
            if (hovered) {
                g.color = UIUtil.getPanelBackground().darker()
                g.fillRect(0, 0, width, height)
            }
            super.paintComponent(g)
        }

        fun update(text: String) {
            label.text = text
            isVisible = text.isNotEmpty()
            revalidate()
            repaint()
        }
    }
}
