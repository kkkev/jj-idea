package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.TagItem
import `in`.kkkev.jjidea.ui.dnd.DragContext
import `in`.kkkev.jjidea.ui.dnd.DragPayload
import `in`.kkkev.jjidea.ui.dnd.DropOperation
import `in`.kkkev.jjidea.ui.dnd.DropTarget
import `in`.kkkev.jjidea.ui.dnd.resolveDropOperation
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Point
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import org.junit.jupiter.api.Tag as JupiterTag

private const val PREVIEW_PROPERTY = "jjidea.preview.dragAndDrop"

/**
 * Platform-level coverage for [JujutsuBookmarksPanel.dragPayloadAt]/[dropTargetAt] (jj-idea-0rdm,
 * batch 4) against a real, laid-out panel - mirrors
 * [in.kkkev.jjidea.ui.log.JujutsuLogTableDnDChipTest]'s shape for the log table's own chip
 * hit-tests. The preview system property is set purely so [installDragAndDrop] doesn't matter
 * either way for the hit-test tests (which call [dragPayloadAt]/[dropTargetAt] directly, not
 * through a live `DnDEvent`); the gating region at the bottom exercises the install call itself.
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class JujutsuBookmarksPanelDnDTest {
    private val project = projectFixture()
    private val repoA = mockk<JujutsuRepository>(relaxed = true)
    private val repoB = mockk<JujutsuRepository>(relaxed = true)
    private var panel: JujutsuBookmarksPanel? = null

    @BeforeEach
    fun enablePreview() {
        System.setProperty(PREVIEW_PROPERTY, "true")
    }

    @AfterEach
    fun cleanUp() {
        System.clearProperty(PREVIEW_PROPERTY)
        panel?.let { Disposer.dispose(it) }
        drainBackgroundLoads()
    }

    /** Splices [nodes] onto a fresh panel's tree as top-level leaves, laid out for hit-testing. */
    private fun panelWith(vararg nodes: BookmarkNode): JujutsuBookmarksPanel {
        val panel = JujutsuBookmarksPanel(project.get())
        this.panel = panel
        val model = panel.tree.model as DefaultTreeModel
        val root = model.root as DefaultMutableTreeNode
        nodes.forEach { root.add(DefaultMutableTreeNode(it)) }
        model.reload()
        for (row in 0 until panel.tree.rowCount) panel.tree.expandRow(row)
        panel.setSize(400, 400)
        panel.doLayout()
        panel.tree.setSize(400, 400)
        panel.tree.doLayout()
        return panel
    }

    /** The point at [row]'s tree path, for driving [JujutsuBookmarksPanel.dragPayloadAt]/`dropTargetAt`. */
    private fun rowPoint(panel: JujutsuBookmarksPanel, row: Int): Point {
        val bounds = panel.tree.getRowBounds(row)
        return Point(bounds.x + 5, bounds.y + bounds.height / 2)
    }

    // region dragPayloadAt

    @Test
    fun `dragging a local bookmark node picks up a BookmarkRef payload carrying its repo and id directly`() {
        val id = ChangeId("aaaaaaaa", "a")
        val bookmark = Bookmark("main")
        val panel = panelWith(BookmarkNode.Local(repoA, BookmarkItem(bookmark, id), "main", onWorkingCopy = false))

        val payload = panel.dragPayloadAt(rowPoint(panel, 0))

        payload.shouldNotBeNull()
        payload as DragPayload.BookmarkRef
        payload.repo shouldBe repoA
        payload.id shouldBe id
        payload.bookmark shouldBe bookmark
    }

    @Test
    fun `dragging a conflicted local bookmark node carries every target - jj-idea-bico`() {
        val idA = ChangeId("aaaaaaaa", "a")
        val idB = ChangeId("bbbbbbbb", "b")
        val bookmark = Bookmark("main", conflict = true)
        val panel = panelWith(
            BookmarkNode.Local(repoA, BookmarkItem(bookmark, listOf(idA, idB)), "main", onWorkingCopy = false)
        )

        val payload = panel.dragPayloadAt(rowPoint(panel, 0))

        payload.shouldNotBeNull()
        payload as DragPayload.BookmarkRef
        payload.id shouldBe idA
        payload.targets shouldBe setOf(idA, idB)
    }

    @Test
    fun `dragging a remote bookmark node picks up a BookmarkRef payload`() {
        val id = ChangeId("aaaaaaaa", "a")
        val bookmark = Bookmark("main@origin")
        val panel = panelWith(BookmarkNode.Remote(repoA, BookmarkItem(bookmark, id), "main"))

        val payload = panel.dragPayloadAt(rowPoint(panel, 0))

        payload.shouldNotBeNull()
        payload as DragPayload.BookmarkRef
        payload.repo shouldBe repoA
        payload.id shouldBe id
    }

    @Test
    fun `dragging a tag node picks up a TagRef payload`() {
        val id = ChangeId("aaaaaaaa", "a")
        val tag = Tag("v1")
        val panel = panelWith(BookmarkNode.Tag(repoA, TagItem(tag, id), "v1"))

        val payload = panel.dragPayloadAt(rowPoint(panel, 0))

        payload.shouldNotBeNull()
        payload as DragPayload.TagRef
        payload.repo shouldBe repoA
        payload.id shouldBe id
        payload.tag shouldBe tag
    }

    @Test
    fun `a bookmark node with no id - deleted or pending-delete - produces no drag payload`() {
        val bookmark = Bookmark("main", deleted = true)
        val panel =
            panelWith(BookmarkNode.Local(repoA, BookmarkItem(bookmark, id = null), "main", onWorkingCopy = false))

        panel.dragPayloadAt(rowPoint(panel, 0)).shouldBeNull()
    }

    @Test
    fun `a working-copy node produces no drag payload - it has no bookmark identity`() {
        val panel = panelWith(
            BookmarkNode.WorkingCopy(repoA, "main", id = null, onWorkingCopyNames = listOf("main"), closest = null)
        )

        panel.dragPayloadAt(rowPoint(panel, 0)).shouldBeNull()
    }

    // endregion

    // region dropTargetAt

    @Test
    fun `dropping onto a local bookmark node resolves to a RefChip target`() {
        val id = ChangeId("aaaaaaaa", "a")
        val bookmark = Bookmark("main")
        val panel = panelWith(BookmarkNode.Local(repoA, BookmarkItem(bookmark, id), "main", onWorkingCopy = false))

        val target = panel.dropTargetAt(rowPoint(panel, 0))

        target.shouldNotBeNull()
        target as DropTarget.RefChip
        target.repo shouldBe repoA
        target.id shouldBe id
        target.bookmark shouldBe bookmark
    }

    @Test
    fun `dropping onto a tag node resolves to a TagChip target`() {
        val id = ChangeId("aaaaaaaa", "a")
        val tag = Tag("v1")
        val panel = panelWith(BookmarkNode.Tag(repoA, TagItem(tag, id), "v1"))

        val target = panel.dropTargetAt(rowPoint(panel, 0))

        target.shouldNotBeNull()
        target as DropTarget.TagChip
        target.repo shouldBe repoA
        target.id shouldBe id
        target.tag shouldBe tag
    }

    @Test
    fun `a bookmark node with no id produces no drop target`() {
        val bookmark = Bookmark("main", deleted = true)
        val panel =
            panelWith(BookmarkNode.Local(repoA, BookmarkItem(bookmark, id = null), "main", onWorkingCopy = false))

        panel.dropTargetAt(rowPoint(panel, 0)).shouldBeNull()
    }

    // endregion

    // region push by dragging local onto remote, within the panel (jj-idea-3xab)

    @Test
    fun `dragging a local bookmark node onto its remote node's own panel node resolves to a Push`() {
        // Falls out of the payload/target hit-tests above with no dedicated push code in this
        // file - resolveDropOperation's existing BookmarkRef+RefChip cell (DropOperation.kt) does
        // the rest, and DropPerformers.forLogTable always routes Push through the pre-filled
        // GitPushDialog (pushBookmarkAction.openPushDialogFor), never a direct push.
        val localId = ChangeId("aaaaaaaa", "a")
        val local = Bookmark("main")
        val remote = Bookmark("main@origin")
        val localPanel =
            panelWith(BookmarkNode.Local(repoA, BookmarkItem(local, localId), "main", onWorkingCopy = false))
        val payload = localPanel.dragPayloadAt(rowPoint(localPanel, 0))
        Disposer.dispose(localPanel)

        val remoteId = ChangeId("bbbbbbbb", "b")
        val remotePanel = panelWith(BookmarkNode.Remote(repoA, BookmarkItem(remote, remoteId), "main"))
        val target = remotePanel.dropTargetAt(rowPoint(remotePanel, 0))

        payload.shouldNotBeNull()
        target.shouldNotBeNull()
        val op = resolveDropOperation(payload, target, copy = false)
        op.shouldNotBeNull()
        op as DropOperation.Push
        op.bookmark shouldBe local
        op.remote shouldBe "origin"
        op.repo shouldBe repoA
    }

    // endregion

    // region cross-repo rejection (multi-root project)

    @Test
    fun `a commit dragged from one repo onto another repo's panel node is rejected`() {
        val sourceEntry = LogEntry(repoA, ChangeId("aaaaaaaa", "a"), CommitId("commit-a"), "desc a")
        val destId = ChangeId("bbbbbbbb", "b")
        val bookmark = Bookmark("main")
        val panel = panelWith(BookmarkNode.Local(repoB, BookmarkItem(bookmark, destId), "main", onWorkingCopy = false))

        val target = panel.dropTargetAt(rowPoint(panel, 0))
        val payload = DragPayload.Commit(listOf(sourceEntry))
        val context = DragContext.forDrag(listOf(sourceEntry), payload)

        target.shouldNotBeNull()
        context.rejectionReason(target, copy = false) shouldBe "Cannot drop across repositories"
    }

    // endregion

    // region gating

    @Test
    fun `installDragAndDrop does not throw with the preview feature off`() {
        System.clearProperty(PREVIEW_PROPERTY)
        val panel = JujutsuBookmarksPanel(project.get())
        this.panel = panel
    }

    @Test
    fun `installDragAndDrop does not throw with the preview feature on`() {
        // DnDManager is a no-op in tests (HeadlessDnDManager, same as JujutsuLogTableDnDTest), so
        // there's no client-property-style signal that install() actually ran - this only asserts
        // the gated call itself is safe; the hit-test regions above cover the actual behaviour the
        // builder wires up.
        val panel = JujutsuBookmarksPanel(project.get())
        this.panel = panel
    }

    // endregion
}
