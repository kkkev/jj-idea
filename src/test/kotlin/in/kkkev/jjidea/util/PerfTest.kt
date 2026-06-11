package `in`.kkkev.jjidea.util

import com.intellij.openapi.diagnostic.Logger
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

class PerfTest {
    // ─── PerfReport ──────────────────────────────────────────────────────────

    @Test
    fun `format renders single count with thousands separator`() {
        val report = PerfReport()
        report.count("visited", 1_843_201L)
        report.format() shouldBe "visited=1,843,201"
    }

    @Test
    fun `format renders multiple counts in insertion order`() {
        val report = PerfReport()
        report.count("visited", 1_843_201L)
        report.count("ignored", 12L)
        report.format() shouldBe "visited=1,843,201, ignored=12"
    }

    @Test
    fun `format returns empty string when no counts`() {
        val report = PerfReport()
        report.format() shouldBe ""
    }

    @Test
    fun `maxCount returns zero when no counts`() {
        PerfReport().maxCount() shouldBe 0L
    }

    @Test
    fun `maxCount returns the largest count`() {
        val report = PerfReport()
        report.count("a", 10L)
        report.count("b", 50_001L)
        report.count("c", 5L)
        report.maxCount() shouldBe 50_001L
    }

    // ─── measurePerf ─────────────────────────────────────────────────────────

    @Test
    fun `measurePerf returns the block result`() {
        val log = mockk<Logger>(relaxed = true)
        val result = log.measurePerf("op", "ctx") { _ -> 42 }
        result shouldBe 42
    }

    @Test
    fun `measurePerf logs INFO when under all thresholds`() {
        val log = mockk<Logger>(relaxed = true)
        log.measurePerf("op", "ctx", durationWarnMs = 10_000L, countWarnThreshold = 100L) { _ -> }
        val slot = slot<String>()
        verify { log.info(capture(slot)) }
        slot.captured shouldContain "perf: op took"
        slot.captured shouldContain "(ctx)"
        verify(exactly = 0) { log.warn(any<String>()) }
    }

    @Test
    fun `measurePerf logs WARN when count exceeds threshold`() {
        val log = mockk<Logger>(relaxed = true)
        log.measurePerf("op", "ctx", durationWarnMs = 10_000L, countWarnThreshold = 100L) { report ->
            report.count("visited", 101L)
        }
        verify(exactly = 0) { log.info(any<String>()) }
        val slot = slot<String>()
        verify { log.warn(capture(slot)) }
        slot.captured shouldContain "visited=101"
    }

    @Test
    fun `measurePerf message omits counts section when no counts set`() {
        val log = mockk<Logger>(relaxed = true)
        val slot = slot<String>()
        log.measurePerf("op", "ctx") { _ -> }
        verify { log.info(capture(slot)) }
        // no square-bracket counts section
        (slot.captured.contains("[")) shouldBe false
    }

    @Test
    fun `measurePerf message omits context parens when context is empty`() {
        val log = mockk<Logger>(relaxed = true)
        val slot = slot<String>()
        log.measurePerf("op") { _ -> }
        verify { log.info(capture(slot)) }
        (slot.captured.contains("(")) shouldBe false
    }
}
