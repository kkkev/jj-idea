package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Regression test for jj-idea-fmrj: [revisionChoiceTooltip] renders bookmark/tag chips as an
 * atomic `<img src='chip:…'>` element (see [HtmlTextCanvasTest]), so it must only ever be shown
 * via a chip-aware pane (installed on [RevisionChoicePanel]'s list by [installIconAwareTooltip]).
 * A plain Swing tooltip doesn't know the `chip:` URL scheme and paints a broken-image glyph
 * instead.
 */
class RevisionChoiceTooltipTest {
    @Test
    fun `Ref tooltip for a bookmark carries a chip img element`() {
        val item = RevisionChoice.Ref(BookmarkItem(Bookmark("main"), ChangeId("aalong", "aa", 0)))

        val html = revisionChoiceTooltip(item)

        html.shouldNotBeNull()
        html shouldContain "<img"
        html shouldContain CHIP_ICON_PREFIX
    }

    @Test
    fun `FreeForm has no tooltip`() {
        revisionChoiceTooltip(RevisionChoice.FreeForm("zkptqxyz")) shouldBe null
    }
}
