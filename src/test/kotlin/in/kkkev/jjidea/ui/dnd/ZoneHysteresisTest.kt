package `in`.kkkev.jjidea.ui.dnd

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Schmitt-trigger behaviour for [ZoneHysteresis] (design section 4): once settled into a zone, the
 * pointer must cross [slack] pixels past the boundary before the zone switches. Driven by a
 * synthetic `(row, dy)` pointer trace, per the design's own recommendation for testing this without
 * a human at a mouse.
 */
class ZoneHysteresisTest {
    private val rowHeight = 100
    private val band = 10
    private val slack = 3
    private val hysteresis = ZoneHysteresis(slack)

    @Test
    fun `settles into the raw zone on the first sample for a row`() {
        hysteresis.update(row = 0, dy = 50, rowHeight, band) shouldBe DropZone.ONTO
    }

    @Test
    fun `sub-slack jitter straddling the ONTO-to-INSERT_BEFORE boundary does not flicker`() {
        hysteresis.update(row = 0, dy = 50, rowHeight, band) shouldBe DropZone.ONTO
        // Boundary is at dy = band (10). Jitter to dy = 9 (1px past, well under slack=3) - should hold ONTO.
        hysteresis.update(row = 0, dy = 9, rowHeight, band) shouldBe DropZone.ONTO
        hysteresis.update(row = 0, dy = 11, rowHeight, band) shouldBe DropZone.ONTO
        hysteresis.update(row = 0, dy = 9, rowHeight, band) shouldBe DropZone.ONTO
    }

    @Test
    fun `switches once the pointer crosses slack pixels past the boundary`() {
        hysteresis.update(row = 0, dy = 50, rowHeight, band) shouldBe DropZone.ONTO
        // Boundary at dy=10; need dy < band - slack = 7 to switch into INSERT_BEFORE.
        hysteresis.update(row = 0, dy = 8, rowHeight, band) shouldBe DropZone.ONTO
        hysteresis.update(row = 0, dy = 6, rowHeight, band) shouldBe DropZone.INSERT_BEFORE
    }

    @Test
    fun `switches back only once the pointer crosses slack pixels past the boundary the other way`() {
        hysteresis.update(row = 0, dy = 6, rowHeight, band) shouldBe DropZone.INSERT_BEFORE
        // Need dy >= band + slack = 13 to switch back to ONTO.
        hysteresis.update(row = 0, dy = 12, rowHeight, band) shouldBe DropZone.INSERT_BEFORE
        hysteresis.update(row = 0, dy = 13, rowHeight, band) shouldBe DropZone.ONTO
    }

    @Test
    fun `INSERT_AFTER boundary has the same slack behaviour, mirrored`() {
        // rowHeight - band = 90 is the raw boundary.
        hysteresis.update(row = 0, dy = 50, rowHeight, band) shouldBe DropZone.ONTO
        hysteresis.update(row = 0, dy = 91, rowHeight, band) shouldBe DropZone.ONTO
        hysteresis.update(row = 0, dy = 92, rowHeight, band) shouldBe DropZone.ONTO
        hysteresis.update(row = 0, dy = 93, rowHeight, band) shouldBe DropZone.INSERT_AFTER
    }

    @Test
    fun `changing row always takes the raw zone immediately, never carrying a stale zone across rows`() {
        hysteresis.update(row = 0, dy = 6, rowHeight, band) shouldBe DropZone.INSERT_BEFORE
        // Row 1's centre is ONTO - must not be held at INSERT_BEFORE just because row 0 last was.
        hysteresis.update(row = 1, dy = 50, rowHeight, band) shouldBe DropZone.ONTO
    }

    @Test
    fun `reset clears held state so the next update takes the raw zone`() {
        hysteresis.update(row = 0, dy = 6, rowHeight, band) shouldBe DropZone.INSERT_BEFORE
        hysteresis.reset()
        // Without the reset, a same-row sample at dy=12 (raw ONTO, but under the switch-back
        // threshold of band+slack=13) would still be held at INSERT_BEFORE by hysteresis - after
        // reset there is no held state to hold it, so it takes the raw zone directly.
        hysteresis.update(row = 0, dy = 12, rowHeight, band) shouldBe DropZone.ONTO
    }
}
