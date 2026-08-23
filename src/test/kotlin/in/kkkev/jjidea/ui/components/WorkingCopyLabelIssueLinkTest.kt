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

/**
 * Tests for jj-idea-n553: the "Working copy" tool window's current-change label
 * ([in.kkkev.jjidea.ui.workingcopy.WorkingCopyControlsPanel.updateWorkingCopyLabel]) renders via
 * `htmlString(linkifier = ...) { appendSummary(entry); appendParents(entry) }` into an
 * [IconAwareHtmlPane] - the same composition and pane type exercised directly here, since building
 * the real Swing panel needs a project. Mirrors [IconAwareHtmlPaneChipIssueLinkTest] (jj-idea-vrmv),
 * which established that a chip's linkified substring must be probed via
 * [IconAwareHtmlPane.issueLinkUriAt] rather than raw HTML string matching, since the chip is an
 * `<img src='unbreakable:...'>` element with the link baked into its encoded content.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class WorkingCopyLabelIssueLinkTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val jiraConfig = IssueNavigationConfiguration().apply {
        links = listOf(IssueNavigationLink("[A-Z]+-\\d+", "https://tracker/\$0"))
    }

    private fun entry(bookmarks: List<Bookmark> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        bookmarks = bookmarks
    )

    private fun collectImgElements(root: javax.swing.text.Element): List<javax.swing.text.Element> {
        val result = mutableListOf<javax.swing.text.Element>()
        fun collect(e: javax.swing.text.Element) {
            if (e.name == "img") result.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(root)
        return result
    }

    private fun renderWorkingCopyLabel(e: LogEntry): IconAwareHtmlPane {
        val html = htmlString(linkifier = IssueLinkifier(jiraConfig)) {
            appendSummary(e)
            appendParents(e)
        }
        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()
        return pane
    }

    @Test
    fun `hovering the linkified issue-tracker prefix of a working copy label bookmark chip resolves its tracker URL`() {
        val pane = renderWorkingCopyLabel(entry(bookmarks = listOf(Bookmark("JIRA-123-fix-thing"))))
        val chip = collectImgElements(pane.document.defaultRootElement).single()
        val left = pane.modelToView2D(chip.startOffset).bounds
        val right = pane.modelToView2D(chip.endOffset).bounds
        val span = left.x until right.x
        val y = left.centerY.toInt()

        val found = span.asSequence()
            .map { x -> pane.issueLinkUriAt(java.awt.Point(x, y)) }
            .firstOrNull { it != null }
        found shouldBe URI("https://tracker/JIRA-123")
    }

    @Test
    fun `a bookmark chip with no matching issue reference resolves no issue link in the working copy label`() {
        val pane = renderWorkingCopyLabel(entry(bookmarks = listOf(Bookmark("plain-bookmark"))))
        val chip = collectImgElements(pane.document.defaultRootElement).single()
        val left = pane.modelToView2D(chip.startOffset).bounds
        val right = pane.modelToView2D(chip.endOffset).bounds
        val span = left.x until right.x
        val y = left.centerY.toInt()

        span.forEach { x -> pane.issueLinkUriAt(java.awt.Point(x, y)).shouldBeNull() }
    }
}
