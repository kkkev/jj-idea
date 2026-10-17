package `in`.kkkev.jjidea.jj.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * jj-idea-innq: [runCancellably] must destroy the process as soon as its caller is cancelled, not leave it
 * running until it exits or times out. Exercised against a real `sleep` process.
 */
class CliExecutorCancellationTest {
    @Test
    fun `cancelling destroys the running process promptly`() {
        val handler = CapturingProcessHandler(GeneralCommandLine("sleep", "30"))
        val cancelled = AtomicBoolean(false)
        thread {
            Thread.sleep(200)
            cancelled.set(true)
        }

        val start = System.currentTimeMillis()
        val output = handler.runCancellably(120_000) { cancelled.get() }

        (System.currentTimeMillis() - start) shouldBeLessThan 5_000
        output.isCancelled shouldBe true
        output.isTimeout shouldBe false
        handler.waitFor(5_000) shouldBe true
        handler.process.isAlive shouldBe false
    }

    @Test
    fun `a process that is never cancelled completes normally`() {
        val handler = CapturingProcessHandler(GeneralCommandLine("echo", "done"))

        val output = handler.runCancellably(30_000) { false }

        output.isCancelled shouldBe false
        output.exitCode shouldBe 0
        output.stdout.trim() shouldBe "done"
    }

    @Test
    fun `a cancellable run still times out`() {
        val handler = CapturingProcessHandler(GeneralCommandLine("sleep", "30"))

        val output = handler.runCancellably(200) { false }

        output.isTimeout shouldBe true
        output.isCancelled shouldBe false
    }

    @Test
    fun `nothing is cancelled outside a progress or coroutine context`() {
        contextCancelled() shouldBe false
    }
}
