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
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * [ConflictBlockGutterIconRenderer.equals]/[hashCode] and its menu (jj-idea-82fo follow-up:
 * per-side icons) - see that class's KDoc for why equals/hashCode are implemented at all despite
 * the dispose-and-recreate pattern that makes them largely inert today.
 */
class ConflictBlockGutterIconRendererTest {
    private val project = mockk<Project>()
    private val document = mockk<Document>()

    private fun renderer(block: ConflictBlock, choice: AcceptChoice = AcceptChoice.SIDE1) =
        ConflictBlockGutterIconRenderer(project, document, block, choice)

    @Test
    fun `two renderers for the same block and side are equal`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        renderer(block, AcceptChoice.SIDE1) shouldBe renderer(block, AcceptChoice.SIDE1)
        renderer(block, AcceptChoice.SIDE1).hashCode() shouldBe renderer(block, AcceptChoice.SIDE1).hashCode()
    }

    @Test
    fun `renderers for the same block but different sides are not equal`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        renderer(block, AcceptChoice.SIDE1) shouldNotBe renderer(block, AcceptChoice.SIDE2)
    }

    @Test
    fun `renderers for different blocks are not equal`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.multiBlock)
        blocks.size shouldBe 2

        renderer(blocks[0]) shouldNotBe renderer(blocks[1])
    }

    @Test
    fun `tooltip names this icon's own side`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        renderer(block, AcceptChoice.SIDE1).tooltipText.contains("side A") shouldBe true
        renderer(block, AcceptChoice.SIDE2).tooltipText.contains("side B") shouldBe true
    }

    @Test
    fun `left-click shows a one-item confirmation popup for this icon's own side, not an instant accept`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        val side1Click = renderer(block, AcceptChoice.SIDE1).clickAction
        val side2Click = renderer(block, AcceptChoice.SIDE2).clickAction

        side1Click.shouldBeInstanceOf<ConflictAcceptConfirmAction>()
        side1Click.templateText?.contains("side A") shouldBe true
        side2Click.templateText?.contains("side B") shouldBe true
    }

    @Test
    fun `popup menu still offers every choicesFor entry, regardless of which side this icon is`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        val actions = (
            renderer(
                block,
                AcceptChoice.SIDE2
            ).popupMenuActions as DefaultActionGroup
        ).getChildActionsOrStubs()

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
