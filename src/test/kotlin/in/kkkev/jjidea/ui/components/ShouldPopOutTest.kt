package `in`.kkkev.jjidea.ui.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ShouldPopOutTest {
    private fun pop(
        collapsed: Boolean = false,
        popupShowing: Boolean = false,
        showing: Boolean = true,
        now: Long = 1_000,
        suppressedUntil: Long = 0
    ) = shouldPopOut(collapsed, popupShowing, showing, now, suppressedUntil)

    @Test
    fun `pops out when the box is inline, whatever the mode`() {
        pop() shouldBe true
    }

    @Test
    fun `not when already the chip or in the popup`() {
        pop(collapsed = true) shouldBe false
        pop(popupShowing = true) shouldBe false
    }

    @Test
    fun `not while the box is not on screen`() {
        pop(showing = false) shouldBe false
    }

    @Test
    fun `not right after the popup closed, again afterwards`() {
        pop(now = 1_000, suppressedUntil = 1_500) shouldBe false
        pop(now = 1_500, suppressedUntil = 1_500) shouldBe true
    }
}
