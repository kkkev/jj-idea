package `in`.kkkev.jjidea.util

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [NotifiableState.cachedValue] and the [SimpleNotifiableState.immediateValue] fix
 * (jj-idea-c4tp): both used to gate on `equalityCheck(value, startValue)`, so a state that
 * genuinely loads to the same value as its start value (e.g. a repo with no git remotes) looked
 * "not yet loaded" forever and re-ran the loader synchronously on every access - exactly the kind
 * of blocking call [in.kkkev.jjidea.actions.file.TrackedToggleAction] already forbids from an
 * action `update()`/`getChildren()` running under a read action.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class NotifiableStateCachedValueTest {
    private val project = projectFixture()

    @Test
    fun `cachedValue never runs the loader on the calling thread`() {
        val callingThread = Thread.currentThread()
        var loaderRanOnCallingThread = false
        val state = SimpleNotifiableState(
            project.get(),
            "Test State ${System.nanoTime()}",
            startValue = 0,
            equalityCheck = { a, b -> a == b }
        ) {
            if (Thread.currentThread() === callingThread) loaderRanOnCallingThread = true
            42
        }

        state.cachedValue // triggers invalidate() on a cold cache - must not block or run inline
        drainBackgroundLoads()

        loaderRanOnCallingThread shouldBe false
    }

    @Test
    fun `cachedValue returns the loaded value once a background load completes`() {
        val state = SimpleNotifiableState(
            project.get(),
            "Test State ${System.nanoTime()}",
            startValue = 0,
            equalityCheck = { a, b -> a == b }
        ) { 42 }

        state.cachedValue // triggers invalidate() on a cold cache
        drainBackgroundLoads()

        state.cachedValue shouldBe 42
    }

    @Test
    fun `immediateValue on a loaded-but-still-empty state runs the loader only once, not per access`() {
        val loaderCalls = AtomicInteger(0)
        val state = SimpleNotifiableState(
            project.get(),
            "Test State ${System.nanoTime()}",
            startValue = 0,
            equalityCheck = { a, b -> a == b }
        ) {
            loaderCalls.incrementAndGet()
            0 // genuinely empty result, same as startValue
        }

        // Simulate reading from a background thread, as immediateValue's contract requires.
        val results = (1..5).map {
            var result = 0
            val t = Thread { result = state.immediateValue }
            t.start()
            t.join()
            result
        }

        results shouldBe listOf(0, 0, 0, 0, 0)
        loaderCalls.get() shouldBe 1 // the regression this test guards against: was 5
    }
}
