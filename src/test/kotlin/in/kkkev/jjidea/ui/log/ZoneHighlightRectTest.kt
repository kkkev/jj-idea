package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.ui.dnd.DropZone
import `in`.kkkev.jjidea.ui.dnd.DropZones
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.Rectangle

/**
 * Regression coverage for [zoneHighlightRect]: manual testing of jj-idea-6jvh found the original
 * indicator - a thin line at the shared boundary between two rows - indistinguishable between
 * "the bottom band of row N" and "the top band of row N+1", which matters here (unlike
 * `RowsDnDSupport`'s list-reorder use) because those are two different rebase destinations at a
 * fork or merge (design section 2). [zoneHighlightRect] fixes this by filling the owning row's
 * own edge band instead of a shared boundary line.
 */
class ZoneHighlightRectTest {
    private val row0 = Rectangle(0, 0, 400, 22)
    private val row1 = Rectangle(0, 22, 400, 22)

    @Test
    fun `ONTO highlights the whole row`() {
        zoneHighlightRect(row0, DropZone.ONTO) shouldBe row0
    }

    @Test
    fun `INSERT_BEFORE highlights a band at the top of the row, inside its own bounds`() {
        val rect = zoneHighlightRect(row1, DropZone.INSERT_BEFORE)

        rect.y shouldBe row1.y
        rect.height shouldBe DropZones.bandFor(row1.height)
        (rect.y + rect.height) shouldBe (row1.y + DropZones.bandFor(row1.height))
    }

    @Test
    fun `INSERT_AFTER highlights a band at the bottom of the row, inside its own bounds`() {
        val rect = zoneHighlightRect(row0, DropZone.INSERT_AFTER)

        (rect.y + rect.height) shouldBe (row0.y + row0.height)
        rect.height shouldBe DropZones.bandFor(row0.height)
    }

    @Test
    fun `row0's after band and row1's before band don't overlap - opposite sides of the shared boundary`() {
        val afterRow0 = zoneHighlightRect(row0, DropZone.INSERT_AFTER)
        val beforeRow1 = zoneHighlightRect(row1, DropZone.INSERT_BEFORE)

        // The old thin-line indicator painted both cases at the exact same y (the shared
        // boundary, row0.y + row0.height == row1.y) - indistinguishable at a glance. Each band
        // now lies entirely on its own row's side of that boundary and they don't overlap.
        (afterRow0.y + afterRow0.height) shouldBe (row0.y + row0.height)
        beforeRow1.y shouldBe row1.y
        afterRow0.intersects(beforeRow1) shouldBe false
    }

    @Test
    fun `the ONTO rectangle is always strictly taller than either insert band`() {
        val onto = zoneHighlightRect(row0, DropZone.ONTO)
        val before = zoneHighlightRect(row0, DropZone.INSERT_BEFORE)

        onto.height shouldBeGreaterThan before.height
    }
}
