package `in`.kkkev.jjidea.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class RelativeTimeTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    @Test
    fun `coarse relative wording`() {
        relativeAgo(now.minusSeconds(20), now) shouldBe "just now"
        relativeAgo(now.minusSeconds(5 * 60), now) shouldBe "5 min ago"
        relativeAgo(now.minusSeconds(3 * 3600), now) shouldBe "3 h ago"
        relativeAgo(now.minusSeconds(2 * 86400), now) shouldBe "2 d ago"
    }

    @Test
    fun `a time in the future reads as just now`() {
        relativeAgo(now.plusSeconds(60), now) shouldBe "just now"
    }
}
