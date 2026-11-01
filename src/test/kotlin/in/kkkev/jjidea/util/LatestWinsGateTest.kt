package `in`.kkkev.jjidea.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LatestWinsGateTest {
    @Test
    fun `only the most recent token is current`() {
        val gate = LatestWinsGate()
        val first = gate.next()
        val second = gate.next()

        gate.isCurrent(first) shouldBe false
        gate.isCurrent(second) shouldBe true
    }
}
