package `in`.kkkev.jjidea.ui.editor.conflict

import `in`.kkkev.jjidea.jj.conflict.ConflictMarkerFixtures
import `in`.kkkev.jjidea.jj.conflict.JjConflictBlockParser
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * [ConflictBlockGutterIconRenderer.equals]/[hashCode] (jj-idea-82fo, stage 3/4) - see that
 * class's KDoc for why the platform's own doc calls these "highly advisable".
 */
class ConflictBlockGutterIconRendererTest {
    @Test
    fun `two renderers for the same block are equal`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        ConflictBlockGutterIconRenderer(block) shouldBe ConflictBlockGutterIconRenderer(block)
        ConflictBlockGutterIconRenderer(block).hashCode() shouldBe ConflictBlockGutterIconRenderer(block).hashCode()
    }

    @Test
    fun `renderers for different blocks are not equal`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.multiBlock)
        blocks.size shouldBe 2

        ConflictBlockGutterIconRenderer(blocks[0]) shouldNotBe ConflictBlockGutterIconRenderer(blocks[1])
    }

    @Test
    fun `tooltip mentions both sides' labels`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        val tooltip = ConflictBlockGutterIconRenderer(block).tooltipText

        tooltip.shouldNotBe(null)
        (tooltip ?: "").let {
            it.contains("side A") shouldBe true
            it.contains("side B") shouldBe true
        }
    }
}
