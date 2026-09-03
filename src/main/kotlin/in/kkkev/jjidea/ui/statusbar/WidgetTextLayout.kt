package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.FragmentLayout
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas
import `in`.kkkev.jjidea.ui.components.RevisionChoice
import `in`.kkkev.jjidea.ui.components.append
import java.awt.Font
import java.awt.font.FontRenderContext

/**
 * Bounds the width of [JujutsuStatusBarWidget]'s rendered label (jj-idea-6nas, GitHub #95).
 *
 * [TextCanvasPanel.renderFrom][in.kkkev.jjidea.ui.components.TextCanvasPanel.renderFrom] lays
 * fragments out with a plain `BoxLayout` and never truncates them — that's [in.kkkev.jjidea.ui.log.LaidOutCell]
 * and [in.kkkev.jjidea.ui.components.TruncatingLeftRightLayout.configure]'s job, and the status bar
 * widget uses neither. [IdeStatusBarImpl][com.intellij.openapi.wm.impl.status.IdeStatusBarImpl] lays
 * its right panel out with `GridBagLayout`/`fill = VERTICAL`, which never shrinks a widget that
 * reports a large preferred width — so the widget must bound itself before rendering.
 */
internal object WidgetTextLayout {
    /** Absolute cap, unscaled px. */
    const val MAX_PX = 300

    /** Cap as a share of the whole status bar's width. */
    const val MAX_FRACTION = 0.25

    /**
     * budget = min(scaled [MAX_PX], [statusBarWidth] * [MAX_FRACTION]).
     *
     * A non-positive [statusBarWidth] (status bar not laid out yet) falls back to the fixed cap;
     * a resize listener re-truncates once a real width arrives.
     */
    fun budget(statusBarWidth: Int): Double {
        val fixed = JBUI.scale(MAX_PX).toDouble()
        if (statusBarWidth <= 0) return fixed
        return minOf(fixed, statusBarWidth * MAX_FRACTION)
    }

    /**
     * Fragments for [entry]'s [RevisionChoice.Change] rendering (icon + change id + description),
     * with the description shortened to fit [budget] — the only truncatable part, per
     * [in.kkkev.jjidea.ui.components.appendSummary].
     */
    fun fit(
        entry: LogEntry,
        budget: Double,
        font: Font,
        frc: FontRenderContext
    ): List<FragmentRecordingCanvas.Fragment> {
        val canvas = FragmentRecordingCanvas().apply { append(RevisionChoice.Change(entry)) }
        return FragmentLayout.truncateToFit(canvas.fragments, canvas.truncateRange, budget, font, frc)
    }
}
