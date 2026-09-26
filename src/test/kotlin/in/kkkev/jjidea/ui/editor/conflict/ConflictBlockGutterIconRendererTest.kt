package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.conflict.AcceptChoice
import `in`.kkkev.jjidea.jj.conflict.ConflictBlock
import `in`.kkkev.jjidea.jj.conflict.ConflictMarkerFixtures
import `in`.kkkev.jjidea.jj.conflict.JjConflictBlockParser
import `in`.kkkev.jjidea.jj.conflict.choicesFor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * [ConflictBlockGutterIconRenderer.equals]/[hashCode] and its menu (jj-idea-82fo, stage 4/4) -
 * see that class's KDoc for why equals/hashCode are implemented at all despite the
 * dispose-and-recreate pattern that makes them largely inert today.
 */
class ConflictBlockGutterIconRendererTest {
    private val project = mockk<Project>()
    private val document = mockk<Document>()

    private fun renderer(block: ConflictBlock) = ConflictBlockGutterIconRenderer(project, document, block)

    @Test
    fun `two renderers for the same block are equal`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        renderer(block) shouldBe renderer(block)
        renderer(block).hashCode() shouldBe renderer(block).hashCode()
    }

    @Test
    fun `renderers for different blocks are not equal`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.multiBlock)
        blocks.size shouldBe 2

        renderer(blocks[0]) shouldNotBe renderer(blocks[1])
    }

    @Test
    fun `tooltip mentions both sides' labels`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        val tooltip = renderer(block).tooltipText

        tooltip.contains("side A") shouldBe true
        tooltip.contains("side B") shouldBe true
    }

    @Test
    fun `popup menu offers exactly one action per choicesFor this block`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        val actions = (renderer(block).popupMenuActions as DefaultActionGroup).getChildActionsOrStubs()

        actions.size shouldBe choicesFor(block).size
    }

    @Test
    fun `a base-less block's popup has no base action`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.diffDestinationFirst).single()
        choicesFor(block).contains(AcceptChoice.BASE) shouldBe false

        val actions = (renderer(block).popupMenuActions as DefaultActionGroup).getChildActionsOrStubs()

        actions.size shouldBe 3 // SIDE1, SIDE2, BOTH only
    }
}
