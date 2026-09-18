package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.stateModel
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

    /**
     * Regression test for jj-idea-bbn3 (GitHub #115): the toolbar Refresh action used to call
     * only [DataLoader.forceRefresh], which reloads log rows but never re-reads
     * [in.kkkev.jjidea.jj.JujutsuStateModel.references] /
     * [in.kkkev.jjidea.jj.JujutsuStateModel.workingCopies] /
     * [in.kkkev.jjidea.jj.JujutsuStateModel.closestBookmarks] — those are only refreshed via
     * [in.kkkev.jjidea.jj.JujutsuStateModel.invalidateRepositoryState]. Normally masked by the
     * op_heads VFS watch auto-refreshing after any external jj operation, but that watch commonly
     * doesn't fire on network-mounted repos, leaving manual Refresh as the only path — and it
     * wasn't actually re-reading bookmarks/working copy. [CommitTablePanel.manualRefresh] must do
     * both: force the log rows *and* invalidate repository state.
     */
    @Test
    fun `manualRefresh re-reads repository state as well as log rows`() {
        lateinit var loader: RecordingDataLoader
        val panel = TestPanel(project.get()) { RecordingDataLoader().also { loader = it } }
        try {
            val stateModel = project.get().stateModel
            // Construction itself triggers an initial load() - clear that before exercising the
            // method under test, matching the sibling test above.
            loader.calls.clear()
            stateModel.closestBookmarks.hasLoaded shouldBe false

            panel.manualRefresh()
            drainBackgroundLoads()

            // The toolbar's explicit Refresh is the one caller allowed to force a full page
            // re-verification (see refresh()'s doc comment) ...
            loader.calls shouldBe listOf("forceRefresh")
            // ... and the one caller that re-reads repository state, so bookmarks/working copy
            // don't stay stale for users whose op_heads watch never fires.
            stateModel.closestBookmarks.hasLoaded shouldBe true
        } finally {
            Disposer.dispose(panel)
        }
    }
}
