package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.IssueLinkifier
import `in`.kkkev.jjidea.ui.components.Linkifier
import `in`.kkkev.jjidea.ui.components.TextRun
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

/**
 * Operation-count regression test for jj-idea-alew (CLAUDE.md § Performance & Scale): before
 * [LaidOutCell], the graph+description column rebuilt `entryCanvas` ~2x and `cappedDecorations` ~4x
 * per row-render (once per hover-target lookup, plus once for the real paint) - each rebuild
 * re-walked and re-linkified the description and every chip label. [LaidOutCell.forRow] now builds
 * both exactly once; querying the pointer position afterwards ([LaidOutCell.linkTargetAt], used by
 * both painting and click resolution) is a pure fragment-width walk that never re-linkifies.
 *
 * Complexity: building is O(description length + decorations); [LaidOutCell.linkTargetAt] is
 * O(fragments) per call, with zero additional linkification regardless of how many times it's
 * called - unlike the pre-refactor `find*` functions, which each re-built (and re-linkified) their
 * own canvas from scratch.
 */
class LaidOutCellScaleTest {
    private val font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val frc = FontRenderContext(AffineTransform(), true, true)
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val jiraLinkifier = IssueLinkifier(
        IssueNavigationConfiguration().apply {
            links = listOf(IssueNavigationLink("[A-Z]+-\\d+", "https://tracker/\$0"))
        }
    )

    private class CountingLinkifier(private val delegate: Linkifier) : Linkifier {
        var calls = 0
            private set

        override fun linkify(text: String): List<TextRun> {
            calls++
            return delegate.linkify(text)
        }
    }

    private fun entry(bookmarks: List<Bookmark> = emptyList(), description: String = "Test commit") = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = description,
        bookmarks = bookmarks
    )

    @Test
    fun `forRow linkifies each piece of content exactly once, regardless of decoration count`() {
        val bookmarks = (1..20).map { Bookmark("bookmark-$it") }
        val e = entry(bookmarks, description = "Fixes JIRA-123 now")
        val counting = CountingLinkifier(jiraLinkifier)

        // A wide enough column that every bookmark chip fits (none collapse behind "+N more" -
        // a collapsed chip's label is never built/linkified at all, so it wouldn't count here).
        val cell = LaidOutCell.forRow(e, 20_000, 0, JujutsuColumnManager.DEFAULT, counting, Color.BLACK, font, frc)
        cell.hidden shouldBe emptyList()

        // One call for the description, one per bookmark label - not multiplied by any rebuild
        // factor, unlike the pre-refactor find*/hovered*Uri functions each independently rebuilding
        // entryCanvas/cappedDecorations.
        counting.calls shouldBe 1 + bookmarks.size
    }

    @Test
    fun `linkTargetAt does no further linkification, no matter how many times it's queried`() {
        val e = entry(description = "Fixes JIRA-123 now")
        val counting = CountingLinkifier(jiraLinkifier)
        val cell = LaidOutCell.forRow(e, 2_000, 0, JujutsuColumnManager.DEFAULT, counting, Color.BLACK, font, frc)
        val callsAfterBuild = counting.calls

        // Simulate many mouse-move hit-tests against the one built cell, as both the renderer's
        // hover lookup and JujutsuLogTable.clickTargetAt now do.
        repeat(50) { x -> cell.linkTargetAt(x) }

        counting.calls shouldBe callsAfterBuild
    }
}
