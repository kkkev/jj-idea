package `in`.kkkev.jjidea.util

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException

@Tag("platform")
@TestApplication
class TasksBackgroundErrorTest {
    @Test
    fun `uncaught exception in runInBackground is logged, not swallowed`() {
        val logged = LoggedErrorProcessor.executeAndReturnLoggedError {
            val future = runInBackground { throw RuntimeException("boom") }
            try {
                future.get()
            } catch (e: ExecutionException) {
                // expected: the Future still fails as before
            }
        }
        logged.message shouldBe "boom"
    }

    @Test
    fun `ProcessCanceledException is not logged`() {
        var loggedAnything = false
        LoggedErrorProcessor.executeWith<RuntimeException>(
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<String>,
                    t: Throwable?
                ): Set<Action> {
                    loggedAnything = true
                    return Action.NONE
                }
            }
        ) {
            val future = runInBackground { throw ProcessCanceledException() }
            try {
                future.get()
            } catch (e: ExecutionException) {
                // expected: control-flow exception still propagates via the Future
            }
        }
        loggedAnything shouldBe false
    }

    @Test
    fun `successful action still returns its result`() {
        runInBackground { 42 }.get() shouldBe 42
    }
}
