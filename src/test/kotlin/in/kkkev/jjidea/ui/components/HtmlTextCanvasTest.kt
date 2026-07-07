package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.URLUtil
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.net.URLEncoder

private val ICON_TAG = Regex("<icon[^>]*>")
private val HREF = Regex("href='([^']*)'")

/**
 * Regression tests for jj-idea-kds1: bookmark/tag chips must render as a single atomic `<icon>` element (resolved by
 * [ChipIconExtension] into one [ChipView]) so HTML line-wrapping can never split the icon from its label, nor break
 * the label mid-word.
 */
class HtmlTextCanvasTest {
    @Test
    fun `bookmark chip renders as a single atomic icon element`() {
        val html = htmlString { append(Bookmark("hotfix/issue-123")) }

        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        iconTags[0].value shouldContain CHIP_ICON_PREFIX
        iconTags[0].value shouldContain URLEncoder.encode("hotfix/issue-123", "UTF-8")
    }

    @Test
    fun `tag chip renders as a single atomic icon element`() {
        val html = htmlString { append(Tag("v1.0")) }

        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        iconTags[0].value shouldContain CHIP_ICON_PREFIX
        iconTags[0].value shouldContain URLEncoder.encode("v1.0", "UTF-8")
    }

    @Test
    fun `bookmark name renders as a single atomic icon element`() {
        val html = htmlString { append(BookmarkName("main")) }

        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        iconTags[0].value shouldContain CHIP_ICON_PREFIX
        iconTags[0].value shouldContain "main"
    }

    @Test
    fun `divergent bookmark chip encodes the ahead-behind suffix in the same atomic element`() {
        val html = htmlString { append(Bookmark("main", aheadCount = 2, behindCount = 1)) }

        val iconTags = ICON_TAG.findAll(html).toList()
        iconTags shouldHaveSize 1
        val src = Regex("src='([^']*)'").find(iconTags[0].value)!!.groupValues[1]
        val fields = src.removePrefix(CHIP_ICON_PREFIX).split(";")
        URLDecoder.decode(fields[4], "UTF-8") shouldBe "↑2↓1"
    }

    @Test
    fun `separators between bookmark chips remain non-breaking spaces`() {
        val html = htmlString {
            append(Bookmark("main"))
            append(" ")
            append(Bookmark("feature/long-name"))
        }

        html shouldContain "&nbsp;"
        ICON_TAG.findAll(html).toList() shouldHaveSize 2
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
}
