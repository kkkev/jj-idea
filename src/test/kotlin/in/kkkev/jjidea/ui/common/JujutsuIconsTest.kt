package `in`.kkkev.jjidea.ui.common

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * jj-idea-rskx: the bookmark and tag "add"-badge icon family. Two things nothing else catches: a
 * malformed SVG or a bad `/icons/...` resource path (would fail lazily, only once something
 * paints the icon), and a colored action icon creeping back in - IntelliJ's New UI draws action
 * icons in a single neutral (see `platform/icons/src/expui/general/add.svg`), so the badged
 * create/delete/forget icons must stay monochrome while the conflict *state* icon stays red.
 */
class JujutsuIconsTest {
    @Test
    fun `all bookmark and tag icons load with a positive size`() {
        listOf(
            JujutsuIcons.Bookmark,
            JujutsuIcons.BookmarkTracked,
            JujutsuIcons.BookmarkAction,
            JujutsuIcons.BookmarkTrackedAction,
            JujutsuIcons.BookmarkAdd,
            JujutsuIcons.BookmarkDelete,
            JujutsuIcons.BookmarkForget,
            JujutsuIcons.BookmarkDeleted,
            JujutsuIcons.BookmarkConflict,
            JujutsuIcons.Tag,
            JujutsuIcons.TagAdd
        ).forEach { icon ->
            (icon.iconWidth > 0) shouldBe true
            (icon.iconHeight > 0) shouldBe true
        }
    }

    @Test
    fun `action icons carry no color outside the accent class`() {
        // A literal hex color anywhere but inside a <style> block's rule would mean the SVG
        // hardcodes a color that recoloring can't touch, silently breaking theme-awareness and
        // (for these) the monochrome-action convention.
        val hexColor = Regex("""#[0-9a-fA-F]{3,6}""")
        listOf("bookmarkAdd.svg", "bookmarkDelete.svg", "bookmarkForget.svg", "tagAdd.svg").forEach { file ->
            val svg = javaClass.getResourceAsStream("/icons/$file")!!.readBytes().decodeToString()
            val style = Regex("""<style>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(svg)!!.groupValues[1]
            val outsideStyle = svg.replace(style, "")
            hexColor.findAll(outsideStyle).map { it.value }.toList().shouldBeEmpty()
        }
    }
}
