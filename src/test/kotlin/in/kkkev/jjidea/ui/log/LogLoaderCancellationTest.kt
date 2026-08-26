package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.progress.ProgressIndicator
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tests for [awaitCancellably] (jj-idea-c4tp): the replacement for a plain
 * `latch.await(5, TimeUnit.MINUTES)` that ignored `indicator.isCanceled` entirely and could stall
 * project close for up to five minutes. All timeouts here are small so the test itself stays fast;
 * only the return value and rough timing bounds are asserted, never precise timing.
 *
 * [ProgressIndicator] is mocked rather than using a real implementation (e.g.
 * `EmptyProgressIndicator`) because those construct an [com.intellij.openapi.application.Application]-
 * backed `ModalityState` and this is a plain unit test with no platform application loaded.
 */
class LogLoaderCancellationTest {
    private fun fakeIndicator(): ProgressIndicator {
        val cancelled = AtomicBoolean(false)
        val indicator = mockk<ProgressIndicator>()
        every { indicator.isCanceled } answers { cancelled.get() }
        every { indicator.cancel() } answers { cancelled.set(true) }
        return indicator
    }

    @Test
    fun `returns true promptly once the latch counts down`() {
        val latch = CountDownLatch(1)
        val indicator = fakeIndicator()
        thread {
            Thread.sleep(50)
            latch.countDown()
        }

        val start = System.currentTimeMillis()
        val result = awaitCancellably(latch, indicator, timeoutMs = 5_000, pollMs = 20)
        val elapsed = System.currentTimeMillis() - start

        result shouldBe true
        (elapsed < 2_000) shouldBe true
    }

    @Test
    fun `returns false shortly after the indicator is cancelled, without waiting out the timeout`() {
        val latch = CountDownLatch(1) // never counts down
        val indicator = fakeIndicator()
        val cancelledAt = AtomicBoolean(false)
        thread {
            Thread.sleep(50)
            indicator.cancel()
            cancelledAt.set(true)
        }

        val start = System.currentTimeMillis()
        val result = awaitCancellably(latch, indicator, timeoutMs = 10_000, pollMs = 20)
        val elapsed = System.currentTimeMillis() - start

        result shouldBe false
        cancelledAt.get() shouldBe true
        (elapsed < 2_000) shouldBe true
    }

    @Test
    fun `returns false once the deadline passes without cancellation or countdown`() {
        val latch = CountDownLatch(1) // never counts down
        val indicator = fakeIndicator()

        val result = awaitCancellably(latch, indicator, timeoutMs = 100, pollMs = 20)

        result shouldBe false
    }

    @Test
    fun `does not report cancellation if the latch already counted down`() {
        val latch = CountDownLatch(1)
        latch.countDown()
        val indicator = fakeIndicator()
        indicator.cancel()

        awaitCancellably(latch, indicator, timeoutMs = 5_000) shouldBe true
    }
}
