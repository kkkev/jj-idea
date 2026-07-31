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
}
