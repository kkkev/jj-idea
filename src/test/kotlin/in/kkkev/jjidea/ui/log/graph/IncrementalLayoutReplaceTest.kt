package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.ui.log.entry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Equivalence tests for [IncrementalLayout.replace] (jj-idea-2570.14): whatever changed at the top of the
 * list, the result must be exactly what a from-scratch layout of the new list gives, and the engine must stay
 * a valid base for later appends (its shifted checkpoints are what a rewind restores).
 */
class IncrementalLayoutReplaceTest {
    private val longEdgeRows = 6

    private fun fresh(entries: List<GraphEntry<String>>) = LayoutCalculatorImpl<String>(longEdgeRows).calculate(entries)

    private fun engineWith(entries: List<GraphEntry<String>>) =
        IncrementalLayout<String>(longEdgeRows).also { it.append(entries) }

    private fun assertReplace(old: List<GraphEntry<String>>, new: List<GraphEntry<String>>) {
        engineWith(old).replace(new) shouldBe fresh(new)
    }

    private fun chain(n: Int, prefix: String = "e", skip: Int = 1) = (0 until n).map { i ->
        entry("$prefix$i", if (i + skip < n) listOf("$prefix${i + skip}") else emptyList())
    }

    /** Every 5th row also points 12 rows down: overlapping long edges at [longEdgeRows] = 6. */
    private fun longEdgeGraph(n: Int) = (0 until n).map { i ->
        entry(
            "e$i",
            buildList {
                if (i + 1 < n) add("e${i + 1}")
                if (i % 5 == 0 && i + 12 < n) add("e${i + 12}")
            }
        )
    }

    @Test
    fun `identical list is a no-op`() {
        val all = chain(40)
        assertReplace(all, all)
    }

    @Test
    fun `head prepended to a linear chain`() {
        val old = chain(600)
        assertReplace(old, listOf(entry("new", listOf("e0"))) + old)
    }

    @Test
    fun `head prepended to a wide fork-merge graph`() {
        val old = longEdgeGraph(700)
        assertReplace(old, listOf(entry("new", listOf("e0"))) + old)
        assertReplace(old, listOf(entry("new", listOf("e3"))) + old)
    }

    @Test
    fun `top row swapped for a new head`() {
        val old = longEdgeGraph(700)
        assertReplace(old, listOf(entry("other", listOf("e1"))) + old.drop(1))
    }

    @Test
    fun `merge head with a long edge to a deep parent waits for the cap`() {
        val old = longEdgeGraph(900)
        assertReplace(old, listOf(entry("merge", listOf("e0", "e600"))) + old)
        assertReplace(old, listOf(entry("a", listOf("merge")), entry("merge", listOf("e2", "e40"))) + old)
    }

    @Test
    fun `several rows prepended and top rows removed`() {
        val old = longEdgeGraph(800)
        val heads = (0 until 5).map { entry("h$it", listOf(if (it == 4) "e0" else "h${it + 1}")) }
        assertReplace(old, heads + old)
        assertReplace(old, old.drop(3))
        assertReplace(old.drop(3), old)
    }

    @Test
    fun `change in the middle replays through it`() {
        val old = longEdgeGraph(900)
        val new = old.toMutableList().also { it[400] = entry("e400", listOf("e401", "e700")) }
        assertReplace(old, new)
    }

    @Test
    fun `completely different list`() {
        assertReplace(chain(300), chain(250, prefix = "x"))
    }

    @Test
    fun `engine is a valid base for an append after a replace`() {
        // Page one leaves e0's far parent unresolved; the replace shifts every checkpoint by one row, then a
        // later page resolves the stub - the rewind must restore shifted checkpoint state.
        val all = longEdgeGraph(1500)
        val page1 = all.subList(0, 700)
        val page2 = all.subList(700, 1500)
        val head = entry("new", listOf("e0"))
        val engine = engineWith(page1)
        engine.replace(listOf(head) + page1)
        engine.append(page2) shouldBe fresh(listOf(head) + all)

        val removed = engineWith(page1)
        removed.replace(page1.drop(2))
        removed.append(page2) shouldBe fresh(page1.drop(2) + page2)
    }

    @Test
    fun `repeated replaces stay equivalent`() {
        var current = longEdgeGraph(600)
        val engine = engineWith(current)
        repeat(30) { n ->
            current = listOf(entry("h$n", listOf(current.first().current))) + current
            engine.replace(current) shouldBe fresh(current)
        }
    }

    @Test
    fun `randomized top mutations match a fresh layout`() {
        val random = Random(2570)
        repeat(60) { round ->
            val n = 300 + random.nextInt(500)
            val all = randomDag(random, n)
            val loaded = 200 + random.nextInt(n - 200)
            val page1 = all.subList(0, loaded)
            val engine = engineWith(page1)

            var current = page1
            repeat(4) { step ->
                val before = current
                current = mutateTop(random, current, "r$round-$step")
                val actual = engine.replace(current)
                val expected = fresh(current)
                val row = actual.rows.indices.firstOrNull { actual.rows[it] != expected.rows[it] }
                check(row == null) {
                    "round $round step $step (${before.size} -> ${current.size} rows): row $row\n" +
                        "actual   ${actual.rows[row!!]}\nexpected ${expected.rows[row]}\n" +
                        "old row  ${before.getOrNull(row)}\nnew row  ${current[row]}"
                }
            }
            val rest = all.subList(loaded, n)
            val actual = engine.append(rest)
            val expected = fresh(current + rest)
            val row = actual.rows.indices.firstOrNull { actual.rows[it] != expected.rows[it] }
            check(row == null) {
                "round $round append at ${current.size} (+${rest.size}): row $row\n" +
                    "actual   ${actual.rows[row!!]}\nexpected ${expected.rows[row]}"
            }
        }
    }

    /** Parents are always later rows (children first); some parents point past the end (unresolved). */
    private fun randomDag(random: Random, n: Int) = (0 until n).map { i ->
        val parents = buildList {
            if (random.nextInt(10) != 0 && i + 1 < n) add("e${i + 1}")
            if (random.nextInt(4) == 0) {
                val p = i + 2 + random.nextInt(if (random.nextBoolean()) 6 else 40)
                if (p < n && "e$p" !in this) add("e$p")
            }
        }
        entry("e$i", parents)
    }

    private fun mutateTop(random: Random, current: List<GraphEntry<String>>, tag: String): List<GraphEntry<String>> {
        val ids = current.map { it.current }
        return when (random.nextInt(4)) {
            0 -> listOf(entry("$tag-h", listOf(ids[random.nextInt(minOf(3, ids.size))]))) + current
            1 -> {
                val deep = ids[random.nextInt(ids.size)]
                listOf(entry("$tag-m", listOf(ids[0], deep).distinct())) + current
            }
            2 -> listOf(entry("$tag-s", listOf(ids[1]))) + current.drop(1)
            else -> current.drop(1 + random.nextInt(3))
        }
    }
}
