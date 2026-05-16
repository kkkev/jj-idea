package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Remote
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [gitFetchArgs] and [gitPushArgs] — the argument building logic
 * used by [CliExecutor.gitFetch] and [CliExecutor.gitPush].
 */
class CliExecutorGitRemoteTest {
    @Nested
    inner class `git fetch` {
        @Test
        fun `default fetch`() {
            gitFetchArgs() shouldBe listOf("git", "fetch")
        }

        @Test
        fun `fetch with specific remote`() {
            gitFetchArgs(remote = Remote("origin")) shouldBe listOf("git", "fetch", "--remote", "origin")
        }

        @Test
        fun `fetch all remotes`() {
            gitFetchArgs(allRemotes = true) shouldBe listOf("git", "fetch", "--all-remotes")
        }

        @Test
        fun `all remotes takes precedence over specific remote`() {
            gitFetchArgs(remote = Remote("origin"), allRemotes = true) shouldBe listOf("git", "fetch", "--all-remotes")
        }
    }

    @Nested
    inner class `git push` {
        @Test
        fun `default push`() {
            gitPushArgs() shouldBe listOf("git", "push")
        }

        @Test
        fun `push to specific remote`() {
            gitPushArgs(remote = Remote("github")) shouldBe listOf("git", "push", "--remote", "github")
        }

        @Test
        fun `push specific bookmark`() {
            gitPushArgs(bookmark = Bookmark("main")) shouldBe listOf("git", "push", "--bookmark", "main")
        }

        @Test
        fun `push all bookmarks`() {
            gitPushArgs(allBookmarks = true) shouldBe listOf("git", "push", "--all")
        }

        @Test
        fun `push specific bookmark to specific remote`() {
            gitPushArgs(remote = Remote("origin"), bookmark = Bookmark("main")) shouldBe
                listOf("git", "push", "--remote", "origin", "--bookmark", "main")
        }

        @Test
        fun `push all bookmarks to specific remote`() {
            gitPushArgs(remote = Remote("origin"), allBookmarks = true) shouldBe
                listOf("git", "push", "--remote", "origin", "--all")
        }

        @Test
        fun `all bookmarks takes precedence over specific bookmark`() {
            gitPushArgs(bookmark = Bookmark("main"), allBookmarks = true) shouldBe listOf("git", "push", "--all")
        }

        @Test
        fun `push with allow-new includes --allow-new flag`() {
            gitPushArgs(allowNew = true) shouldBe listOf("git", "push", "--allow-new")
        }

        @Test
        fun `push with revision adds -r flag in default scope`() {
            val revision = ChangeId("abc123", "abc")
            gitPushArgs(revision = revision) shouldBe listOf("git", "push", "-r", "abc123")
        }

        @Test
        fun `push with revision and remote`() {
            val revision = ChangeId("abc123", "abc")
            gitPushArgs(remote = Remote("origin"), revision = revision) shouldBe
                listOf("git", "push", "--remote", "origin", "-r", "abc123")
        }

        @Test
        fun `revision is ignored when specific bookmark is selected`() {
            val revision = ChangeId("abc123", "abc")
            gitPushArgs(bookmark = Bookmark("main"), revision = revision) shouldBe
                listOf("git", "push", "--bookmark", "main")
        }

        @Test
        fun `revision is ignored when all bookmarks is selected`() {
            val revision = ChangeId("abc123", "abc")
            gitPushArgs(allBookmarks = true, revision = revision) shouldBe listOf("git", "push", "--all")
        }
    }
}
