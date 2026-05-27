package `in`.kkkev.jjidea.actions.git

import `in`.kkkev.jjidea.jj.Bookmark
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GitPushDialogTest {
    // Null byte: the field separator emitted by the JJ template
    private val nul = Char(0).toString()

    private fun entry(name: String, present: Boolean) = name + nul + (if (present) "true" else "false") + nul

    @Nested
    inner class `parseBookmarks` {
        @Test
        fun `returns empty list for empty stdout`() =
            GitPushDialog.parseBookmarks("", tracked = true) shouldBe emptyList()

        @Test
        fun `parses single present bookmark`() =
            GitPushDialog.parseBookmarks(entry("main", present = true), tracked = true) shouldBe
                listOf(Bookmark("main", tracked = true, deleted = false))

        @Test
        fun `parses single deleted bookmark`() =
            GitPushDialog.parseBookmarks(entry("main", present = false), tracked = true) shouldBe
                listOf(Bookmark("main", tracked = true, deleted = true))

        @Test
        fun `parses multiple bookmarks including deletions`() =
            GitPushDialog.parseBookmarks(
                entry("main", present = true) + entry("feature", present = true) + entry("old", present = false),
                tracked = true
            ) shouldBe listOf(
                Bookmark("main", tracked = true, deleted = false),
                Bookmark("feature", tracked = true, deleted = false),
                Bookmark("old", tracked = true, deleted = true)
            )

        @Test
        fun `propagates tracked=false to all results`() =
            GitPushDialog.parseBookmarks(entry("main", present = true), tracked = false) shouldBe
                listOf(Bookmark("main", tracked = false, deleted = false))

        @Test
        fun `ignores incomplete trailing pair`() =
            GitPushDialog.parseBookmarks(
                entry("main", present = true) + "partial" + nul,
                tracked = true
            ) shouldBe listOf(Bookmark("main", tracked = true, deleted = false))

        @Test
        fun `remote-tracking entries produce empty tokens that are filtered out`() {
            // Remote tracking entries emit "" in the template; these appear as extra NULs
            // in the concatenated output and must be silently discarded
            val stdout = nul + entry("main", present = true) + nul
            val result = GitPushDialog.parseBookmarks(stdout, tracked = true)
            result shouldBe listOf(Bookmark("main", tracked = true, deleted = false))
        }
    }
}
