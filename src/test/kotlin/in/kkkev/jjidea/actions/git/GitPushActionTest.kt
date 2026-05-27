package `in`.kkkev.jjidea.actions.git

import `in`.kkkev.jjidea.jj.Bookmark
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GitPushActionTest {
    @Nested
    inner class `resolveAllowNew` {
        @Test
        fun `default scope without confirmation does not allow new`() =
            resolveAllowNew(forceAllowNew = false, bookmark = null) shouldBe false

        @Test
        fun `default scope with user confirmation allows new`() =
            resolveAllowNew(forceAllowNew = true, bookmark = null) shouldBe true

        @Test
        fun `specific untracked bookmark allows new`() =
            resolveAllowNew(forceAllowNew = false, bookmark = Bookmark("feature", tracked = false)) shouldBe true

        @Test
        fun `specific tracked bookmark does not allow new`() =
            resolveAllowNew(forceAllowNew = false, bookmark = Bookmark("main", tracked = true)) shouldBe false

        @Test
        fun `force plus tracked bookmark still allows new`() =
            resolveAllowNew(forceAllowNew = true, bookmark = Bookmark("main", tracked = true)) shouldBe true
    }

    @Nested
    inner class `parseForcePushBookmarks` {
        @Test
        fun `returns empty list when no force-push moves`() {
            val stderr =
                """
                Changes to push to origin:
                  Move forward bookmark main from abc1234 to def5678
                  Add bookmark feature to abc1234
                """.trimIndent()
            parseForcePushBookmarks(stderr) shouldBe emptyList()
        }

        @Test
        fun `detects sideways move`() {
            val stderr =
                """
                Changes to push to origin:
                  Move sideways bookmark main from abc1234 to def5678
                """.trimIndent()
            parseForcePushBookmarks(stderr) shouldBe listOf("main")
        }

        @Test
        fun `detects backward move`() {
            val stderr =
                """
                Changes to push to origin:
                  Move backward bookmark main from abc1234 to def5678
                """.trimIndent()
            parseForcePushBookmarks(stderr) shouldBe listOf("main")
        }

        @Test
        fun `detects mix of sideways and backward moves`() {
            val stderr =
                """
                Changes to push to origin:
                  Move sideways bookmark main from abc1234 to def5678
                  Move forward bookmark feature from aaa1111 to bbb2222
                  Move backward bookmark release/1.0 from ccc3333 to ddd4444
                """.trimIndent()
            parseForcePushBookmarks(stderr) shouldBe listOf("main", "release/1.0")
        }

        @Test
        fun `returns empty list for empty output`() {
            parseForcePushBookmarks("") shouldBe emptyList()
        }

        @Test
        fun `handles dry-run suffix in output`() {
            val stderr =
                """
                Changes to push to origin:
                  Move backward bookmark main from abc1234 to def5678
                Dry-run requested, not pushing.
                """.trimIndent()
            parseForcePushBookmarks(stderr) shouldBe listOf("main")
        }
    }

    @Nested
    inner class `parseDeletedBookmarks` {
        @Test
        fun `returns empty list when no deletions`() {
            val stderr =
                """
                Changes to push to origin:
                  Move forward bookmark main from abc1234 to def5678
                  Add bookmark feature to abc1234
                """.trimIndent()
            parseDeletedBookmarks(stderr) shouldBe emptyList()
        }

        @Test
        fun `detects single deletion`() {
            val stderr =
                """
                Changes to push to origin:
                  Delete bookmark old-feature from abc1234
                """.trimIndent()
            parseDeletedBookmarks(stderr) shouldBe listOf("old-feature")
        }

        @Test
        fun `detects multiple deletions`() {
            val stderr =
                """
                Changes to push to origin:
                  Delete bookmark old-feature from abc1234
                  Delete bookmark release/1.0 from def5678
                """.trimIndent()
            parseDeletedBookmarks(stderr) shouldBe listOf("old-feature", "release/1.0")
        }

        @Test
        fun `ignores non-deletion lines`() {
            val stderr =
                """
                Changes to push to origin:
                  Move sideways bookmark main from abc1234 to def5678
                  Delete bookmark old-feature from aaa1111
                  Move forward bookmark feature from bbb2222 to ccc3333
                  Add bookmark new-thing to ddd4444
                """.trimIndent()
            parseDeletedBookmarks(stderr) shouldBe listOf("old-feature")
        }

        @Test
        fun `returns empty list for empty output`() {
            parseDeletedBookmarks("") shouldBe emptyList()
        }

        @Test
        fun `handles dry-run suffix in output`() {
            val stderr =
                """
                Changes to push to origin:
                  Delete bookmark stale from abc1234
                Dry-run requested, not pushing.
                """.trimIndent()
            parseDeletedBookmarks(stderr) shouldBe listOf("stale")
        }
    }
}
