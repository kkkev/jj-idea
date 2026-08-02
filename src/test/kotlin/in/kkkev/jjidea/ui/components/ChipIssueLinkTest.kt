package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for jj-idea-vrmv: an issue-tracker reference inside a bookmark/tag chip's own name (e.g. a
 * bookmark named `JIRA-123-fix-thing`) linkifies to the tracker URL, distinct from the chip's own
 * `jjref://` target used for right-click resolution - see [TextCanvas.appendChip].
 */
class ChipIssueLinkTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val jiraConfig = IssueNavigationConfiguration().apply {
        links = listOf(IssueNavigationLink("[A-Z]+-\\d+", "https://tracker/\$0"))
    }

    private fun entry(bookmarks: List<Bookmark> = emptyList(), tags: List<Tag> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        bookmarks = bookmarks,
        tags = tags
    )

    @Test
    fun `a bookmark name containing an issue reference linkifies just that substring`() {
        val e = entry(listOf(Bookmark("JIRA-123-fix-thing")))
        val canvas = FragmentRecordingCanvas()

        canvas.appendBookmarks(e, issueLinks = jiraConfig)

        val issueFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text == "JIRA-123" }
        issueFragment.linkTarget.shouldBeInstanceOf<URI>()
        issueFragment.linkTarget shouldBe URI("https://tracker/JIRA-123")
    }

    @Test
    fun `the rest of the chip keeps the bookmark's jjref target, not the inner issue link`() {
        val e = entry(listOf(Bookmark("JIRA-123-fix-thing")))
        val canvas = FragmentRecordingCanvas()

        canvas.appendBookmarks(e, issueLinks = jiraConfig)

        val suffixFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text == "-fix-thing" }
        suffixFragment.linkTarget shouldBe refUri(e, "bookmark", "JIRA-123-fix-thing")

        // The icon fragment (rendered before any text) also carries the chip-wide jjref target.
        val iconFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Icon>().first()
        iconFragment.linkTarget shouldBe refUri(e, "bookmark", "JIRA-123-fix-thing")
    }

    @Test
    fun `a bookmark name with no matching reference renders unaffected`() {
        val e = entry(listOf(Bookmark("plain-bookmark")))
        val canvas = FragmentRecordingCanvas()

        canvas.appendBookmarks(e, issueLinks = jiraConfig)

        val labelFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text == "plain-bookmark" }
        labelFragment.linkTarget shouldBe refUri(e, "bookmark", "plain-bookmark")
    }

    @Test
    fun `the linkified chip substring underlines only while its URI matches hoveredTarget`() {
        val e = entry(listOf(Bookmark("JIRA-123-fix-thing")))
        val trackerUri = URI("https://tracker/JIRA-123")
        val canvas = FragmentRecordingCanvas()

        canvas.appendBookmarks(e, issueLinks = jiraConfig, hoveredTarget = trackerUri)

        val issueFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text == "JIRA-123" }
        issueFragment.style.isUnderline shouldBe true

        val suffixFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text == "-fix-thing" }
        suffixFragment.style.isUnderline shouldBe false
    }

    @Test
    fun `a tag name containing an issue reference linkifies just that substring`() {
        val e = entry(tags = listOf(Tag("JIRA-123-fix-thing")))
        val canvas = FragmentRecordingCanvas()

        canvas.appendTags(e)

        // appendTags doesn't thread issueLinks (tooltip/uncapped path); linkification for tags goes
        // through tagDecorationUnits (the capped, interactive log-column path) instead.
        val issueFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .singleOrNull { it.text == "JIRA-123" }
        issueFragment shouldBe null
    }

    @Test
    fun `tagDecorationUnits linkifies an issue reference inside a tag name`() {
        val e = entry(tags = listOf(Tag("JIRA-123-fix-thing")))

        val units = tagDecorationUnits(e, jiraConfig)
        val canvas = FragmentRecordingCanvas().apply { units.single().build(this) }

        val issueFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text == "JIRA-123" }
        issueFragment.linkTarget shouldBe URI("https://tracker/JIRA-123")
    }
}
