package `in`.kkkev.jjidea.util

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * jj-idea-fchy: a loader must not run against a disposing project - loaders can lazily create
 * project services (e.g. the platform's AllVcses) during teardown, which leaks the Project.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class SimpleNotifiableStateDisposedProjectTest {
    private val project = projectFixture()

    @Test
    fun `invalidate on a disposed project never runs the loader`() {
        val disposed = mockk<Project>(relaxed = true)
        every { disposed.isDisposed } returns true
        every { disposed.messageBus } returns project.get().messageBus
        val loads = AtomicInteger()

        val state = SimpleNotifiableState(
            disposed,
            "Test State ${System.nanoTime()}",
            startValue = 0,
            equalityCheck = { a, b -> a == b }
        ) { loads.incrementAndGet() }

        state.invalidate()
        drainBackgroundLoads()

        loads.get() shouldBe 0
        state.hasLoaded shouldBe false
    }
}
