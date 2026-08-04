package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import `in`.kkkev.jjidea.jj.Description
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for jj-idea-10fo: rendering issue-tracker references (e.g. `JIRA-123`) as clickable links, resolved via
 * [IssueLinkifier]. Links are emitted through [TextCanvas.linked] rather than raw HTML injection, so they carry
 * over into [FragmentRecordingCanvas] (the log-column backend) as well as the HTML details pane — see
 * `appendLinkified` in `TextCanvas.kt`. The [Linkifier]/hover state are injected once per canvas (constructor for
 * [FragmentRecordingCanvas], the `linkifier` param for [htmlString]) rather than passed to each append call - see
 * [TextCanvas.linkifier]/[TextCanvas.hoveredTarget].
 */
class IssueLinkRenderingTest {
    private fun configWith(issueRegexp: String, linkRegexp: String) =
        IssueNavigationConfiguration().apply { links = listOf(IssueNavigationLink(issueRegexp, linkRegexp)) }

    private val jiraConfig = configWith("[A-Z]+-\\d+", "https://tracker/\$0")

    @Test
    fun `matching issue reference renders as a link in HTML output`() {
        val html = htmlString(linkifier = IssueLinkifier(jiraConfig)) { append(Description("Fixes JIRA-123 now")) }

        // Wrapped in a link-color span (jj-idea-iesq): linked() now emits an explicit color, since
        // the HTML backend has no other way to apply link styling to arbitrary linked content.
        html shouldContain "<a href='https://tracker/JIRA-123'>"
        html shouldContain "JIRA-123</span></a>"
        html shouldContain "Fixes"
        html shouldContain "now"
    }

    @Test
    fun `summary variant also linkifies the first line only`() {
        val html =
            htmlString(linkifier = IssueLinkifier(jiraConfig)) {
                appendSummary(Description("Fixes JIRA-123\nsecond line"))
            }

        html shouldContain "<a href='https://tracker/JIRA-123'>"
        html shouldContain "JIRA-123</span></a>"
        html shouldNotContain "second line"
    }

    @Test
    fun `no config leaves rendering unchanged`() {
        val html = htmlString { append(Description("Fixes JIRA-123 now")) }

        html shouldNotContain "<a href"
        html shouldContain "JIRA-123"
    }

    @Test
    fun `config with no matches leaves rendering unchanged`() {
        val noMatchConfig = configWith("NOPE-\\d+", "https://tracker/\$0")
        val html = htmlString(linkifier = IssueLinkifier(noMatchConfig)) { append(Description("Fixes JIRA-123 now")) }

        html shouldNotContain "<a href"
        html shouldContain "JIRA-123"
    }

    @Test
    fun `malformed link target falls back to plain text instead of throwing`() {
        // A link regexp producing an invalid URI (unescaped space + control-ish chars) must not throw.
        val badConfig = configWith("[A-Z]+-\\d+", "not a uri \$0")

        val html = htmlString(linkifier = IssueLinkifier(badConfig)) { append(Description("See JIRA-123 please")) }

        html shouldNotContain "<a href"
        html shouldContain "JIRA-123"
    }

    @Test
    fun `linkified issue reference carries the URI as a fragment link target for column rendering (jj-idea-iesq)`() {
        val canvas = FragmentRecordingCanvas(linkifier = IssueLinkifier(jiraConfig))
        canvas.append(Description("See JIRA-123 please"))

        val linked = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .filter { it.text == "JIRA-123" }
        linked shouldHaveSize 1
        linked[0].linkTarget.shouldBeInstanceOf<URI>()
        linked[0].linkTarget shouldBe URI("https://tracker/JIRA-123")
    }

    @Test
    fun `the linkified fragment underlines only while its URI matches hoveredTarget (jj-idea-91qf)`() {
        val trackerUri = URI("https://tracker/JIRA-123")
        val canvas = FragmentRecordingCanvas(linkifier = IssueLinkifier(jiraConfig), hoveredTarget = trackerUri)
        canvas.append(Description("See JIRA-123 please"))

        val linkFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text == "JIRA-123" }
        linkFragment.style.isUnderline shouldBe true

        val plainFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text.contains("See") }
        plainFragment.style.isUnderline shouldBe false
    }

    @Test
    fun `a non-matching hoveredTarget leaves the link fragment plain`() {
        val canvas =
            FragmentRecordingCanvas(
                linkifier = IssueLinkifier(jiraConfig),
                hoveredTarget = URI("https://tracker/OTHER-1")
            )
        canvas.append(Description("See JIRA-123 please"))

        val linkFragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text == "JIRA-123" }
        linkFragment.style.isUnderline shouldBe false
    }
}
