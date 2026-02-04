package `in`.kkkev.jjidea.vcs.actions

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for bookmark list template and parsing.
 * These tests verify the template-based parsing logic for "jj bookmark list" output.
 *
 * Template format: name ++ "\0" ++ normal_target.change_id() ++ "~" ++ normal_target.change_id().shortest() ++ "\0"
 * Example output: "main\0mzmoywwptovqwlnzxvvsxknnruoypnxr~mz\0"
 */
class JujutsuCompareWithPopupTest {
    /**
     * Parse bookmark list output matching the template format
     * Simulates what LogTemplates.bookmarkListTemplate does
     */
    private fun parseBookmarks(output: String): List<BookmarkItem> {
        val fields = output.trim().split("\u0000")
        val recordSize = 2 // name + changeId
        return fields
            .chunked(recordSize)
            .filter { it.size == recordSize }
            .map { chunk ->
                val name = chunk[0]
                val changeIdWithShort = chunk[1]
                val (full, short) = changeIdWithShort.split("~")
                BookmarkItem(Bookmark(name), ChangeId(full, short))
            }
    }

    @Test
    fun `parse empty output`() {
        val result = parseBookmarks("")
        result.shouldBeEmpty()
    }

    @Test
    fun `parse single bookmark`() {
        val output = "main\u0000mzmoywwptovqwlnzxvvsxknnruoypnxr~mz\u0000"
        val result = parseBookmarks(output)

        result shouldHaveSize 1
        result[0].bookmark.name shouldBe "main"
        result[0].id.full shouldBe "mzmoywwptovqwlnzxvvsxknnruoypnxr"
        result[0].id.short shouldBe "mz"
    }

    @Test
    fun `parse multiple bookmarks`() {
        val output = "main\u0000mzmoywwp~mz\u0000feature\u0000vyrqzltx~vy\u0000bugfix\u0000qrrpnylv~qr\u0000"

        val result = parseBookmarks(output)

        result shouldHaveSize 3
        result.map { it.bookmark.name } shouldBe listOf("main", "feature", "bugfix")
        result[0].id.short shouldBe "mz"
        result[1].id.short shouldBe "vy"
        result[2].id.short shouldBe "qr"
    }

    @Test
    fun `parse bookmarks with dashes and underscores in names`() {
        val output =
            "main-branch\u0000mzmoywwp~mz\u0000" +
                "feature_v2\u0000vyrqzltx~vy\u0000" +
                "bug-fix_123\u0000qrrpnylv~qr\u0000"

        val result = parseBookmarks(output)

        result shouldHaveSize 3
        result.map { it.bookmark.name } shouldBe listOf("main-branch", "feature_v2", "bug-fix_123")
    }

    @Test
    fun `parse preserves full change ID`() {
        val output = "main\u0000mzmoywwptovqwlnzxvvsxknnruoypnxr~mz\u0000"
        val result = parseBookmarks(output)

        result shouldHaveSize 1
        result[0].id.full shouldBe "mzmoywwptovqwlnzxvvsxknnruoypnxr"
        result[0].id.short shouldBe "mz"
    }

    @Test
    fun `parse handles varying short prefix lengths`() {
        val output = "main\u0000mzmoywwptovqwlnz~mzmoywwp\u0000feature\u0000vyrqzltx~vy\u0000"

        val result = parseBookmarks(output)

        result shouldHaveSize 2
        result[0].id.short shouldBe "mzmoywwp"
        result[1].id.short shouldBe "vy"
    }
}
