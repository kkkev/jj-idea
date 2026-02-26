package `in`.kkkev.jjidea.jj.cli

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
            gitFetchArgs(remote = "origin") shouldBe listOf("git", "fetch", "--remote", "origin")
        }

        @Test
        fun `fetch all remotes`() {
            gitFetchArgs(allRemotes = true) shouldBe listOf("git", "fetch", "--all-remotes")
        }

        @Test
        fun `all remotes takes precedence over specific remote`() {
            gitFetchArgs(remote = "origin", allRemotes = true) shouldBe listOf("git", "fetch", "--all-remotes")
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
            gitPushArgs(remote = "github") shouldBe listOf("git", "push", "--remote", "github")
        }

        @Test
        fun `push specific bookmark`() {
            gitPushArgs(bookmark = "main") shouldBe listOf("git", "push", "--bookmark", "main")
        }

        @Test
        fun `push all bookmarks`() {
            gitPushArgs(allBookmarks = true) shouldBe listOf("git", "push", "--all")
        }

        @Test
        fun `push specific bookmark to specific remote`() {
            gitPushArgs(remote = "origin", bookmark = "main") shouldBe
                listOf("git", "push", "--remote", "origin", "--bookmark", "main")
        }

        @Test
        fun `push all bookmarks to specific remote`() {
            gitPushArgs(remote = "origin", allBookmarks = true) shouldBe
                listOf("git", "push", "--remote", "origin", "--all")
        }

        @Test
        fun `all bookmarks takes precedence over specific bookmark`() {
            gitPushArgs(bookmark = "main", allBookmarks = true) shouldBe listOf("git", "push", "--all")
        }
    }
}
