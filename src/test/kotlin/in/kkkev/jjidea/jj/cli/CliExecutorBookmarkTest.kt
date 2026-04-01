package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for bookmark argument building functions used by [CliExecutor].
 */
class CliExecutorBookmarkTest {
    @Nested
    inner class `bookmark create` {
        @Test
        fun `create at working copy`() {
            bookmarkCreateArgs(Bookmark("main")) shouldBe listOf("bookmark", "create", "main", "-r", "@")
        }

        @Test
        fun `create at specific revision`() {
            bookmarkCreateArgs(Bookmark("feature"), ChangeId("abc123", "abc1", null)) shouldBe
                listOf("bookmark", "create", "feature", "-r", "abc123")
        }
    }

    @Nested
    inner class `bookmark delete` {
        @Test
        fun `delete bookmark`() {
            bookmarkDeleteArgs(Bookmark("main")) shouldBe listOf("bookmark", "delete", "main")
        }
    }

    @Nested
    inner class `bookmark rename` {
        @Test
        fun `rename bookmark`() {
            bookmarkRenameArgs(Bookmark("old-name"), Bookmark("new-name")) shouldBe
                listOf("bookmark", "rename", "old-name", "new-name")
        }
    }

    @Nested
    inner class `bookmark track` {
        @Test
        fun `track remote bookmark`() {
            bookmarkTrackArgs(Bookmark("main@origin")) shouldBe
                listOf("bookmark", "track", "main", "--remote", "origin")
        }
    }

    @Nested
    inner class `bookmark untrack` {
        @Test
        fun `untrack remote bookmark`() {
            bookmarkUntrackArgs(Bookmark("main@origin")) shouldBe
                listOf("bookmark", "untrack", "main", "--remote", "origin")
        }
    }

    @Nested
    inner class `bookmark set` {
        @Test
        fun `set at working copy`() {
            bookmarkSetArgs(Bookmark("main")) shouldBe listOf("bookmark", "set", "main", "-r", "@")
        }

        @Test
        fun `set at specific revision`() {
            bookmarkSetArgs(Bookmark("main"), ChangeId("abc123", "abc1", null)) shouldBe
                listOf("bookmark", "set", "main", "-r", "abc123")
        }

        @Test
        fun `set with allow backwards`() {
            bookmarkSetArgs(Bookmark("main"), WorkingCopy, allowBackwards = true) shouldBe
                listOf("bookmark", "set", "main", "-r", "@", "-B")
        }

        @Test
        fun `set without allow backwards does not include flag`() {
            bookmarkSetArgs(Bookmark("main"), WorkingCopy, allowBackwards = false) shouldBe
                listOf("bookmark", "set", "main", "-r", "@")
        }
    }
}
