package `in`.kkkev.jjidea.ui.common

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Shared shell for a per-file native diff preview, extracted from
 * [in.kkkev.jjidea.ui.split.SplitDialog]'s original inline preview so [SquashIntoDialog] can
 * offer the same UX. Owns only the mechanical parts — a header label showing the selected
 * file name (or a placeholder), a [DiffRequestPanel], and an optional footer bar for extra
 * controls (e.g. Split's "Pick Hunks…" button, added via [addFooterComponent]).
 *
 * Content derivation (what the two diff sides actually contain) stays with each caller — see
 * [in.kkkev.jjidea.ui.split.SplitDialog.updateDiffPreview] and
 * [in.kkkev.jjidea.ui.squash.SquashFilePreview] — this panel only renders whatever
 * [DiffRequest] it's given.
 */
class FileDiffPreviewPanel(project: Project, parentDisposable: Disposable) : JPanel(BorderLayout()) {
    private val header = JBLabel(
        JujutsuBundle.message("dialog.preview.select"),
        SwingConstants.CENTER
    ).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(Font.BOLD)
    }

    private val diffPanel: DiffRequestPanel =
        DiffManager.getInstance().createRequestPanel(project, parentDisposable, null)
    private var currentRequest: DiffRequest? = null

    /** Footer bar below the diff, empty by default. Callers add controls via [addFooterComponent]. */
    val footer: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.emptyTop(4)
        add(Box.createHorizontalGlue())
    }

    init {
        add(header, BorderLayout.NORTH)
        add(diffPanel.component, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }

    /** Add a control to the footer bar, before the trailing glue. */
    fun addFooterComponent(component: JComponent) {
        footer.add(component, footer.componentCount - 1)
    }

    /** Clear the preview and restore the placeholder header — e.g. when nothing is selected. */
    fun showPlaceholder() {
        header.text = JujutsuBundle.message("dialog.preview.select")
        header.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        currentRequest = null
        WriteIntentReadAction.run { diffPanel.setRequest(null) }
    }

    /**
     * Show [request] under a header of [fileName]. Pass a null [request] to keep the header but
     * clear the diff (e.g. while content is loading).
     *
     * Building the request synchronously constructs real editors — see the [WriteIntentReadAction]
     * note at [in.kkkev.jjidea.diffedit.HunkPickerDialog]: harmless on a real EDT dispatch (which
     * already holds an implicit write-intent lock), but required so `@RunInEdt` test harnesses
     * (which don't provide that lock automatically) can drive this path too.
     */
    fun show(fileName: String, request: DiffRequest? = null) {
        header.text = fileName
        header.foreground = JBUI.CurrentTheme.Label.foreground()
        currentRequest = request
        WriteIntentReadAction.run { diffPanel.setRequest(request) }
    }

    @org.jetbrains.annotations.TestOnly
    internal fun headerTextForTest(): String = header.text

    @org.jetbrains.annotations.TestOnly
    internal fun hasRequestForTest(): Boolean = currentRequest != null
}
