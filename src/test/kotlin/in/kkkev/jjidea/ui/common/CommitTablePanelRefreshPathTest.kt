package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression test for jj-idea-2c8k's bug 6 (docs/design/jj-idea-2c8k-paged-log-loading.md):
 * [CommitTablePanel.refresh] was once wired to [DataLoader.forceRefresh] instead of
 * [DataLoader.refresh] — a pure rename-shaped mistake nothing in the type system catches (both
 * exist on every [DataLoader], and [DataLoader.forceRefresh] defaults to [DataLoader.refresh] for
 * non-paged loaders, so the swap is invisible outside a paged loader). [CommitTablePanel.refresh]
 * is bound to [in.kkkev.jjidea.jj.JujutsuStateModel.logRefresh], which fires after **every
 * write** — so wiring it to `forceRefresh()` silently made every write re-verify every loaded
 * page, exactly the GitHub #69 latency bug this bead exists to fix. Caught only by manual
 * `runIde` testing; this pins the fix down.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class CommitTablePanelRefreshPathTest {
    private val project = projectFixture()

    // Constructing CommitTablePanel touches project.stateModel (JujutsuReferenceFilterComponent /
    // JujutsuAuthorFilterComponent init), which fires fire-and-forget pooled-thread loaders
    // capturing this fixture's project - drain them before projectFixture disposes, matching
    // JujutsuBookmarksPanelTest's guard against a flaky LeakHunter retained-Project report
    // (jj-idea-q49j).
    @AfterEach
    fun drainStateModelLoads() = drainBackgroundLoads()

    private class RecordingDataLoader : DataLoader {
        val calls = mutableListOf<String>()

        override fun load() {
            calls += "load"
        }

        override fun refresh() {
            calls += "refresh"
        }

        override fun forceRefresh() {
            calls += "forceRefresh"
        }
    }

    private class TestPanel(project: Project, dataLoaderFactory: (CommitTablePanel<Unit>) -> DataLoader) :
        CommitTablePanel<Unit>(project, "CommitTablePanelRefreshPathTestToolbar", dataLoaderFactory) {
        override fun onDataLoaded(newData: Unit) {}

        override fun updateTableStuff() {}

        override fun dispose() {}
    }

    @Test
    fun `refresh() calls the loader's cheap refresh, never forceRefresh`() {
        lateinit var loader: RecordingDataLoader
        val panel = TestPanel(project.get()) { RecordingDataLoader().also { loader = it } }
        try {
            // Construction itself triggers an initial load() - clear that before exercising the
            // method under test.
            loader.calls.clear()

            panel.refresh()

            // The bug-6 assertion: refresh() must go through DataLoader.refresh() (page-1-only
            // for a paged loader), never DataLoader.forceRefresh() (paged re-verification of
            // every loaded page) - that swap is what silently reintroduced GitHub #69's per-write
            // latency after this bead's fix first shipped.
            loader.calls shouldBe listOf("refresh")
        } finally {
            Disposer.dispose(panel)
        }
    }
}
