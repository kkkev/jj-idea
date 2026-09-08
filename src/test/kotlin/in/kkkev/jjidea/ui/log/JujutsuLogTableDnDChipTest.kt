package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.ui.dnd.DragPayload
import `in`.kkkev.jjidea.ui.dnd.DropTarget
import `in`.kkkev.jjidea.ui.dnd.ZoneHysteresis
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.event.MouseEvent
import org.junit.jupiter.api.Tag as JupiterTag

private const val PREVIEW_PROPERTY = "jjidea.preview.dragAndDrop"

/**
 * Platform-level coverage for [JujutsuLogTable.dragPayloadAt] and [dropTargetAt]'s chip handling
 * (jj-idea-ibth, -vdwh) against a real, laid-out [JujutsuLogTable] - the chip geometry these two
 * rely on ([JujutsuLogTable.clickTargetAt]) already has its own coverage in
 * [JujutsuLogTableBookmarkClickTest]; this only covers the drag/drop-specific dispatch on top of
 * it. Mirrors [JujutsuLogTableDnDTest]'s fixture (real preview-feature gating isn't exercised
 * here, only the pure hit-test functions, so the preview system property is set purely so
 * `installDragAndDrop` doesn't matter either way).
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableDnDChipTest {
    private val project = projectFixture()

    // The bookmark/tag chip's jjref:// URI embeds repo.directory.path (LogEntryText.kt's
    // refUri) - an unstubbed relaxed mock returns "", which makes that URI unparseable (its
    // regex requires a non-empty repo-path segment) and every chip hit-test silently miss.
    // Same stub JujutsuLogTableChipIssueLinkTest/LaidOutCellTest/LogClickTargetTest already use.
    private val repo = mockk<JujutsuRepository>(relaxed = true).also { every { it.directory.path } returns "/repo" }
    private var table: JujutsuLogTable? = null

    @BeforeEach
    fun enablePreview() {
        System.setProperty(PREVIEW_PROPERTY, "true")
    }

    @AfterEach
    fun cleanUp() {
        System.clearProperty(PREVIEW_PROPERTY)
        table?.let {
            it.dispatchEvent(MouseEvent(it, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0, false))
        }
        drainBackgroundLoads()
    }

    private fun entry(id: String, bookmarks: List<Bookmark> = emptyList(), tags: List<Tag> = emptyList()) =
        LogEntry(
            repo = repo,
            id = ChangeId(id, id, null),
            commitId = CommitId("commit-$id"),
            underlyingDescription = "desc $id",
            bookmarks = bookmarks,
            tags = tags
        )

    private fun tableWith(entries: List<LogEntry>): JujutsuLogTable {
        val table = JujutsuLogTable(project.get())
        this.table = table
        Disposer.register(project.get(), table)
        table.setEntries(entries)
        table.setSize(2000, 400)
        table.doLayout()
        return table
    }

    /**
     * A point inside [row]'s bookmark/tag chip, found by scanning [table.clickTargetAt] itself
     * from the cell's right edge leftward, rather than guessing a fixed offset (as
     * [JujutsuLogTableBookmarkClickTest.bookmarkChipPoint] does - safe there only because that
     * file's assertions are soft enough that a miss still looks like a pass). Scanning against the
     * real hit-test is exact regardless of a chip's actual rendered width.
     */
    private fun chipPoint(table: JujutsuLogTable, row: Int): Point {
        val col = table.convertColumnIndexToView(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION)
        val cellRect = table.getCellRect(row, col, false)
        val y = cellRect.y + cellRect.height / 2
        for (x in (cellRect.x + cellRect.width - 1) downTo cellRect.x) {
            val point = Point(x, y)
            if (table.clickTargetAt(point).let { it is BookmarkClick || it is TagClick }) return point
        }
        error("No bookmark/tag chip found in row $row")
    }

    private fun rowStartPoint(table: JujutsuLogTable, row: Int): Point {
        val rowRect = table.getCellRect(row, 0, true)
        return Point(rowRect.x + 10, rowRect.y + 2)
    }

    // region dragPayloadAt

    @Test
    fun `dragging from a bookmark chip picks up a BookmarkRef payload`() {
        val bookmark = Bookmark("main")
        val a = entry("aaaaaaaa", bookmarks = listOf(bookmark))
        val table = tableWith(listOf(a))

        val payload = table.dragPayloadAt(chipPoint(table, 0))

        payload.shouldNotBeNull()
        payload as DragPayload.BookmarkRef
        payload.entry shouldBe a
        payload.bookmark shouldBe bookmark
    }

    @Test
    fun `dragging from a tag chip picks up a TagRef payload`() {
        val tag = Tag("v1")
        val a = entry("aaaaaaaa", tags = listOf(tag))
        val table = tableWith(listOf(a))

        val payload = table.dragPayloadAt(chipPoint(table, 0))

        payload.shouldNotBeNull()
        payload as DragPayload.TagRef
        payload.entry shouldBe a
        payload.tag shouldBe tag
    }

    @Test
    fun `dragging from plain row content picks up a Commit payload`() {
        val a = entry("aaaaaaaa")
        val table = tableWith(listOf(a))

        val payload = table.dragPayloadAt(rowStartPoint(table, 0))

        payload.shouldNotBeNull()
        payload as DragPayload.Commit
        payload.entries shouldBe listOf(a)
    }

    @Test
    fun `dragging a selection with the press on a selected row picks up the whole selection`() {
        val a = entry("aaaaaaaa")
        val b = entry("bbbbbbbb")
        val table = tableWith(listOf(a, b))
        table.selectionModel.setSelectionInterval(0, 1)

        val payload = table.dragPayloadAt(rowStartPoint(table, 1))

        payload.shouldNotBeNull()
        payload as DragPayload.Commit
        payload.entries shouldBe listOf(a, b)
    }

    // endregion

    // region dropTargetAt - chip targets

    @Test
    fun `dropping a Commit payload on a bookmark chip resolves to a RefChip target`() {
        val bookmark = Bookmark("main")
        val a = entry("aaaaaaaa")
        val b = entry("bbbbbbbb", bookmarks = listOf(bookmark))
        val table = tableWith(listOf(a, b))

        val (row, target) = table.dropTargetAt(chipPoint(table, 1), ZoneHysteresis(), DragPayload.Commit(listOf(a)))!!

        row shouldBe 1
        target.shouldNotBeNull()
        target as DropTarget.RefChip
        target.entry shouldBe b
        target.bookmark shouldBe bookmark
    }

    @Test
    fun `dropping a BookmarkRef payload on another row's bookmark chip resolves to a RefChip target`() {
        val remote = Bookmark("main@origin")
        val a = entry("aaaaaaaa", bookmarks = listOf(Bookmark("main")))
        val b = entry("bbbbbbbb", bookmarks = listOf(remote))
        val table = tableWith(listOf(a, b))
        val payload = DragPayload.BookmarkRef(a, Bookmark("main"))

        val (_, target) = table.dropTargetAt(chipPoint(table, 1), ZoneHysteresis(), payload)!!

        target.shouldNotBeNull()
        target as DropTarget.RefChip
        target.entry shouldBe b
        target.bookmark shouldBe remote
    }

    @Test
    fun `a TagRef payload never resolves a chip target, even hovering directly over one`() {
        // TagRef never pairs with a RefChip target in resolveDropOperation (only CommitRow), so
        // dropTargetAt skips the chip hit-test for it entirely and always reports CommitRow -
        // asserted here so that stays true even when the point is exactly over another row's chip.
        val a = entry("aaaaaaaa")
        val b = entry("bbbbbbbb", bookmarks = listOf(Bookmark("main")))
        val table = tableWith(listOf(a, b))
        val payload = DragPayload.TagRef(a, Tag("v1"))

        val (_, target) = table.dropTargetAt(chipPoint(table, 1), ZoneHysteresis(), payload)!!

        target.shouldNotBeNull()
        target as DropTarget.CommitRow
        target.entry shouldBe b
    }

    // endregion

    // region dropTargetAt - chip payloads never yield a Gap

    @Test
    fun `a BookmarkRef payload over an edge band still resolves to CommitRow, never a Gap`() {
        val a = entry("aaaaaaaa")
        val table = tableWith(listOf(a))
        val payload = DragPayload.BookmarkRef(a, Bookmark("main"))
        val topEdge = Point(table.getCellRect(0, 0, true).x + 10, table.getCellRect(0, 0, true).y)

        val (_, target) = table.dropTargetAt(topEdge, ZoneHysteresis(), payload)!!

        target.shouldNotBeNull()
        target as DropTarget.CommitRow
        target.entry shouldBe a
    }

    @Test
    fun `a TagRef payload over an edge band still resolves to CommitRow, never a Gap`() {
        val a = entry("aaaaaaaa")
        val table = tableWith(listOf(a))
        val payload = DragPayload.TagRef(a, Tag("v1"))
        val bottomEdge = Point(
            table.getCellRect(0, 0, true).x + 10,
            table.getCellRect(0, 0, true).y + table.rowHeight - 1
        )

        val (_, target) = table.dropTargetAt(bottomEdge, ZoneHysteresis(), payload)!!

        target.shouldNotBeNull()
        target as DropTarget.CommitRow
        target.entry shouldBe a
    }

    // endregion
}
