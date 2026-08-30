package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Platform-wiring tests for [JujutsuBookmarksPanel] (jj-idea-b2ae): construction on a repo-less
 * project, rebuild coalescing on a burst of invalidations (the same scale-guard pattern as
 * `UnifiedWorkingCopyPanelScaleTest` for jj-idea-f21f), and clean disposal. The tree-content logic
 * itself is covered without a platform test by [BookmarkTreeModelTest].
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuBookmarksPanelTest {
    private val project = projectFixture()

    // Touching project.stateModel (constructing the panel does) fires fire-and-forget
    // pooled-thread loaders that capture this fixture's project; drain them before projectFixture
    // disposes the project, to avoid a flaky LeakHunter retained-Project report (jj-idea-q49j).
    @AfterEach
    fun drainStateModelLoads() = drainBackgroundLoads()

    @Test
    fun `constructs empty on a project with no jj repositories`() {
        val panel = JujutsuBookmarksPanel(project.get())
        try {
            (panel.tree.model.root as DefaultMutableTreeNode).childCount shouldBe 0
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `a burst of scheduled rebuilds coalesces into a single rebuild`() {
        val panel = JujutsuBookmarksPanel(project.get())
        try {
            val before = panel.rebuildCount
            repeat(1_000) { panel.scheduleRebuild() }

            panel.flushRebuildQueue()

            // Bound: must be exactly 1, not 1_000 - a regression that rebuilds per queued event
            // (or gives each queued Update a distinct identity) blows through.
            (panel.rebuildCount - before) shouldBe 1
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `dispose is clean`() {
        val panel = JujutsuBookmarksPanel(project.get())
        Disposer.dispose(panel)
    }
}
