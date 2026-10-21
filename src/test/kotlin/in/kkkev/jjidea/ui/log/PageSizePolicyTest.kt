package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageSizePolicyTest {
    private fun next(rows: Int, ms: Long, floor: Int = 500, ceiling: Int = 2000) =
        PageSizePolicy.next(rows, ms, floor, ceiling)

    @Test
    fun `a fetch on target keeps the size`() = next(500, PageSizePolicy.TARGET_MS * 500 / 500) shouldBe 500

    @Test
    fun `a fast fetch grows the page but at most double`() {
        next(500, 100) shouldBe 1000 // would scale to 3500
        next(500, 500) shouldBe 700 // scales to 700, under the double
    }

    @Test
    fun `a slow fetch shrinks the page, never below the first-page size`() {
        next(1000, 2000) shouldBe 500 // scales to 350
        next(2000, 1400) shouldBe 1000
    }

    @Test
    fun `growth stops at the ceiling`() = next(2000, 10) shouldBe 2000

    @Test
    fun `a zero or negative duration cannot divide by zero`() {
        next(500, 0) shouldBe 1000
        next(500, -5) shouldBe 1000
    }

    @Test
    fun `a ceiling below the floor never goes under the floor`() = next(10, 1, floor = 10, ceiling = 4) shouldBe 10
}
