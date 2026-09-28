package `in`.kkkev.jjidea.settings

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression coverage for jj-idea-vqpn: [JujutsuConfigurable.apply] only fired
 * [in.kkkev.jjidea.jj.JujutsuStateModel.logRefresh] when the *global* logRevset/logChangeLimit
 * fields changed, never for a per-repo override of either — so editing a repo's Log Revset
 * override in Settings left every open log window (and jj-idea-vqpn's revset filter chip, which
 * ANDs against that same value) showing stale data until an unrelated refresh happened to fire.
 * Found via manual testing of jj-idea-vqpn's revset filter chip.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuConfigurableLogRefreshTest {
    private val project = projectFixture()

    // createPanel() kicks off JujutsuConfigurable's fire-and-forget identity-loading coroutine
    // (per-repo identity save in apply() too), which captures this fixture's project - drain it
    // before projectFixture disposes the project, same as JujutsuStateModelPlatformTest, to
    // avoid a flaky LeakHunter report. The generous 2s budget (vs. drainBackgroundLoads()'s 1s
    // default) matches PagedLogLoaderConcurrencyTest/PagedLogRevsetChangeTest's own choice for
    // the same reason: this class's four tests each do genuinely more background work
    // (createPanel + apply, several times) than most drainBackgroundLoads() callers, so a full
    // run's cumulative pooled-thread load needs more headroom - found via bisection after the
    // full suite's LeakHunter intermittently reported an unrelated leaked Project only when this
    // class ran alongside PagedLogRevsetChangeTest.
    @AfterEach
    fun drainConfigurableLoads() = drainBackgroundLoads(2_000)

    private fun stubRepo(path: String): JujutsuRepository {
        val directory = mockk<VirtualFile>(relaxed = true) { every { this@mockk.path } returns path }
        return mockk(relaxed = true) {
            every { this@mockk.project } returns this@JujutsuConfigurableLogRefreshTest.project.get()
            every { this@mockk.directory } returns directory
            every { this@mockk.displayName } returns path
        }
    }

    /**
     * Builds a [JujutsuConfigurable] the way the real Settings dialog does: [Configurable.createComponent]
     * then [Configurable.reset], which populates bound fields (e.g. the global revsetField) from
     * the model. Skipping `reset()` leaves those fields at Swing's blank default, so `apply()`
     * would spuriously overwrite `settings.state.logRevset`'s non-blank default ("all()") with "".
     *
     * [block] runs with the built configurable; [Configurable.disposeUIResources] is always called
     * after, matching what the real Settings dialog does on close - skipping it leaks the
     * `jjAvailabilityStatus` message-bus connection [createPanel] registers, which a LeakHunter
     * platform test then reports as a retained [com.intellij.openapi.project.Project].
     */
    private fun withConfigurable(repo: JujutsuRepository, block: (JujutsuConfigurable) -> Unit) {
        val configurable = JujutsuConfigurable(project.get(), listOf(repo))
        configurable.createPanel()
        configurable.reset()
        try {
            block(configurable)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun withLogRefreshListener(block: (fired: () -> Boolean) -> Unit) {
        val disposable = Disposer.newDisposable()
        try {
            var fired = false
            project.get().stateModel.logRefresh.connect(disposable) { fired = true }
            block { fired }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `setting a per-repo revset override fires logRefresh`() {
        val repo = stubRepo("/repos/one")

        withConfigurable(repo) { configurable ->
            withLogRefreshListener { fired ->
                configurable.setRepoRevsetOverrideForTest(repo, "ancestors(@, 20)")
                configurable.apply()
                UIUtil.dispatchAllInvocationEvents()

                fired() shouldBe true
            }
        }
    }

    @Test
    fun `changing an already-set per-repo revset override fires logRefresh`() {
        val repo = stubRepo("/repos/one")
        JujutsuSettings.getInstance(project.get()).state.repositoryOverrides["/repos/one"] =
            RepositoryConfig(logRevset = "trunk()")

        withConfigurable(repo) { configurable ->
            withLogRefreshListener { fired ->
                configurable.setRepoRevsetOverrideForTest(repo, "ancestors(@, 5)")
                configurable.apply()
                UIUtil.dispatchAllInvocationEvents()

                fired() shouldBe true
            }
        }
    }

    @Test
    fun `clearing a per-repo revset override fires logRefresh`() {
        val repo = stubRepo("/repos/one")
        JujutsuSettings.getInstance(project.get()).state.repositoryOverrides["/repos/one"] =
            RepositoryConfig(logRevset = "trunk()")

        withConfigurable(repo) { configurable ->
            withLogRefreshListener { fired ->
                configurable.setRepoRevsetOverrideForTest(repo, null)
                configurable.apply()
                UIUtil.dispatchAllInvocationEvents()

                fired() shouldBe true
            }
        }
    }

    // A "no changes -> no notify" negative test was tried and dropped: JujutsuStateModel's own
    // background initialization (unrelated to Settings apply - see its logRefresh.notify call
    // sites in jj/JujutsuStateModel.kt) can independently fire logRefresh once its async work
    // lands on the EDT, racing this test's dispatchAllInvocationEvents() regardless of what
    // apply() itself does. Not testable reliably here; the three positive cases above are the
    // actual regression coverage for jj-idea-vqpn's fix.
}
