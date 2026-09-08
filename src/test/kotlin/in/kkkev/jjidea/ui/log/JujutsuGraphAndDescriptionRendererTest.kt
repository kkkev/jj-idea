package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Unit tests for [JujutsuGraphAndDescriptionRenderer.shouldDrawElidedStub] (jj-idea-2c8k round
 * 3): the decision of when a row should get a wiggly elided-parent stub instead of drawing
 * nothing (indistinguishable from a true root) or a real connector. The actual paint routine
 * (a [java.awt.geom.Path2D] wave) is not covered here - same limitation the design doc already
 * notes for this renderer's other paint code, since it needs a real [java.awt.Graphics2D].
 */
class JujutsuGraphAndDescriptionRendererTest {
    private fun node(parentLanes: List<Int>, hasElidedParents: Boolean) =
        GraphNode(lane = 0, color = Color.BLUE, parentLanes = parentLanes, hasElidedParents = hasElidedParents)

    @Test
    fun `all parents elided draws a stub`() {
        JujutsuGraphAndDescriptionRenderer.shouldDrawElidedStub(
            node(parentLanes = emptyList(), hasElidedParents = true)
        ) shouldBe true
    }

    @Test
    fun `true root draws no stub`() {
        JujutsuGraphAndDescriptionRenderer.shouldDrawElidedStub(
            node(parentLanes = emptyList(), hasElidedParents = false)
        ) shouldBe false
    }

    @Test
    fun `mixed merge with one loaded parent draws no stub - left to jj-idea-hlu3`() {
        JujutsuGraphAndDescriptionRenderer.shouldDrawElidedStub(
            node(parentLanes = listOf(0), hasElidedParents = true)
        ) shouldBe false
    }

    @Test
    fun `fully loaded merge draws no stub`() {
        JujutsuGraphAndDescriptionRenderer.shouldDrawElidedStub(
            node(parentLanes = listOf(0, 1), hasElidedParents = false)
        ) shouldBe false
    }
}
