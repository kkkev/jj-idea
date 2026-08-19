package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.URLUtil
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URLEncoder

// <img>, not <icon>: see jj-idea-vll4/jj-idea-m2wr — <icon> isn't a genuine HTML void element, so on IntelliJ
// 2026.2 it round-trips through JBHtmlPane's Jsoup transpiler as an open/close pair, splitting one chip into two
// elements. <img> is void to both Jsoup and Swing's parser, so chips are encoded as <img> instead.
private val ICON_TAG = Regex("<img[^>]*>")
private val HREF = Regex("href='([^']*)'")

/**
 * Regression tests for jj-idea-kds1: bookmark/tag chips must render as a single atomic `<img>` element (resolved
 * by [AtomicHtmlExtension] into one [AtomicHtmlView]) so HTML line-wrapping can never split the icon from its
 * label, nor break the label mid-word.
 */
class HtmlTextCanvasTest {
    @Test
    fun `bookmark chip renders as a single atomic icon element`() {
        val html = htmlString { append(Bookmark("hotfix/issue-123")) }

        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        iconTags[0].value shouldContain UNBREAKABLE_PREFIX
        iconTags[0].value shouldContain URLEncoder.encode("hotfix/issue-123", "UTF-8")
    }

    @Test
    fun `tag chip renders as a single atomic icon element`() {
        val html = htmlString { append(Tag("v1.0")) }

        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        iconTags[0].value shouldContain UNBREAKABLE_PREFIX
        iconTags[0].value shouldContain URLEncoder.encode("v1.0", "UTF-8")
    }

    @Test
    fun `bookmark name renders as a single atomic icon element`() {
        val html = htmlString { append(BookmarkName("main")) }

        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        iconTags[0].value shouldContain UNBREAKABLE_PREFIX
        iconTags[0].value shouldContain "main"
    }

    @Test
    fun `divergent bookmark chip encodes the ahead-behind suffix in the same atomic element`() {
        val html = htmlString { append(Bookmark("main", aheadCount = 2, behindCount = 1)) }

        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        val src = Regex("src='([^']*)'").find(iconTags[0].value)!!.groupValues[1]
        UnbreakableContent.decode(src.removePrefix(UNBREAKABLE_PREFIX)) shouldContain "↑2↓1"
    }

    @Test
    fun `separators between bookmark chips remain non-breaking spaces`() {
        val html = htmlString {
            append(Bookmark("main"))
            space()
            append(Bookmark("feature/long-name"))
        }

        html shouldContain "&nbsp;"
        ICON_TAG.findAll(html).toList() shouldHaveSize 2
    }

    /**
     * Regression test for jj-idea-myje / GitHub #77: [TextCanvas.space] is the one place that's
     * still allowed to produce a non-breaking space (it's for a deliberate gap next to an icon or
     * chip, not real text), and it must still do so - guards against the gap silently
     * re-collapsing if [Formatters.escapeHtml] or [HtmlTextCanvas.space] regresses.
     */
    @Test
    fun `space() next to an icon still renders as a non-breaking space`() {
        val html = htmlString {
            append(icon(JujutsuIcons::Repo))
            space()
            append("main")
        }

        html shouldContain "&nbsp;"
    }

    /**
     * Regression test for jj-idea-myje / GitHub #77: a description's own inter-word spaces must
     * stay as ordinary breakable spaces, not become non-breaking `&nbsp;` (which broke line
     * wrapping and corrupted copy/paste, since the selected text then contained literal U+00A0
     * characters instead of the description as typed).
     */
    @Test
    fun `description text keeps regular breakable spaces`() {
        val html = htmlString { append(Description("fix the thing that was broken")) }

        html shouldContain "fix the thing that was broken"
        html shouldNotContain "&nbsp;"
    }

    /**
     * Regression tests for jj-idea-44jr / GitHub #39: a workspace path containing a space
     * (e.g. `.../untitled untitled`) must not throw `URISyntaxException` when building the
     * `jjc://` / `jjref://` link URIs, and the encoded path must round-trip back to the
     * original via [URLUtil.unescapePercentSequences] (the inverse used by
     * [in.kkkev.jjidea.ui.components.IconAwareHtmlPane]).
     */
    private val spacedPath = "/tmp/untitled untitled"

    private fun repoWithSpacedPath(): JujutsuRepository {
        val dir = mockk<VirtualFile>()
        every { dir.path } returns spacedPath
        val repo = mockk<JujutsuRepository>()
        every { repo.directory } returns dir
        return repo
    }

    @Test
    fun `change link with a space in the repository path does not throw and round-trips`() {
        val repo = repoWithSpacedPath()
        val changeKey = ChangeKey(repo, ChangeId("qpvuntsmxyz", "qp"))

        val html = htmlString { append(changeKey) }

        val href = HREF.find(html)!!.groupValues[1]
        href shouldContain "%20"
        val encodedPath = href.removePrefix("jjc://").substringBefore("?")
        URLUtil.unescapePercentSequences(encodedPath) shouldBe spacedPath
    }

    @Test
    fun `bookmark ref link with a space in the repository path does not throw and round-trips`() {
        val repo = repoWithSpacedPath()
        val entry = LogEntry(
            repo = repo,
            id = ChangeId("qpvuntsmxyz", "qp"),
            commitId = CommitId("abc123"),
            underlyingDescription = "",
            bookmarks = listOf(Bookmark("main"))
        )

        val html = htmlString { appendBookmarks(entry) }

        val href = HREF.find(html)!!.groupValues[1]
        href shouldContain "%20"
        val encodedPath = href.removePrefix("jjref://").substringBefore("?")
        URLUtil.unescapePercentSequences(encodedPath) shouldBe spacedPath
    }

    /** Regression tests for jj-idea-c6f5 / GitHub #51: the commit details panel shows the user's email. */
    @Test
    fun `appendWithEmail renders name and email as a single clickable unbreakable chip`() {
        val html = htmlString { appendWithEmail(VcsUserImpl("Alice", "alice@example.com")) }

        html shouldContain "mailto:alice@example.com"
        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        val src = Regex("src='([^']*)'").find(iconTags[0].value)!!.groupValues[1]
        src shouldContain UNBREAKABLE_PREFIX
        UnbreakableContent.decode(src.removePrefix(UNBREAKABLE_PREFIX)) shouldContain "Alice"
        UnbreakableContent.decode(src.removePrefix(UNBREAKABLE_PREFIX)) shouldContain "alice@example.com"
    }

    @Test
    fun `appendWithEmail falls back to the plain name when email is missing`() {
        val html = htmlString { appendWithEmail(VcsUserImpl("John", "")) }

        html shouldContain "John"
        html shouldNotContain "mailto:"
        html shouldNotContain "<>"
    }

    /**
     * Regression test for jj-idea-c6f5: a `white-space: nowrap` CSS span does not stop Swing's HTMLEditorKit from
     * force-breaking text mid-word when it doesn't fit the line. [TextCanvas.appendUnbreakable] must instead render
     * as a single atomic leaf (the [AtomicHtmlExtension] mechanism, jj-idea-kds1) with no separate breakable text
     * run for the surrounding layout to split.
     */
    @Test
    fun `appendUnbreakable renders as a single atomic icon element with no icon`() {
        val html = htmlString { appendUnbreakable("· 12/07/2026, 04:07") }

        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        val src = Regex("src='([^']*)'").find(iconTags[0].value)!!.groupValues[1]
        src shouldContain UNBREAKABLE_PREFIX
        UnbreakableContent.decode(src.removePrefix(UNBREAKABLE_PREFIX)) shouldContain "12/07/2026"
    }
}
