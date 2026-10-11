package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.ui.log.entry
import io.kotest.matchers.comparables.shouldBeLessThan
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for [IncrementalLayout.append] (jj-idea-jnqi) - the graph-layout
 * half of the fix. Companion to [GraphLayoutScaleTest] (which pins a single from-scratch
 * `calculate()` as linear); this pins N *sequential* page-shaped appends, matching the bead's
 * explicit ask: "a scale test asserting buildGraph/topologicalSort's total operationCount
 * across N sequential loadMore() calls stays O(total rows), not O(total rows * N)."
 *
 * Before this fix, `mergeAndNotify()` called `LayoutCalculatorImpl.calculate()` from scratch on
 * every page - total work across N pages of size `pageSize` is ~sum(pageSize, 2*pageSize, ...,
 * N*pageSize) = O(N^2 * pageSize). [IncrementalLayout.append] instead extends the previous
 * layout, so the same scroll should cost O(N * pageSize) = O(total rows) in total.
 */
class IncrementalLayoutScaleTest {
    @Test
    fun `N sequential page appends to a linear chain stay O(total rows), not O(total rows squared)`() {
        val pageSize = 1_000
        val pageCount = 100
        val totalRows = pageSize * pageCount

        // e0 -> e1 -> ... -> e(totalRows-1): every parent adjacent, split into pageCount
        // pages in load order (newest/child-most first, oldest/parent-most last) - the
        // shape `loadMore()` produces paging back through history.
        val all = (0 until totalRows).map { i ->
            entry("e$i", if (i + 1 < totalRows) listOf("e${i + 1}") else emptyList())
        }
        val pages = all.chunked(pageSize)

        val engine = IncrementalLayout<String>()
        var totalOperations = 0L
        for (page in pages) {
            engine.append(page)
            totalOperations += engine.operationCount
        }

        // A from-scratch recompute per page would be ~pageSize * pageCount * (pageCount+1) / 2
        // (≈505M work-units at these sizes) - two orders of magnitude above any plausible
        // linear bound. Linear total work here is a small constant per row.
        totalOperations shouldBeLessThan (5L * totalRows)
    }

    @Test
    fun `N sequential page appends to a wide DAG stay O(total rows times width), not quadratic in page count`() {
        val pageSize = 1_000
        val pageCount = 50
        val width = 8
        val totalRows = pageSize * pageCount

        val all = (0 until totalRows).map { i ->
            entry("e$i", if (i + width < totalRows) listOf("e${i + width}") else emptyList())
        }
        val pages = all.chunked(pageSize)

        val engine = IncrementalLayout<String>()
        var totalOperations = 0L
        for (page in pages) {
            engine.append(page)
            totalOperations += engine.operationCount
        }

        totalOperations shouldBeLessThan (20L * totalRows * width)
    }

    @Test
    fun `appending is proportional to page size regardless of how many rows already loaded`() {
        // Direct pin on the O(N^2) shape itself: the operation count of the LAST append in a
        // long scroll must stay close to a single page's own work, not grow with how many
        // pages preceded it.
        val pageSize = 500
        val pageCount = 80
        val totalRows = pageSize * pageCount
        val all = (0 until totalRows).map { i ->
            entry("e$i", if (i + 1 < totalRows) listOf("e${i + 1}") else emptyList())
        }
        val pages = all.chunked(pageSize)

        val engine = IncrementalLayout<String>()
        var lastPageOperations = 0L
        for (page in pages) {
            engine.append(page)
            lastPageOperations = engine.operationCount
        }

        // A from-scratch-per-page implementation would have its last append cost ~totalRows
        // (the whole accumulated set); a true append-only fast path costs ~pageSize.
        lastPageOperations shouldBeLessThan (10L * pageSize)
    }

    // jj-idea-2570.14: a post-write refresh prepends a row (jj new); the layout work must not grow with the
    // number of rows already loaded.
    @Test
    fun `replace after prepending a head costs the same at 5k and 20k rows and far less than a full layout`() {
        val width = 8
        fun graph(total: Int) = (0 until total).map { i ->
            entry("e$i", if (i + width < total) listOf("e${i + width}") else emptyList())
        }

        fun replaceOperations(total: Int): Pair<Long, Long> {
            val old = graph(total)
            val engine = IncrementalLayout<String>()
            engine.append(old)
            val fullOperations = engine.operationCount
            engine.replace(listOf(entry("new", listOf("e0"))) + old)
            return engine.operationCount to fullOperations
        }

        val (small, smallFull) = replaceOperations(5_000)
        val (large, largeFull) = replaceOperations(20_000)

        large shouldBeLessThan (2L * small + 1_000)
        large shouldBeLessThan (largeFull / 20)
        small shouldBeLessThan (smallFull / 5)
    }

    @Test
    fun `replace after dropping the top row is independent of loaded rows`() {
        fun operations(total: Int): Long {
            val old = (0 until total).map { i -> entry("e$i", if (i + 1 < total) listOf("e${i + 1}") else emptyList()) }
            val engine = IncrementalLayout<String>()
            engine.append(old)
            engine.replace(old.drop(1))
            return engine.operationCount
        }
        operations(20_000) shouldBeLessThan (2L * operations(5_000) + 1_000)
    }
}
