package `in`.kkkev.jjidea.jj

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuStateModelPlatformTest {
    private val project = projectFixture()

    // stateModel.init fires fire-and-forget pooled-thread loaders that capture this fixture's
    // project (see PlatformTestSupport.drainBackgroundLoads); drain them before projectFixture
    // disposes the project, to avoid a flaky LeakHunter retained-Project report (jj-idea-q49j).
    @AfterEach
    fun drainStateModelLoads() = drainBackgroundLoads()

    @Test
    fun `logRefresh notifier fires connected listener`() {
        val stateModel = project.get().stateModel
        val disposable = Disposer.newDisposable()
        try {
            var fired = false
            stateModel.logRefresh.connect(disposable) { fired = true }

            stateModel.logRefresh.notify(Unit)
            UIUtil.dispatchAllInvocationEvents()

            fired shouldBe true
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `invalidate fires logRefresh`() {
        val stateModel = project.get().stateModel
        val disposable = Disposer.newDisposable()
        try {
            var fired = false
            stateModel.logRefresh.connect(disposable) { fired = true }

            val repo = mockk<JujutsuRepository> {
                every { project } returns this@JujutsuStateModelPlatformTest.project.get()
                every { logCache } returns mockk(relaxed = true)
            }
            repo.invalidate()
            UIUtil.dispatchAllInvocationEvents()

            fired shouldBe true
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `invalidate with select fires both logRefresh and changeSelection`() {
        val stateModel = project.get().stateModel
        val disposable = Disposer.newDisposable()
        try {
            var logRefreshFired = false
            var selectionKey: ChangeKey? = null
            stateModel.logRefresh.connect(disposable) { logRefreshFired = true }
            stateModel.changeSelection.connect(disposable) { selectionKey = it }

            val repo = mockk<JujutsuRepository> {
                every { project } returns this@JujutsuStateModelPlatformTest.project.get()
                every { logCache } returns mockk(relaxed = true)
            }
            repo.invalidate(select = WorkingCopy)
            UIUtil.dispatchAllInvocationEvents()

            logRefreshFired shouldBe true
            selectionKey shouldBe ChangeKey(repo, WorkingCopy)
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
