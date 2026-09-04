package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas
import `in`.kkkev.jjidea.ui.components.TextCanvasPanel
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class JujutsuStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val panel = WidgetPanel()
    private var currentRepo: JujutsuRepository? = null
    private var statusBarComponent: JComponent? = null
    private val resizeListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            panel.statusBarWidth = e.component.width
        }
    }

    override fun ID() = JujutsuStatusBarWidgetFactory.ID
    override fun getComponent(): JComponent = panel
    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null

    override fun install(statusBar: StatusBar) {
        panel.onClick = ::openPopup

        statusBar.component?.let { component ->
            statusBarComponent = component
            panel.statusBarWidth = component.width
            component.addComponentListener(resizeListener)
        }

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
        val isMultiRoot = project.stateModel.initialisedRepositories.value.size > 1
        // Data can be read on any thread; UI mutation must happen on EDT.
        runLater {
            currentRepo = repo
            panel.update(repo, entry, isMultiRoot = isMultiRoot)
        }
    }

    private fun openPopup() {
        val repo = currentRepo ?: return
        JujutsuWidgetSupport.rememberRecentRoot(project, repo.directory.path)
        JujutsuWorkingCopySwitcher.createPopup(repo).showUnderneathOf(panel)
    }

    override fun dispose() {
        currentRepo = null
        statusBarComponent?.removeComponentListener(resizeListener)
        statusBarComponent = null
    }

    private class WidgetPanel : JPanel(BorderLayout()) {
        var onClick: (() -> Unit)? = null
        private val content = TextCanvasPanel()
        private val arrow = JLabel(" ▾")
        private var entry: LogEntry? = null

        /** The [StatusBar]'s live component width — read by [WidgetTextLayout.budget] (jj-idea-6nas). */
        var statusBarWidth: Int = 0
            set(value) {
                field = value
                renderTruncated()
            }

        init {
            // Platform status-bar chrome, not hand-picked colours (jj-idea-z5uu, GitHub #95):
            // this panel is a direct, non-JLabel child of the status bar's right panel, so
            // IdeStatusBarImpl already paints the correct hover/pressed background for it
            // (WidgetEffectRenderer.paintBackground, called from paintChildren before this
            // panel ever paints itself) - painting our own here would only overpaint it, and
            // used to do so with the wrong-direction .darker() shade in dark themes. Setting
            // Widget.border() explicitly, rather than leaving it null for wrapCustomStatusBarWidget
            // to install, keeps renderTruncated's insets-based budget correct from the first layout.
            border = JBUI.CurrentTheme.StatusBar.Widget.border()
            isOpaque = false
            // `content` is a plain JPanel (TextCanvasPanel) and defaults to isOpaque=true, unlike
            // TruncatingLeftRightLayout's TextCanvasPanel children which set this explicitly for
            // the same reason: WidgetEffectRenderer.applyEffect sets this outer panel's own
            // `background` field to Widget.HOVER_BACKGROUND on hover start, but never resets it
            // on hover end (it only clears the WIDGET_EFFECT_KEY client property, since stock
            // widgets are expected to key their paint off that, not off getBackground()) - so
            // once `content` is opaque, it paints that dangling colour as a permanent box behind
            // the text after the very first hover, even while nothing is hovered (jj-idea-z5uu).
            content.isOpaque = false
            // Swing gives JPanel/JLabel their own LookAndFeel-installed foreground on
            // construction, which shadows a foreground set only on this outer panel - so
            // Widget.FOREGROUND has to be applied to each leaf that actually paints text too
            // (content's SimpleColoredComponents have no foreground of their own and inherit
            // dynamically from `content`, per Component.getForeground()).
            foreground = JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
            content.foreground = JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
            arrow.foreground = JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
            add(content, BorderLayout.CENTER)
            add(arrow, BorderLayout.EAST)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick?.invoke() ?: Unit
            })
        }

        /**
         * Clamp the whole widget's width to [WidgetTextLayout.budget] (jj-idea-6nas) — belt and
         * braces alongside [renderTruncated]'s fragment-level truncation, in case Swing's own
         * `FontMetrics` measurement of the rendered [com.intellij.ui.SimpleColoredComponent] ever
         * drifts from [in.kkkev.jjidea.ui.components.FragmentLayout]'s.
         */
        override fun getMaximumSize() = capWidth(super.getMaximumSize())
        override fun getPreferredSize() = capWidth(super.getPreferredSize())

        private fun capWidth(size: java.awt.Dimension): java.awt.Dimension {
            val cap = kotlin.math.ceil(WidgetTextLayout.budget(statusBarWidth)).toInt()
            return if (size.width > cap) java.awt.Dimension(cap, size.height) else size
        }

        fun update(repo: JujutsuRepository?, entry: LogEntry?, isMultiRoot: Boolean) {
            this.entry = entry
            renderTruncated()
            isVisible = repo != null
            toolTipText = if (entry != null) buildTooltip(entry, repo, isMultiRoot) else null
        }

        private fun renderTruncated() {
            val entry = entry
            val canvas = if (entry == null) {
                FragmentRecordingCanvas()
            } else {
                // Budget for `content` alone: the panel's own left/right border insets and the
                // arrow label both eat into the overall cap, and aren't part of what `content`
                // gets to render into. RENDER_SAFETY_MARGIN_PX further guards against
                // FragmentLayout's logical-bounds text measurement (java.awt.Font.getStringBounds)
                // coming out a little narrower than what SimpleColoredComponent actually paints -
                // without it, the truncated text can render a few px wider than measured and get
                // clipped by the real layout, cutting off part of the "..." ellipsis (jj-idea-6nas).
                val budget = (
                    WidgetTextLayout.budget(statusBarWidth) -
                        arrow.preferredSize.width -
                        insets.left -
                        insets.right -
                        RENDER_SAFETY_MARGIN_PX
                ).coerceAtLeast(0.0)
                val frc = getFontMetrics(font).fontRenderContext
                FragmentRecordingCanvas(WidgetTextLayout.fit(entry, budget, font, frc))
            }
            content.renderFrom(canvas)
            revalidate()
            repaint()
        }

        private companion object {
            const val RENDER_SAFETY_MARGIN_PX = 4.0
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
