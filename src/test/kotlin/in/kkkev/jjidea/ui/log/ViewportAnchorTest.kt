package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ViewportAnchors]' pure capture/restore arithmetic (jj-idea-wrza). Kept separate
 * from [JujutsuLogTableScrollPreservationTest] (which exercises the same mechanism through a real
 * Swing table) so the arithmetic itself doesn't need a platform test.
 */
class ViewportAnchorTest {
    private val repo = mockk<JujutsuRepository>()
    private val key = ChangeKey(repo, ChangeId("abc123", "abc123", null))

    @Test
    fun `capture returns null when the viewport is pinned to the top`() {
        ViewportAnchors.capture(key, viewY = 0, topRowY = 0) shouldBe null
    }

    @Test
    fun `capture returns null when there is no row at the viewport top`() {
        ViewportAnchors.capture(null, viewY = 40, topRowY = 20) shouldBe null
    }

    @Test
    fun `capture records how far the top row is scrolled off-screen`() {
        // Row 2 starts at y=40; the viewport's visible top is at y=55, i.e. 15px into that row.
        ViewportAnchors.capture(key, viewY = 55, topRowY = 40) shouldBe ViewportAnchor(key, 15)
    }

    @Test
    fun `restoredY shifts by exactly the anchor row's new offset`() {
        val anchor = ViewportAnchor(key, pixelsScrolledOff = 15)
        // The anchor row moved from y=40 to y=60 (one row height lower, e.g. a prepend).
        ViewportAnchors.restoredY(anchor, newRowY = 60) shouldBe 75
    }

    @Test
    fun `restoredY clamps to zero rather than going negative`() {
        // Defensive: capture() never produces a negative offset in practice (the anchor row's
        // top is always at or above the viewport's visible top), but restoredY must still not
        // scroll past the top of the content if it somehow were.
        val anchor = ViewportAnchor(key, pixelsScrolledOff = -50)
        ViewportAnchors.restoredY(anchor, newRowY = 0) shouldBe 0
    }
}
