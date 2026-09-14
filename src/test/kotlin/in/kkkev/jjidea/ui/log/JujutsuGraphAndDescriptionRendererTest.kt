package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.log.graph.ParentState
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests for [stubTargetFor] (jj-idea-2c8k round 3, jj-idea-xi58/jj-idea-1pgy/jj-idea-sc8m):
 * the decision of when a row should get an unresolved-parent stub, which parent it names, and
 * which state (straight NOT_LOADED vs wiggle HIDDEN) wins when mixed - [GraphEdgeIndex] uses the
 * parent key alongside the state to record the stub as a navigable edge. The actual paint routine
 * (a [java.awt.geom.Path2D] wave / faded line, [JujutsuGraphAndDescriptionRenderer]) is not
 * covered here - same limitation the design doc already notes for this renderer's other paint
 * code, since it needs a real [java.awt.Graphics2D].
 */
class JujutsuGraphAndDescriptionRendererTest {
    private val repo = mockk<JujutsuRepository>()
    private fun parentKey(name: String) = ChangeKey(repo, ChangeId(name))

    private fun node(parentLanes: List<Int>, unresolvedParents: Map<ChangeKey, ParentState>) =
        GraphNode(lane = 0, parentLanes = parentLanes, unresolvedParents = unresolvedParents)

    @Test
    fun `true root has no stub target`() {
        stubTargetFor(node(parentLanes = emptyList(), unresolvedParents = emptyMap())) shouldBe null
    }

    @Test
    fun `all parents elided and not loaded targets that parent as NOT_LOADED`() {
        stubTargetFor(
            node(parentLanes = emptyList(), unresolvedParents = mapOf(parentKey("a") to ParentState.NOT_LOADED))
        ) shouldBe (parentKey("a") to ParentState.NOT_LOADED)
    }

    @Test
    fun `all parents elided and hidden targets that parent as HIDDEN`() {
        stubTargetFor(
            node(parentLanes = emptyList(), unresolvedParents = mapOf(parentKey("a") to ParentState.HIDDEN))
        ) shouldBe (parentKey("a") to ParentState.HIDDEN)
    }

    @Test
    fun `mixed merge with one loaded parent still targets the unresolved one`() {
        stubTargetFor(
            node(parentLanes = listOf(0), unresolvedParents = mapOf(parentKey("a") to ParentState.NOT_LOADED))
        ) shouldBe (parentKey("a") to ParentState.NOT_LOADED)
    }

    @Test
    fun `fully loaded merge has no stub target`() {
        stubTargetFor(node(parentLanes = listOf(0, 1), unresolvedParents = emptyMap())) shouldBe null
    }

    @Test
    fun `mixed states prefer NOT_LOADED since it is the actionable one`() {
        stubTargetFor(
            node(
                parentLanes = listOf(0),
                unresolvedParents = mapOf(
                    parentKey("a") to ParentState.HIDDEN,
                    parentKey("b") to ParentState.NOT_LOADED
                )
            )
        ) shouldBe (parentKey("b") to ParentState.NOT_LOADED)
    }
}
