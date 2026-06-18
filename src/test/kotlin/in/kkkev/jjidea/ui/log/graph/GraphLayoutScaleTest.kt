package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.ui.log.entry
import io.kotest.matchers.comparables.shouldBeLessThan
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for [LayoutCalculatorImpl] (the `graph-layout` hot path).
 *
 * A correct-but-quadratic regression in the per-row passthrough/lane bookkeeping would
 * produce identical [GraphLayout] output, so output assertions can't catch it — only a
 * work-count assertion can. [LayoutCalculatorImpl.operationCount] tracks that work; this
 * test asserts it stays linear (or near-linear) in entry count, never quadratic, using
 * synthetic in-memory graphs (no platform, no real repo). See contributing.md's
 * "Writing a scale test" section and [in.kkkev.jjidea.vcs.ignore.GitignoreScanTest] for
 * the pattern this generalizes.
 */
class GraphLayoutScaleTest {
    private val calculator = LayoutCalculatorImpl<String>()

    @Test
    fun `linear chain stays linear, not quadratic`() {
        val n = 20_000
        // e0 -> e1 -> e2 -> ... -> e(n-1): every parent is adjacent, so no passthroughs ever open.
        val entries = (0 until n).map { i ->
            entry("e$i", if (i + 1 < n) listOf("e${i + 1}") else emptyList())
        }

        calculator.calculate(entries)

        // A quadratic regression re-scanning all prior rows would be ~n²/2 (≈2*10^8 for n=20k),
        // far above any plausible linear bound. Linear work here is a small constant per row.
        calculator.operationCount shouldBeLessThan (5L * n)
    }

    @Test
    fun `wide DAG with bounded passthrough width stays O(n times width), not quadratic`() {
        val n = 20_000
        val width = 8
        // Each entry's single parent is `width` rows below (non-adjacent => opens a passthrough
        // that lives for `width` rows). At most `width` passthroughs are open at any time,
        // regardless of n, so total work should scale with n * width, not n^2.
        val entries = (0 until n).map { i ->
            entry("e$i", if (i + width < n) listOf("e${i + width}") else emptyList())
        }

        calculator.calculate(entries)

        // A quadratic regression (work growing with row index rather than open-passthrough count)
        // would be ~n²/2 (≈2*10^8 for n=20k) — far above this bound, which scales with n * width.
        calculator.operationCount shouldBeLessThan (20L * n * width)
    }
}
