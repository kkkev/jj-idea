package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.log.graph.ParentState
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Unit tests for [JujutsuGraphAndDescriptionRenderer.stubStateToDraw] (jj-idea-2c8k round 3,
 * jj-idea-xi58/jj-idea-1pgy): the decision of when a row should get an unresolved-parent stub,
 * and which paint treatment (straight vs wiggle) it gets. The actual paint routine (a
 * [java.awt.geom.Path2D] wave / faded line) is not covered here - same limitation the design doc
 * already notes for this renderer's other paint code, since it needs a real [java.awt.Graphics2D].
 */
class JujutsuGraphAndDescriptionRendererTest {
    private val repo = mockk<JujutsuRepository>()
    private fun parentKey(name: String) = ChangeKey(repo, ChangeId(name))

    private fun node(parentLanes: List<Int>, unresolvedParents: Map<ChangeKey, ParentState>) =
        GraphNode(lane = 0, color = Color.BLUE, parentLanes = parentLanes, unresolvedParents = unresolvedParents)

    @Test
    fun `all parents elided and not loaded draws a straight stub`() {
        JujutsuGraphAndDescriptionRenderer.stubStateToDraw(
            node(parentLanes = emptyList(), unresolvedParents = mapOf(parentKey("a") to ParentState.NOT_LOADED))
        ) shouldBe ParentState.NOT_LOADED
    }

    @Test
    fun `all parents elided and hidden draws a wiggle`() {
        JujutsuGraphAndDescriptionRenderer.stubStateToDraw(
            node(parentLanes = emptyList(), unresolvedParents = mapOf(parentKey("a") to ParentState.HIDDEN))
        ) shouldBe ParentState.HIDDEN
    }

    @Test
    fun `true root draws no stub`() {
        JujutsuGraphAndDescriptionRenderer.stubStateToDraw(
            node(parentLanes = emptyList(), unresolvedParents = emptyMap())
        ) shouldBe null
    }

    @Test
    fun `mixed merge with one loaded parent still draws a stub`() {
        JujutsuGraphAndDescriptionRenderer.stubStateToDraw(
            node(parentLanes = listOf(0), unresolvedParents = mapOf(parentKey("a") to ParentState.NOT_LOADED))
        ) shouldBe ParentState.NOT_LOADED
    }

    @Test
    fun `fully loaded merge draws no stub`() {
        JujutsuGraphAndDescriptionRenderer.stubStateToDraw(
            node(parentLanes = listOf(0, 1), unresolvedParents = emptyMap())
        ) shouldBe null
    }

    @Test
    fun `mixed states prefer NOT_LOADED since it is the actionable one`() {
        JujutsuGraphAndDescriptionRenderer.stubStateToDraw(
            node(
                parentLanes = listOf(0),
                unresolvedParents = mapOf(
                    parentKey("a") to ParentState.HIDDEN,
                    parentKey("b") to ParentState.NOT_LOADED
                )
            )
        ) shouldBe ParentState.NOT_LOADED
    }
}
