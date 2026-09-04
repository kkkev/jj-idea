package `in`.kkkev.jjidea.util

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression test for the "Working Copy panel stuck on 'Checking jj…'" bug: a simplification
 * replaced [in.kkkev.jjidea.jj.JjAvailabilityChecker]'s explicit `recheck()` call at startup with
 * [NotifiableState.connectAndFireSync], but the default implementation only replayed the current
 * (start) value — nothing ever called [NotifiableState.invalidate], so a cold state's status never
 * loaded. Fixed by having [SimpleNotifiableState.connectAndFireSync] go through
 * [NotifiableState.cachedValue], which starts the first load itself.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class ConnectAndFireSyncTest {
    private val project = projectFixture()

    @Test
    fun `connectAndFireSync on a cold state fires the start value and still triggers a load`() {
        val state = SimpleNotifiableState(
            project.get(),
            "Test State ${System.nanoTime()}",
            startValue = 0,
            equalityCheck = { a, b -> a == b }
        ) { 42 }

        val received = mutableListOf<Int>()
        val disposable = Disposer.newDisposable()
        try {
            state.connectAndFireSync(disposable) { received += it }
            received shouldBe listOf(0) // delivered synchronously, before the load completes

            drainBackgroundLoads()

            received shouldBe listOf(0, 42) // background load completed and notified
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `connectAndFireSync on an already-loaded state delivers the value exactly once`() {
        val state = SimpleNotifiableState(
            project.get(),
            "Test State ${System.nanoTime()}",
            startValue = 0,
            equalityCheck = { a, b -> a == b }
        ) { 42 }

        state.invalidate()
        drainBackgroundLoads()

        val callCount = AtomicInteger(0)
        val disposable = Disposer.newDisposable()
        try {
            state.connectAndFireSync(disposable) { callCount.incrementAndGet() }

            callCount.get() shouldBe 1
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
