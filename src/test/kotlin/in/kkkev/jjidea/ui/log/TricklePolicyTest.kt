package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Pure tests for the paged log idle trickle's throttle and stop rule (jj-idea-2570.5). */
class TricklePolicyTest {
    @Test
    fun `waits three times the work just done, for about a 25 percent duty cycle`() {
        TricklePolicy.delayAfter(400) shouldBe 1_200
    }

    @Test
    fun `a trivially fast page still waits the floor`() {
        TricklePolicy.delayAfter(0) shouldBe TricklePolicy.MIN_DELAY_MS
        TricklePolicy.delayAfter(10) shouldBe TricklePolicy.MIN_DELAY_MS
    }

    @Test
    fun `one very slow page cannot stall the trickle past the ceiling`() {
        TricklePolicy.delayAfter(60_000) shouldBe TricklePolicy.MAX_DELAY_MS
    }

    @Test
    fun `wants more only while something is pageable and under the row cap`() {
        TricklePolicy.wantsMore(loadedRows = 0, anyRepoPageable = true) shouldBe true
        TricklePolicy.wantsMore(TricklePolicy.ROW_CAP - 1, true) shouldBe true
        TricklePolicy.wantsMore(TricklePolicy.ROW_CAP, true) shouldBe false
        TricklePolicy.wantsMore(0, anyRepoPageable = false) shouldBe false
    }
}
