package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import javax.swing.text.Element

/**
 * Tests for jj-idea-vrmv follow-up: a bookmark chip's linkified issue-tracker substring must be
 * clickable/right-clickable in the commit-details HTML pane, not just the log table's fragment
 * backend (already covered by [ChipIssueLinkTest]) - the user explicitly asked for this after
 * noticing bookmarks in the detail pane were not clickable at all.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class IconAwareHtmlPaneChipIssueLinkTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val jiraConfig = IssueNavigationConfiguration().apply {
        links = listOf(IssueNavigationLink("[A-Z]+-\\d+", "https://tracker/\$0"))
    }

    private fun entry(bookmarks: List<Bookmark>) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        bookmarks = bookmarks
    )

    private fun collectImgElements(root: Element): List<Element> {
        val result = mutableListOf<Element>()
        fun collect(e: Element) {
            if (e.name == "img") result.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(root)
        return result
    }

    private fun renderBookmarkChip(): IconAwareHtmlPane {
        val e = entry(listOf(Bookmark("JIRA-123-fix-thing")))
        val html = htmlString(linkifier = IssueLinkifier(jiraConfig)) {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") { appendBookmarks(e) }
        }
        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()
        return pane
    }

    /**
     * [ChipView.modelToView] returns a zero-width rectangle at [Element.getStartOffset]/
     * [Element.getEndOffset] (by design - it's a single atomic leaf, see [ChipView]'s own
     * `modelToView` doc), so the chip's real pixel span has to be read from those two positions'
     * x-coordinates rather than from either rectangle's `width` alone.
     */
    private fun chipSpanOf(pane: IconAwareHtmlPane, chip: Element): IntRange {
        val left = pane.modelToView2D(chip.startOffset).bounds
        val right = pane.modelToView2D(chip.endOffset).bounds
        return left.x until right.x
    }

    @Test
    fun `hovering the linkified issue-tracker prefix of a bookmark chip resolves its tracker URL`() {
        val pane = renderBookmarkChip()
        val chip = collectImgElements(pane.document.defaultRootElement).single()
        val span = chipSpanOf(pane, chip)
        val y = pane.modelToView2D(chip.startOffset).bounds.centerY.toInt()

        // Scan across the chip's real pixel span looking for a point that resolves to the
        // "JIRA-123" prefix's tracker URL - the icon's exact width isn't known here.
        val found = span.asSequence()
            .map { x -> pane.issueLinkUriAt(java.awt.Point(x, y)) }
            .firstOrNull { it != null }
        found shouldBe URI("https://tracker/JIRA-123")
    }

    @Test
    fun `hovering the plain suffix of the same bookmark chip resolves no issue link`() {
        val pane = renderBookmarkChip()
        val chip = collectImgElements(pane.document.defaultRootElement).single()
        val span = chipSpanOf(pane, chip)
        val y = pane.modelToView2D(chip.startOffset).bounds.centerY.toInt()

        // A point right at the chip's trailing edge, inside the "-fix-thing" suffix.
        val point = java.awt.Point(span.last - 1, y)
        pane.issueLinkUriAt(point).shouldBeNull()
    }

    @Test
    fun `a bookmark chip with no matching issue reference resolves no issue link anywhere`() {
        val e = entry(listOf(Bookmark("plain-bookmark")))
        val html = htmlString(linkifier = IssueLinkifier(jiraConfig)) {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") { appendBookmarks(e) }
        }
        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val chip = collectImgElements(pane.document.defaultRootElement).single()
        val span = chipSpanOf(pane, chip)
        val y = pane.modelToView2D(chip.startOffset).bounds.centerY.toInt()

        span.forEach { x -> pane.issueLinkUriAt(java.awt.Point(x, y)).shouldBeNull() }
    }
}
