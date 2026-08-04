package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.ui.components.FragmentLayout
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

/**
 * Tests for [cappedDecorations] — width-budgeted bookmark/tag rendering in the log table's
 * graph+description column (jj-idea-w61m). A commit with many bookmarks must never push the
 * description out of the cell: decorations are capped to a width budget, with the rest collapsed
 * behind a clickable "+N more" chip. Hit-testing this output is covered by [LaidOutCellTest].
 */
class CappedDecorationsTest {
    private val font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val frc = FontRenderContext(AffineTransform(), true, true)
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(
        bookmarks: List<Bookmark> = emptyList(),
        tags: List<Tag> = emptyList(),
        isWorkingCopy: Boolean = false,
        description: String = "Test commit"
    ) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = description,
        bookmarks = bookmarks,
        tags = tags,
        isWorkingCopy = isWorkingCopy
    )

    private fun cap(entry: LogEntry, maxWidth: Double) = cappedDecorations(entry, Color.BLACK, maxWidth, font, frc)

    private fun widthOf(decorations: CappedDecorations) =
        decorations.canvas.fragments.sumOf { FragmentLayout.fragmentWidth(it, font, frc) }

    private fun renderedText(decorations: CappedDecorations) =
        decorations.canvas.fragments.filterIsInstance<Fragment.Text>().joinToString("") { it.text }

    @Nested
    inner class `cappedDecorations` {
        @Test
        fun `wide budget keeps all bookmarks with no overflow chip`() {
            val bookmarks = (1..5).map { Bookmark("bookmark-$it") }
            val result = cap(entry(bookmarks), 10_000.0)

            result.hidden.shouldBeEmpty()
            renderedText(result) shouldNotContain "more"
        }

        @Test
        fun `narrow budget collapses bookmarks behind a plus-N-more chip within budget`() {
            val bookmarks = (1..30).map { Bookmark("bookmark-$it") }
            val result = cap(entry(bookmarks), 100.0)

            result.hidden.shouldNotBeEmpty()
            renderedText(result) shouldContain "+${result.hidden.size} more"
            widthOf(result) shouldBeLessThanOrEqual 100.0
        }

        @Test
        fun `hidden refs are exactly the bookmarks dropped from the visible chips`() {
            val bookmarks = (1..30).map { Bookmark("bookmark-$it") }
            val result = cap(entry(bookmarks), 100.0)

            val hiddenNames = result.hidden.map { (it as BookmarkClick).bookmark.name.name }
            val visibleCount = bookmarks.size - hiddenNames.size
            hiddenNames shouldBe bookmarks.drop(visibleCount).map { it.name.name }
        }

        @Test
        fun `working copy marker is always present even when bookmarks fully collapse`() {
            val bookmarks = (1..30).map { Bookmark("bookmark-$it") }
            val result = cap(entry(bookmarks, isWorkingCopy = true), 1.0)

            renderedText(result) shouldContain "@"
        }

        @Test
        fun `tags collapse alongside bookmarks once the budget is exhausted`() {
            val bookmarks = (1..10).map { Bookmark("bookmark-$it") }
            val tags = (1..10).map { Tag("tag-$it") }
            val result = cap(entry(bookmarks, tags), 100.0)

            result.hidden.shouldNotBeEmpty()
            (result.hidden.last() is TagClick) shouldBe true
        }
    }
}
