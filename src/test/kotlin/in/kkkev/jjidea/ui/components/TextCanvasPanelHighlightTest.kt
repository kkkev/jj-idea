package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleColoredComponent
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import javax.swing.JLabel

/**
 * Tests for [TextCanvasPanel.renderFrom]'s per-[FragmentRecordingCanvas.Fragment.linkTarget]
 * grouping (jj-idea-a52h): [TextCanvasPanel.highlightTarget] paints a background behind exactly
 * the child components sharing one target, so a chip's hover-highlight must not bleed into a
 * neighboring separator or another chip's components.
 */
class TextCanvasPanelHighlightTest {
    private fun targetOf(component: java.awt.Component): Any? =
        (component as? javax.swing.JComponent)?.getClientProperty("jjLinkTarget")

    @Test
    fun `adjacent chips with different link targets render as separate component groups`() {
        val targetA = URI("jjref://repo?a&kind=bookmark&name=main")
        val targetB = URI("jjref://repo?a&kind=bookmark&name=other")
        val canvas = FragmentRecordingCanvas().apply {
            linked(targetA) { appendChip(icon(JujutsuIcons::Bookmark), "main") }
            append(" ")
            linked(targetB) { appendChip(icon(JujutsuIcons::Bookmark), "other") }
        }

        val panel = TextCanvasPanel()
        panel.renderFrom(canvas)

        // icon(A), label(A), separator(null target), icon(B), label(B).
        panel.components shouldHaveSize 5
        val targets = panel.components.map { targetOf(it) }
        targets shouldBe listOf(targetA, targetA, null, targetB, targetB)
    }

    @Test
    fun `a plain separator between chips does not inherit either chip's link target`() {
        val target = URI("jjref://repo?a&kind=bookmark&name=main")
        val canvas = FragmentRecordingCanvas().apply {
            linked(target) { appendChip(icon(JujutsuIcons::Bookmark), "main") }
            append(" ")
            append("plain trailing text")
        }

        val panel = TextCanvasPanel()
        panel.renderFrom(canvas)

        // icon(target), label(target), then the separator+trailing text merge into one
        // no-target SCC (both are plain, untargeted text fragments).
        panel.components shouldHaveSize 3
        targetOf(panel.components[0]) shouldBe target
        targetOf(panel.components[1]) shouldBe target
        targetOf(panel.components[2]) shouldBe null
    }

    @Test
    fun `highlightTarget only matches components carrying that exact link target`() {
        val targetA = URI("jjref://repo?a&kind=bookmark&name=main")
        val targetB = URI("jjref://repo?a&kind=bookmark&name=other")
        val canvas = FragmentRecordingCanvas().apply {
            linked(targetA) { appendChip(icon(JujutsuIcons::Bookmark), "main") }
            append(" ")
            linked(targetB) { appendChip(icon(JujutsuIcons::Bookmark), "other") }
        }

        val panel = TextCanvasPanel()
        panel.renderFrom(canvas)
        panel.highlightTarget = targetA

        val matching = panel.components.filter { targetOf(it) == panel.highlightTarget }
        matching shouldHaveSize 2
        matching.all { it is JLabel || it is SimpleColoredComponent } shouldBe true
    }

    @Test
    fun `each SCC created on a link-target boundary has no ipad`() {
        // Regression test: a mid-sentence link (e.g. a description's issue-tracker reference)
        // splits the row into several adjacent SimpleColoredComponents at each linkTarget change.
        // SimpleColoredComponent's default ipad (JBInsets.create(1, 2)) would stack into visible
        // gaps between them that FragmentLayout.fragmentWidth's pure-text-width measurement never
        // budgeted for - every SCC this panel creates must have it zeroed out.
        val target = URI("https://tracker/JIRA-123")
        val canvas = FragmentRecordingCanvas().apply {
            append("Fixes ")
            linked(target) { append("JIRA-123") }
            append(" now")
        }

        val panel = TextCanvasPanel()
        panel.renderFrom(canvas)

        val sccs = panel.components.filterIsInstance<SimpleColoredComponent>()
        sccs shouldHaveSize 3
        sccs.forEach { it.ipad shouldBe java.awt.Insets(0, 0, 0, 0) }
    }

    @Test
    fun `adjacent SCCs' total preferred width matches pure text width, with no border padding either`() {
        // Regression test: SimpleColoredComponent.computePreferredSize also counts a separate
        // `border` field (default JBUI.Borders.empty(1)) alongside ipad - zeroing only ipad (the
        // previous regression test above) left a residual 2px-per-SCC gap from this border, visible
        // as e.g. "release ( #50713 )" instead of "release (#50713)" once a description's issue-link
        // split the row into three adjacent SCCs.
        val target = URI("https://tracker/JIRA-123")
        val canvas = FragmentRecordingCanvas().apply {
            append("release (")
            linked(target) { append("JIRA-123") }
            append(")")
        }

        val panel = TextCanvasPanel()
        panel.renderFrom(canvas)

        val fm = panel.getFontMetrics(panel.font)
        val pureWidth = fm.stringWidth("release (") + fm.stringWidth("JIRA-123") + fm.stringWidth(")")
        val actualWidth = panel.components.sumOf { it.preferredSize.width }
        actualWidth shouldBe pureWidth
    }
}
