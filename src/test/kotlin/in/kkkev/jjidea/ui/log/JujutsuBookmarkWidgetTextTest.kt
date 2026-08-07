package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [bookmarkWidgetText] — the bookmark widget's toolbar label, covering both the
 * "bookmark(s) on the working copy" case and the "name +N" closest-ancestor fallback
 * (jj-idea-l7wd / GitHub #62).
 */
class JujutsuBookmarkWidgetTextTest {
    @Test
    fun `no bookmarks anywhere is empty`() {
        bookmarkWidgetText(emptyList(), closest = null) shouldBe ""
    }

    @Test
    fun `bookmark on the working copy is shown as-is`() {
        bookmarkWidgetText(listOf("main"), closest = null) shouldBe "main"
    }

    @Test
    fun `multiple bookmarks on the working copy are comma-joined`() {
        bookmarkWidgetText(listOf("main", "release"), closest = null) shouldBe "main, release"
    }

    @Test
    fun `working-copy bookmarks take priority over a closest-ancestor fallback`() {
        val closest = ClosestBookmarks(listOf(BookmarkName("old")), distance = 5, distanceCapped = false)

        bookmarkWidgetText(listOf("main"), closest) shouldBe "main"
    }

    @Test
    fun `no bookmark on the working copy falls back to the closest ancestor and its distance`() {
        val closest = ClosestBookmarks(listOf(BookmarkName("main")), distance = 3, distanceCapped = false)

        bookmarkWidgetText(emptyList(), closest) shouldBe "main +3"
    }

    @Test
    fun `bookmark exactly on an ancestor shows a zero distance`() {
        val closest = ClosestBookmarks(listOf(BookmarkName("main")), distance = 0, distanceCapped = false)

        bookmarkWidgetText(emptyList(), closest) shouldBe "main +0"
    }

    @Test
    fun `multiple equidistant closest bookmarks are comma-joined before the shared distance`() {
        val closest = ClosestBookmarks(
            listOf(BookmarkName("main"), BookmarkName("release")),
            distance = 2,
            distanceCapped = false
        )

        bookmarkWidgetText(emptyList(), closest) shouldBe "main, release +2"
    }

    @Test
    fun `capped distance is marked with a trailing plus`() {
        val closest = ClosestBookmarks(listOf(BookmarkName("main")), distance = 1000, distanceCapped = true)

        bookmarkWidgetText(emptyList(), closest) shouldBe "main +1000+"
    }

    @Test
    fun `long text is truncated with an ellipsis`() {
        val names = listOf("a-very-long-bookmark-name-indeed", "another-long-one")

        val text = bookmarkWidgetText(names, closest = null)

        text.length shouldBe 30
        text.last() shouldBe '…'
    }
}
