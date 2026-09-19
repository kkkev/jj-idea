package `in`.kkkev.jjidea.ui.editor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Operation-count test for [DebouncedDocumentScan] (contributing.md § Performance & Scale: any
 * refresh-path debounce needs an operation-count test, not a wall-clock one). [scheduleScan] and
 * [cancelPending] fake a single-slot "alarm": [cancelPending] drops any pending action,
 * [scheduleScan] records the latest one - so calling the recorded action simulates "the debounce
 * window elapsed" without any real threading or delay.
 */
class DebouncedDocumentScanTest {
    private var pending: (() -> Unit)? = null
    private var scanCount = 0

    private val scan = DebouncedDocumentScan(
        delayMs = 300,
        cancelPending = { pending = null },
        scheduleScan = { _, action -> pending = action },
        scan = { scanCount++ }
    )

    private fun fireWindow() {
        pending?.invoke()
        pending = null
    }

    @Test
    fun `one document change - one scan once its window elapses`() {
        scan.onDocumentChanged()
        fireWindow()

        scanCount shouldBe 1
    }

    @Test
    fun `a burst of changes within one window - exactly one scan, not one per change`() {
        repeat(5) { scan.onDocumentChanged() }
        fireWindow()

        scanCount shouldBe 1
    }

    @Test
    fun `changes spanning two windows - one scan per window`() {
        scan.onDocumentChanged()
        fireWindow()

        scan.onDocumentChanged()
        fireWindow()

        scanCount shouldBe 2
    }

    @Test
    fun `no document changes - no scan`() {
        scanCount shouldBe 0
    }

    @Test
    fun `each document change cancels the previously scheduled one`() {
        var cancelCount = 0
        val counting = DebouncedDocumentScan(
            cancelPending = { cancelCount++ },
            scheduleScan = { _, _ -> },
            scan = {}
        )

        repeat(3) { counting.onDocumentChanged() }

        cancelCount shouldBe 3
    }
}
