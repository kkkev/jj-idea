package `in`.kkkev.jjidea.vcs.actions

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for bookmark list parsing in JujutsuCompareWithPopup.
 * These tests verify the inline parsing logic for "jj bookmark list" output.
 */
class JujutsuCompareWithPopupTest {

    /**
     * Simulates the bookmark parsing logic from JujutsuCompareWithPopup.buildItemList()
     * Lines 95-108 in JujutsuCompareWithPopup.kt
     */
    private fun parseBookmarks(output: String): List<Pair<String, String>> {
        val bookmarkLines = output.lines().filter { it.isNotBlank() && it.contains(':') }
        return bookmarkLines.mapNotNull { line ->
            // Format: "bookmark-name: change-id"
            val parts = line.split(':', limit = 2)
            if (parts.size == 2) {
                val bookmarkName = parts[0].trim()
                val changeId = parts[1].trim().take(12)
                if (bookmarkName.isNotEmpty()) {
                    bookmarkName to changeId
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    @Test
    fun `parse empty output`() {
        val result = parseBookmarks("")
        result.shouldBeEmpty()
    }

    @Test
    fun `parse blank lines only`() {
        val result = parseBookmarks("\n\n\n")
        result.shouldBeEmpty()
    }

    @Test
    fun `parse single bookmark`() {
        val output = "main: abc123def456"
        val result = parseBookmarks(output)

        result shouldHaveSize 1
        result[0].first shouldBe "main"
        result[0].second shouldBe "abc123def456"
    }

    @Test
    fun `parse multiple bookmarks`() {
        val output = """
            main: abc123def456
            feature: def456abc123
            bugfix: 123456789abc
        """.trimIndent()

        val result = parseBookmarks(output)

        result shouldHaveSize 3
        result.map { it.first } shouldBe listOf("main", "feature", "bugfix")
    }

    @Test
    fun `parse bookmarks with extra whitespace`() {
        val output = """
            main   : abc123def456
            feature:def456abc123
             bugfix : 123456789abc
        """.trimIndent()

        val result = parseBookmarks(output)

        result shouldHaveSize 3
        result.map { it.first } shouldBe listOf("main", "feature", "bugfix")
    }

    @Test
    fun `parse bookmarks with blank lines between entries`() {
        val output = """
            main: abc123def456

            feature: def456abc123

            bugfix: 123456789abc
        """.trimIndent()

        val result = parseBookmarks(output)

        result shouldHaveSize 3
        result.map { it.first } shouldBe listOf("main", "feature", "bugfix")
    }

    @Test
    fun `parse bookmarks ignores malformed lines without colon`() {
        val output = """
            main: abc123def456
            invalid-no-colon
            feature: def456abc123
        """.trimIndent()

        val result = parseBookmarks(output)

        result shouldHaveSize 2
        result.map { it.first } shouldBe listOf("main", "feature")
    }

    @Test
    fun `parse bookmarks with dashes and underscores in names`() {
        val output = """
            main-branch: abc123
            feature_v2: def456
            bug-fix_123: 789abc
        """.trimIndent()

        val result = parseBookmarks(output)

        result shouldHaveSize 3
        result.map { it.first } shouldBe listOf("main-branch", "feature_v2", "bug-fix_123")
    }

    @Test
    fun `parse handles colons in change ID`() {
        // Change ID might contain colons (though unlikely)
        val output = "main: abc:123"
        val result = parseBookmarks(output)

        result shouldHaveSize 1
        result[0].first shouldBe "main"
        result[0].second shouldBe "abc:123"
    }

    @Test
    fun `parse truncates long change IDs to 12 characters`() {
        val output = "main: abcdef0123456789abcdef0123456789abcdef0123456789"
        val result = parseBookmarks(output)

        result shouldHaveSize 1
        result[0].first shouldBe "main"
        result[0].second shouldBe "abcdef012345" // Truncated to 12 chars
    }

    @Test
    fun `parse ignores empty bookmark names`() {
        val output = """
            : abc123def456
            main: def456abc123
        """.trimIndent()

        val result = parseBookmarks(output)

        result shouldHaveSize 1
        result[0].first shouldBe "main"
    }

    @Test
    fun `parse handles whitespace-only bookmark names as empty`() {
        val output = """
               : abc123def456
            main: def456abc123
        """.trimIndent()

        val result = parseBookmarks(output)

        result shouldHaveSize 1
        result[0].first shouldBe "main"
    }
}
