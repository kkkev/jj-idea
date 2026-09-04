package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.ide.DataManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.actions.bookmark.bookmarkActionGroup
import `in`.kkkev.jjidea.actions.bookmark.bookmarkWidgetText
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
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

        init {
            // Platform status-bar chrome, not hand-picked colours (jj-idea-z5uu, GitHub #95) -
            // see JujutsuStatusBarWidget.WidgetPanel's init for why no hover is painted here.
            border = JBUI.CurrentTheme.StatusBar.Widget.border()
            isOpaque = false
            // Swing gives JLabel its own LookAndFeel-installed foreground on construction, which
            // shadows a foreground set only on this outer panel (see JujutsuStatusBarWidget
            // .WidgetPanel's init) - so Widget.FOREGROUND has to be applied to each label too.
            foreground = JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
            label.foreground = JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
            arrow.foreground = JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
            label.icon = JujutsuIcons.Bookmark
            add(label, BorderLayout.CENTER)
            add(arrow, BorderLayout.EAST)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onClick?.invoke() ?: Unit
                }
            )
        }

        fun update(text: String) {
            label.text = text
            isVisible = text.isNotEmpty()
            revalidate()
            repaint()
        }
    }
}
