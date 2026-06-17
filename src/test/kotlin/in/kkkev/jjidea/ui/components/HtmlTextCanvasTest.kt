package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.Tag
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.net.URLEncoder

private val ICON_TAG = Regex("<icon[^>]*>")

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
}
