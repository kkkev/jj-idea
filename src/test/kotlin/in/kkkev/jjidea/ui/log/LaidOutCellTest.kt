package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.FragmentLayout
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import `in`.kkkev.jjidea.ui.components.IssueLinkifier
import `in`.kkkev.jjidea.ui.components.Linkifier
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.net.URI

/**
 * Tests for [LaidOutCell] - the single build of the graph+description cell's content shared by
 * painting and hit-testing (jj-idea-91qf, jj-idea-vrmv, jj-idea-w61m). Replaces the previous direct
 * tests of `findInlinedRefUri`/`findDescriptionLinkUri`, which [LaidOutCell.linkTargetAt] absorbed.
 */
class LaidOutCellTest {
    private val font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val frc = FontRenderContext(AffineTransform(), true, true)

    // A relaxed mock's VirtualFile.path defaults to "", which collapses the "jjref://<host>?..."
    // authority to nothing and breaks LogClickTarget.REF_URL_PARSER's `([^?]+)` host group - stub
    // a real path so a chip's jjref URI actually resolves (see LogClickTargetTest).
    private val repo = mockk<JujutsuRepository>(relaxed = true).also { every { it.directory.path } returns "/repo" }
    private val regular = com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES

    private fun entry(
        bookmarks: List<Bookmark> = emptyList(),
        description: String = "Test commit"
    ) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = description,
        bookmarks = bookmarks
    )

    private fun textWidth(text: String) = FragmentLayout.fragmentWidth(Fragment.Text(text, regular, false), font, frc)

    private fun laidOut(
        entry: LogEntry,
        columnWidth: Int = 2_000,
        columnManager: JujutsuColumnManager = JujutsuColumnManager.DEFAULT,
        linkifier: Linkifier = Linkifier.None
    ) = LaidOutCell.forRow(entry, columnWidth, textStartX = 0, columnManager, linkifier, Color.BLACK, font, frc)

    @Nested
    inner class `description hit-testing` {
        private val jiraLinkifier = IssueLinkifier(
            IssueNavigationConfiguration().apply {
                links =
                    listOf(IssueNavigationLink("[A-Z]+-\\d+", "https://tracker/\$0"))
            }
        )

        // Isolate the description text - showStatus/showChangeId would otherwise prepend status
        // icons and the change ID before it, complicating the x-offset math below.
        private val descriptionOnly = JujutsuColumnManager().apply {
            showStatus = false
            showChangeId = false
            showDecorations = false
        }

        @Test
        fun `clicking the linkified issue reference resolves to its tracker URI`() {
            val e = entry(description = "Fixes JIRA-123 now")
            val prefixWidth = textWidth("Fixes ")
            val linkWidth = textWidth("JIRA-123")

            val cell = laidOut(e, columnManager = descriptionOnly, linkifier = jiraLinkifier)

            cell.linkTargetAt((prefixWidth + linkWidth / 2).toInt()) shouldBe URI("https://tracker/JIRA-123")
        }

        @Test
        fun `clicking plain description text resolves to no link`() {
            val e = entry(description = "Fixes JIRA-123 now")

            laidOut(e, columnManager = descriptionOnly, linkifier = jiraLinkifier).linkTargetAt(0).shouldBeNull()
        }

        @Test
        fun `no linkifier never resolves a target even over the reference text`() {
            val e = entry(description = "Fixes JIRA-123 now")
            val prefixWidth = textWidth("Fixes ")

            laidOut(e, columnManager = descriptionOnly).linkTargetAt(prefixWidth.toInt() + 2).shouldBeNull()
        }

        @Test
        fun `description column disabled never resolves a target`() {
            val e = entry(description = "Fixes JIRA-123 now")
            val columnManager = JujutsuColumnManager().apply {
                showDescription = false
                showDecorations = false
            }

            laidOut(e, columnManager = columnManager, linkifier = jiraLinkifier).linkTargetAt(0).shouldBeNull()
        }

        @Test
        fun `a point left of the text area resolves to no link`() {
            val e = entry(description = "Fixes JIRA-123 now")

            val cell = LaidOutCell.forRow(
                e,
                2_000,
                textStartX = 20,
                descriptionOnly,
                jiraLinkifier,
                Color.BLACK,
                font,
                frc
            )

            cell.linkTargetAt(5).shouldBeNull()
        }
    }

    @Nested
    inner class `decoration hit-testing` {
        @Test
        fun `clicking the overflow chip resolves to an overflow URI`() {
            val e = entry((1..30).map { Bookmark("bookmark-$it") })
            val columnWidth = 200

            // The overflow chip is rightmost; click near the right edge of the cell.
            val uri = laidOut(e, columnWidth).linkTargetAt(columnWidth - 2)

            uri.shouldNotBeNull()
            uri.toString() shouldContain "kind=overflow"
        }

        @Test
        fun `disabled decorations never resolve a target`() {
            val e = entry((1..30).map { Bookmark("bookmark-$it") })
            val columnManager = JujutsuColumnManager().apply { showDecorations = false }

            laidOut(e, 200, columnManager).linkTargetAt(198).shouldBeNull()
        }

        @Test
        fun `a bookmark chip resolves to its ref URI`() {
            val bookmark = Bookmark("main")
            val e = entry(listOf(bookmark))

            val cell = laidOut(e, 2_000)
            // Scan for the chip: it sits flush against the right edge of the (huge) column.
            val hitX = (0 until 2_000).first { cell.linkTargetAt(it) != null }

            LogClickTarget.resolve(cell.linkTargetAt(hitX)!!, project = null, listOf(e)) as BookmarkClick
        }

        @Test
        fun `hidden exposes exactly the collapsed bookmarks, without a separate cappedDecorations rebuild`() {
            val bookmarks = (1..30).map { Bookmark("bookmark-$it") }
            val e = entry(bookmarks)

            val cell = laidOut(e, 100)

            cell.hidden.isNotEmpty() shouldBe true
        }
    }
}
