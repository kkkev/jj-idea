package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI

/** Unit tests for [Linkifier]/[IssueLinkifier] - the single module responsible for turning text into links. */
class LinkifierTest {
    private fun configWith(issueRegexp: String, linkRegexp: String) =
        IssueNavigationConfiguration().apply { links = listOf(IssueNavigationLink(issueRegexp, linkRegexp)) }

    private val jiraLinkifier = IssueLinkifier(configWith("[A-Z]+-\\d+", "https://tracker/\$0"))

    @Test
    fun `text with no match returns a single plain run`() {
        val runs = jiraLinkifier.linkify("no reference here")

        runs shouldBe listOf(TextRun.Plain("no reference here"))
    }

    @Test
    fun `text with a match splits into plain and link runs`() {
        val runs = jiraLinkifier.linkify("Fixes JIRA-123 now")

        runs shouldBe listOf(
            TextRun.Plain("Fixes "),
            TextRun.Link("JIRA-123", URI("https://tracker/JIRA-123")),
            TextRun.Plain(" now")
        )
    }

    @Test
    fun `a malformed link target falls back to a plain run instead of throwing`() {
        val badLinkifier = IssueLinkifier(configWith("[A-Z]+-\\d+", "not a uri \$0"))

        val runs = badLinkifier.linkify("See JIRA-123 please")

        runs.filterIsInstance<TextRun.Link>() shouldBe emptyList()
        runs.joinToString("") { it.text } shouldBe "See JIRA-123 please"
    }

    @Test
    fun `Linkifier None always returns the text as one plain run`() {
        val runs = Linkifier.None.linkify("Fixes JIRA-123 now")

        runs shouldBe listOf(TextRun.Plain("Fixes JIRA-123 now"))
    }

    @Test
    fun `target extension returns null for a plain run and the target for a link run`() {
        val plain: TextRun = TextRun.Plain("x")
        val link: TextRun = TextRun.Link("x", URI("https://example.com"))

        plain.target shouldBe null
        link.target.shouldBeInstanceOf<URI>()
        link.target shouldBe URI("https://example.com")
    }
}
