package `in`.kkkev.jjidea.ui.log.graph

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ElidedAncestorsTest {
    /** Builds the remap over [dag] (child -> parents, listed newest first) with [hidden] filtered out. */
    private fun remap(dag: Map<String, List<String>>, hidden: Set<String>): ElidedAncestors<String> =
        ElidedAncestors(dag.keys.filter { it !in hidden }, dag::get) { it !in hidden && it in dag }

    @Test
    fun `nothing hidden means no remap`() {
        val r = remap(linkedMapOf("c" to listOf("b"), "b" to listOf("a"), "a" to emptyList()), emptySet())

        r.effectiveParents.shouldBeEmpty()
        r.elided.shouldBeEmpty()
    }

    @Test
    fun `linear chain with a hidden middle links to the nearest visible ancestor`() {
        val r = remap(linkedMapOf("c" to listOf("b"), "b" to listOf("a"), "a" to emptyList()), setOf("b"))

        r.effectiveParents["c"]!!.shouldContainExactly("a")
        r.elided["c"]!!.shouldContainExactly("a")
    }

    @Test
    fun `several consecutive hidden commits are skipped`() {
        val r = remap(
            linkedMapOf(
                "e" to listOf("d"),
                "d" to listOf("c"),
                "c" to listOf("b"),
                "b" to listOf("a"),
                "a" to emptyList()
            ),
            setOf("d", "c", "b")
        )

        r.effectiveParents["e"]!!.shouldContainExactly("a")
    }

    @Test
    fun `diamond with one hidden side keeps the direct edge solid and bridges the hidden one`() {
        // m merges x (hidden) and y; both descend from base.
        val r = remap(
            linkedMapOf(
                "m" to listOf("x", "y"),
                "x" to listOf("base"),
                "y" to listOf("base"),
                "base" to emptyList()
            ),
            setOf("x")
        )

        r.effectiveParents["m"]!!.shouldContainExactly("base", "y")
        r.elided["m"]!!.shouldContainExactly("base")
    }

    @Test
    fun `an ancestor reached both directly and through a hidden node stays solid`() {
        val r = remap(
            linkedMapOf("m" to listOf("a", "h"), "h" to listOf("a"), "a" to emptyList()),
            setOf("h")
        )

        r.effectiveParents["m"]!!.shouldContainExactly("a")
        r.elided.shouldBeEmpty()
    }

    @Test
    fun `a hidden merge bridges to both visible ancestors in parent order`() {
        val r = remap(
            linkedMapOf("c" to listOf("h"), "h" to listOf("p1", "p2"), "p1" to emptyList(), "p2" to emptyList()),
            setOf("h")
        )

        r.effectiveParents["c"]!!.shouldContainExactly("p1", "p2")
        r.elided["c"]!!.shouldContainExactly("p1", "p2")
    }

    @Test
    fun `a hidden parent with no visible ancestor is kept so it renders as a stub`() {
        val r = remap(linkedMapOf("c" to listOf("b"), "b" to listOf("a"), "a" to emptyList()), setOf("b", "a"))

        r.effectiveParents["c"]!!.shouldContainExactly("b")
        r.elided.shouldBeEmpty()
    }

    @Test
    fun `a hidden chain that runs into an unloaded parent keeps the hidden parent`() {
        // b's parent "gone" isn't in the loaded set at all.
        val r = remap(linkedMapOf("c" to listOf("b"), "b" to listOf("gone")), setOf("b"))

        r.effectiveParents["c"]!!.shouldContainExactly("b")
        r.elided.shouldBeEmpty()
    }

    @Test
    fun `result does not depend on the order the hidden nodes are listed in`() {
        // Parents listed before their children, as on-demand rows merged in can be.
        val r = remap(linkedMapOf("a" to emptyList(), "b" to listOf("a"), "c" to listOf("b")), setOf("b"))

        r.effectiveParents["c"]!!.shouldContainExactly("a")
    }

    @Test
    fun `visits stay linear on a long history with every second commit hidden`() {
        val n = 10_000
        val dag = LinkedHashMap<String, List<String>>()
        for (i in n downTo 1) dag["c$i"] = if (i > 1) listOf("c${i - 1}") else emptyList()
        val hidden = (1..n).filter { it % 2 == 0 }.mapTo(HashSet()) { "c$it" }

        val r = remap(dag, hidden)

        (r.operationCount) shouldBeLessThanOrEqual (6L * n)
        r.effectiveParents["c${n - 1}"]!!.shouldContainExactly("c${n - 3}")
    }

    @Test
    fun `a very long fully hidden chain neither overflows the stack nor is walked twice`() {
        val n = 50_000
        val dag = LinkedHashMap<String, List<String>>()
        dag["head"] = listOf("h$n")
        for (i in n downTo 1) dag["h$i"] = if (i > 1) listOf("h${i - 1}") else listOf("root")
        dag["root"] = emptyList()
        val hidden = (1..n).mapTo(HashSet()) { "h$it" }

        val r = remap(dag, hidden)

        r.effectiveParents["head"]!!.shouldContainExactly("root")
        r.operationCount shouldBeLessThanOrEqual (6L * n)
        r.elided["head"]!!.size shouldBe 1
    }
}
