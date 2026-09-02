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
            bookmarkCreateArgs(BookmarkName("main")).args shouldBe listOf("bookmark", "create", "main", "-r", "@")
        }

        @Test
        fun `create at specific revision`() {
            bookmarkCreateArgs(BookmarkName("feature"), ChangeId("abc123", "abc1", null)).args shouldBe
                listOf("bookmark", "create", "feature", "-r", "abc123")
        }
    }

    @Nested
    inner class `bookmark delete` {
        @Test
        fun `delete bookmark`() {
            bookmarkDeleteArgs(BookmarkName("main")).args shouldBe listOf("bookmark", "delete", "main")
        }
    }

    @Nested
    inner class `bookmark forget` {
        @Test
        fun `forget bookmark`() {
            bookmarkForgetArgs(BookmarkName("main")).args shouldBe listOf("bookmark", "forget", "main")
        }
    }

    @Nested
    inner class `bookmark rename` {
        @Test
        fun `rename bookmark`() {
            bookmarkRenameArgs(BookmarkName("old-name"), BookmarkName("new-name")).args shouldBe
                listOf("bookmark", "rename", "old-name", "new-name")
        }
    }

    @Nested
    inner class `bookmark track` {
        @Test
        fun `track remote bookmark`() {
            bookmarkTrackArgs(listOf(BookmarkName("main@origin"))).args shouldBe
                listOf("bookmark", "track", "main", "--remote", "origin")
        }

        @Test
        fun `track multiple remote bookmarks in one command`() {
            bookmarkTrackArgs(listOf(BookmarkName("main@origin"), BookmarkName("feature@origin"))).args shouldBe
                listOf("bookmark", "track", "main", "feature", "--remote", "origin")
        }
    }

    @Nested
    inner class `bookmark untrack` {
        @Test
        fun `untrack remote bookmark`() {
            bookmarkUntrackArgs(BookmarkName("main@origin")).args shouldBe
                listOf("bookmark", "untrack", "main", "--remote", "origin")
        }
    }

    @Nested
    inner class `bookmark list` {
        @Test
        fun `list defaults to all remotes, including untracked`() {
            bookmarkListArgs().args shouldBe listOf("bookmark", "list", "--all-remotes")
        }

        @Test
        fun `list scoped to a specific remote omits all-remotes`() {
            bookmarkListArgs(remote = Remote("origin")).args shouldBe listOf("bookmark", "list", "--remote", "origin")
        }
    }

    @Nested
    inner class `bookmark set` {
        @Test
        fun `set at working copy`() {
            bookmarkSetArgs(BookmarkName("main")).args shouldBe listOf("bookmark", "set", "main", "-r", "@")
        }

        @Test
        fun `set at specific revision`() {
            bookmarkSetArgs(BookmarkName("main"), ChangeId("abc123", "abc1", null)).args shouldBe
                listOf("bookmark", "set", "main", "-r", "abc123")
        }

        @Test
        fun `set with allow backwards`() {
            bookmarkSetArgs(BookmarkName("main"), WorkingCopy, allowBackwards = true).args shouldBe
                listOf("bookmark", "set", "main", "-r", "@", "-B")
        }

        @Test
        fun `set without allow backwards does not include flag`() {
            bookmarkSetArgs(BookmarkName("main"), WorkingCopy, allowBackwards = false).args shouldBe
                listOf("bookmark", "set", "main", "-r", "@")
        }
    }

    @Nested
    inner class `bookmark advance` {
        @Test
        fun `advance with no names lets jj pick eligible bookmarks`() {
            bookmarkAdvanceArgs().args shouldBe listOf("bookmark", "advance", "--to", "@")
        }

        @Test
        fun `advance restricted to specific names`() {
            bookmarkAdvanceArgs(listOf(BookmarkName("main"), BookmarkName("feature"))).args shouldBe
                listOf("bookmark", "advance", "--to", "@", "main", "feature")
        }

        @Test
        fun `advance to a specific revision`() {
            bookmarkAdvanceArgs(listOf(BookmarkName("main")), ChangeId("abc123", "abc1", null)).args shouldBe
                listOf("bookmark", "advance", "--to", "abc123", "main")
        }
    }

    @Nested
    inner class `reversibility` {
        @Test
        fun `create, delete, forget, rename, set and advance are reversible`() {
            bookmarkCreateArgs(BookmarkName("main")).reversibility shouldBe Reversibility.REVERSIBLE
            bookmarkDeleteArgs(BookmarkName("main")).reversibility shouldBe Reversibility.REVERSIBLE
            bookmarkForgetArgs(BookmarkName("main")).reversibility shouldBe Reversibility.REVERSIBLE
            bookmarkRenameArgs(BookmarkName("a"), BookmarkName("b")).reversibility shouldBe Reversibility.REVERSIBLE
            bookmarkSetArgs(BookmarkName("main")).reversibility shouldBe Reversibility.REVERSIBLE
            bookmarkAdvanceArgs().reversibility shouldBe Reversibility.REVERSIBLE
        }

        @Test
        fun `list is read-only`() {
            bookmarkListArgs().reversibility shouldBe Reversibility.READ_ONLY
        }

        @Test
        fun `track and untrack are irreversible - remote-tracking scope only`() {
            bookmarkTrackArgs(listOf(BookmarkName("main"))).reversibility shouldBe Reversibility.IRREVERSIBLE
            bookmarkUntrackArgs(BookmarkName("main")).reversibility shouldBe Reversibility.IRREVERSIBLE
        }
    }
}
