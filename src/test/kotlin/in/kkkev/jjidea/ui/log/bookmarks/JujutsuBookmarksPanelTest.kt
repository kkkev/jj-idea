package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

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

    @Test
    fun `a rebuild's own programmatic expand-collapse replay never triggers a settings write`() {
        // jj-idea-a7a7 (GitHub #48): every rebuild() re-applies expansionState onto the tree from
        // scratch (a DefaultTreeModel structure change resets Swing's own per-path expansion
        // state), which fires the same TreeExpansionListener a user's own click does. Without the
        // applyingExpansionState guard, every background state-model invalidation would spuriously
        // call onExpansionChanged and persist to settings, even with nothing to persist.
        var saveCount = 0
        val panel = JujutsuBookmarksPanel(project.get(), mutableMapOf(), onExpansionChanged = { saveCount++ })
        try {
            repeat(50) { panel.scheduleRebuild() }
            panel.flushRebuildQueue()

            saveCount shouldBe 0
        } finally {
            Disposer.dispose(panel)
        }
    }

    // jj-idea-ib1i / jj-idea-p35f (GitHub #48 splits 1/3, 2/3): the tree's selection is published
    // as action data context so registered bookmark actions and the log's own registered change
    // actions (New Change/Edit/Rebase) work from this panel. Nodes are spliced directly onto the
    // tree model rather than going through a real jj repo (buildBookmarkTree's own tree-shape
    // logic is already covered, unit-testably, by BookmarkTreeModelTest) - this only needs to
    // verify what a *selection* publishes.

    @Test
    fun `uiDataSnapshot publishes the tree's bookmark selection and resolved log entries`() {
        val repo = mockk<JujutsuRepository> { every { displayName } returns "repo" }
        val idA = ChangeId("aaaaaaaa", "a")
        val idB = ChangeId("bbbbbbbb", "b")
        val bookmarkA = Bookmark("main")
        val bookmarkB = Bookmark("release")
        val entryA = LogEntry(repo, idA, CommitId("1111111111111111111111111111111111"), "a")
        val entryB = LogEntry(repo, idB, CommitId("2222222222222222222222222222222222"), "b")
        val entriesByKey = mapOf(ChangeKey(repo, idA) to entryA, ChangeKey(repo, idB) to entryB)

        val fallbackDisposable = Disposer.newDisposable()
        val panel = JujutsuBookmarksPanel(project.get(), entryLookup = { entriesByKey[it] })
        try {
            HeadlessDataManager.fallbackToProductionDataManager(fallbackDisposable)
            selectLeaves(
                panel,
                BookmarkNode.Local(repo, BookmarkItem(bookmarkA, idA), "main", onWorkingCopy = false),
                BookmarkNode.Local(repo, BookmarkItem(bookmarkB, idB), "release", onWorkingCopy = false)
            )

            val dataContext = DataManager.getInstance().getDataContext(panel.tree)

            JujutsuDataKeys.BOOKMARK_TARGETS.getData(dataContext) shouldBe listOf(
                JujutsuDataKeys.BookmarkTarget(repo, bookmarkA, idA),
                JujutsuDataKeys.BookmarkTarget(repo, bookmarkB, idB)
            )
            JujutsuDataKeys.LOG_ENTRIES.getData(dataContext) shouldBe listOf(entryA, entryB)
        } finally {
            Disposer.dispose(fallbackDisposable)
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `uiDataSnapshot omits log entries when a selected bookmark's change is outside the loaded window`() {
        // The default entryLookup (always null) stands in for "this bookmark's change isn't in
        // the log table's currently-loaded window" - change actions must disable, not act on
        // whatever happened to resolve.
        val repo = mockk<JujutsuRepository> { every { displayName } returns "repo" }
        val id = ChangeId("aaaaaaaa", "a")
        val bookmark = Bookmark("main")

        val fallbackDisposable = Disposer.newDisposable()
        val panel = JujutsuBookmarksPanel(project.get())
        try {
            HeadlessDataManager.fallbackToProductionDataManager(fallbackDisposable)
            selectLeaves(panel, BookmarkNode.Local(repo, BookmarkItem(bookmark, id), "main", onWorkingCopy = false))

            val dataContext = DataManager.getInstance().getDataContext(panel.tree)

            JujutsuDataKeys.BOOKMARK_TARGET.getData(dataContext) shouldBe
                JujutsuDataKeys.BookmarkTarget(repo, bookmark, id)
            JujutsuDataKeys.LOG_ENTRIES.getData(dataContext) shouldBe null
            JujutsuDataKeys.LOG_ENTRY.getData(dataContext) shouldBe null
        } finally {
            Disposer.dispose(fallbackDisposable)
            Disposer.dispose(panel)
        }
    }
}

/** Splices [nodes] onto [panel]'s tree as top-level leaves and selects all of them. */
private fun selectLeaves(panel: JujutsuBookmarksPanel, vararg nodes: BookmarkNode) {
    val model = panel.tree.model as DefaultTreeModel
    val root = model.root as DefaultMutableTreeNode
    root.removeAllChildren()
    val treeNodes = nodes.map { DefaultMutableTreeNode(it).also(root::add) }
    model.reload()
    panel.tree.selectionPaths = treeNodes.map { TreePath(it.path) }.toTypedArray()
}
