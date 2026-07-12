package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Bookmark
import io.kotest.matchers.maps.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * Tests for [selectableBookmarkNames], the pure grouping logic behind the References/Bookmarks
 * filter popup (jj-idea-iadu): it must surface remote-only bookmarks alongside local ones while
 * collapsing a local bookmark and its synced tracked remote into a single entry.
 */
class JujutsuReferenceFilterComponentTest {
    @Test
    fun `local bookmark is selectable and not flagged remote-only`() {
        selectableBookmarkNames(listOf(Bookmark("main"))) shouldContainExactly mapOf("main" to false)
    }

    @Test
    fun `remote-only bookmark is selectable and flagged remote-only`() {
        selectableBookmarkNames(listOf(Bookmark("feature@origin"))) shouldContainExactly
            mapOf("feature@origin" to true)
    }

    @Test
    fun `local bookmark and its synced tracked remote collapse to one local entry`() {
        val bookmarks = listOf(Bookmark("main"), Bookmark("main@origin", tracked = true))

        selectableBookmarkNames(bookmarks) shouldContainExactly mapOf("main" to false)
    }

    @Test
    fun `mix of local and remote-only bookmarks are all selectable`() {
        val bookmarks = listOf(
            Bookmark("main"),
            Bookmark("main@origin"),
            Bookmark("feature@origin"),
            Bookmark("other@github")
        )

        selectableBookmarkNames(bookmarks) shouldContainExactly mapOf(
            "main" to false,
            "feature@origin" to true,
            "other@github" to true
        )
    }

    @Test
    fun `deleted bookmarks are excluded`() {
        selectableBookmarkNames(listOf(Bookmark("main", deleted = true))) shouldContainExactly emptyMap()
    }
}
