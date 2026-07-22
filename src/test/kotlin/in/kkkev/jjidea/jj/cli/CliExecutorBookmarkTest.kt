package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Remote
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
            bookmarkCreateArgs(BookmarkName("main")) shouldBe listOf("bookmark", "create", "main", "-r", "@")
        }

        @Test
        fun `create at specific revision`() {
            bookmarkCreateArgs(BookmarkName("feature"), ChangeId("abc123", "abc1", null)) shouldBe
                listOf("bookmark", "create", "feature", "-r", "abc123")
        }
    }

    @Nested
    inner class `bookmark delete` {
        @Test
        fun `delete bookmark`() {
            bookmarkDeleteArgs(BookmarkName("main")) shouldBe listOf("bookmark", "delete", "main")
        }
    }

    @Nested
    inner class `bookmark forget` {
        @Test
        fun `forget bookmark`() {
            bookmarkForgetArgs(BookmarkName("main")) shouldBe listOf("bookmark", "forget", "main")
        }
    }

    @Nested
    inner class `bookmark rename` {
        @Test
        fun `rename bookmark`() {
            bookmarkRenameArgs(BookmarkName("old-name"), BookmarkName("new-name")) shouldBe
                listOf("bookmark", "rename", "old-name", "new-name")
        }
    }

    @Nested
    inner class `bookmark track` {
        @Test
        fun `track remote bookmark`() {
            bookmarkTrackArgs(listOf(BookmarkName("main@origin"))) shouldBe
                listOf("bookmark", "track", "main", "--remote", "origin")
        }

        @Test
        fun `track multiple remote bookmarks in one command`() {
            bookmarkTrackArgs(listOf(BookmarkName("main@origin"), BookmarkName("feature@origin"))) shouldBe
                listOf("bookmark", "track", "main", "feature", "--remote", "origin")
        }
    }

    @Nested
    inner class `bookmark untrack` {
        @Test
        fun `untrack remote bookmark`() {
            bookmarkUntrackArgs(BookmarkName("main@origin")) shouldBe
                listOf("bookmark", "untrack", "main", "--remote", "origin")
        }
    }

    @Nested
    inner class `bookmark list` {
        @Test
        fun `list defaults to all remotes, including untracked`() {
            bookmarkListArgs() shouldBe listOf("bookmark", "list", "--all-remotes")
        }

        @Test
        fun `list scoped to a specific remote omits all-remotes`() {
            bookmarkListArgs(remote = Remote("origin")) shouldBe listOf("bookmark", "list", "--remote", "origin")
        }
    }

    @Nested
    inner class `bookmark set` {
        @Test
        fun `set at working copy`() {
            bookmarkSetArgs(BookmarkName("main")) shouldBe listOf("bookmark", "set", "main", "-r", "@")
        }

        @Test
        fun `set at specific revision`() {
            bookmarkSetArgs(BookmarkName("main"), ChangeId("abc123", "abc1", null)) shouldBe
                listOf("bookmark", "set", "main", "-r", "abc123")
        }

        @Test
        fun `set with allow backwards`() {
            bookmarkSetArgs(BookmarkName("main"), WorkingCopy, allowBackwards = true) shouldBe
                listOf("bookmark", "set", "main", "-r", "@", "-B")
        }

        @Test
        fun `set without allow backwards does not include flag`() {
            bookmarkSetArgs(BookmarkName("main"), WorkingCopy, allowBackwards = false) shouldBe
                listOf("bookmark", "set", "main", "-r", "@")
        }
    }
}
