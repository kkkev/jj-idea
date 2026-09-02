package `in`.kkkev.jjidea.ui.dnd

import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Boundary math for [DropZones] (`docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 2):
 * top/bottom [DropZone.INSERT_BEFORE]/[DropZone.INSERT_AFTER] bands, centre [DropZone.ONTO].
 */
class DropZonesTest {
    private val rowHeight = 22
    private val band = DropZones.bandFor(rowHeight)

    @Test
    fun `band is the design's worked example - clamped scaled 6px at the 22px default row height`() {
        // JBUI.scale is 1x unscaled in a headless unit test, so this is exactly min(6, 22/4) = 5.
        band shouldBe 5
    }

    @Test
    fun `dy 0 (row top) is INSERT_BEFORE`() {
        DropZones.zoneAt(dy = 0, rowHeight, band) shouldBe DropZone.INSERT_BEFORE
    }

    @Test
    fun `dy just inside the top band is INSERT_BEFORE`() {
        DropZones.zoneAt(dy = band - 1, rowHeight, band) shouldBe DropZone.INSERT_BEFORE
    }

    @Test
    fun `dy at the top band boundary is ONTO`() {
        DropZones.zoneAt(dy = band, rowHeight, band) shouldBe DropZone.ONTO
    }

    @Test
    fun `dy at the row centre is ONTO`() {
        DropZones.zoneAt(dy = rowHeight / 2, rowHeight, band) shouldBe DropZone.ONTO
    }

    @Test
    fun `dy just inside the bottom band boundary is ONTO`() {
        DropZones.zoneAt(dy = rowHeight - band - 1, rowHeight, band) shouldBe DropZone.ONTO
    }

    @Test
    fun `dy at the bottom band boundary is INSERT_AFTER`() {
        DropZones.zoneAt(dy = rowHeight - band, rowHeight, band) shouldBe DropZone.INSERT_AFTER
    }

    @Test
    fun `dy at the last pixel of the row is INSERT_AFTER`() {
        DropZones.zoneAt(dy = rowHeight - 1, rowHeight, band) shouldBe DropZone.INSERT_AFTER
    }

    @Test
    fun `the ONTO centre never drops below half the row, across a range of row heights`() {
        for (h in 8..64) {
            val b = DropZones.bandFor(h)
            val ontoHeight = h - 2 * b
            ontoHeight shouldBeGreaterThanOrEqualTo (h / 2)
        }
    }
}
