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
            gitFetchArgs().args shouldBe listOf("git", "fetch")
        }

        @Test
        fun `fetch with specific remote`() {
            gitFetchArgs(remote = Remote("origin")).args shouldBe listOf("git", "fetch", "--remote", "origin")
        }

        @Test
        fun `fetch all remotes`() {
            gitFetchArgs(allRemotes = true).args shouldBe listOf("git", "fetch", "--all-remotes")
        }

        @Test
        fun `all remotes takes precedence over specific remote`() {
            gitFetchArgs(remote = Remote("origin"), allRemotes = true).args shouldBe
                listOf("git", "fetch", "--all-remotes")
        }

        @Test
        fun `fetch is irreversible - verified, reverting leaves remote-tracking desynced from local`() {
            gitFetchArgs().reversibility shouldBe Reversibility.IRREVERSIBLE
        }
    }

    @Nested
    inner class `git push` {
        @Test
        fun `default push`() {
            gitPushArgs().args shouldBe listOf("git", "push")
        }

        @Test
        fun `push to specific remote`() {
            gitPushArgs(remote = Remote("github")).args shouldBe listOf("git", "push", "--remote", "github")
        }

        @Test
        fun `push specific bookmark`() {
            gitPushArgs(bookmark = Bookmark("main")).args shouldBe listOf("git", "push", "--bookmark", "main")
        }

        @Test
        fun `push all bookmarks`() {
            gitPushArgs(allBookmarks = true).args shouldBe listOf("git", "push", "--all")
        }

        @Test
        fun `push specific bookmark to specific remote`() {
            gitPushArgs(remote = Remote("origin"), bookmark = Bookmark("main")).args shouldBe
                listOf("git", "push", "--remote", "origin", "--bookmark", "main")
        }

        @Test
        fun `push all bookmarks to specific remote`() {
            gitPushArgs(remote = Remote("origin"), allBookmarks = true).args shouldBe
                listOf("git", "push", "--remote", "origin", "--all")
        }

        @Test
        fun `all bookmarks takes precedence over specific bookmark`() {
            gitPushArgs(bookmark = Bookmark("main"), allBookmarks = true).args shouldBe listOf("git", "push", "--all")
        }

        @Test
        fun `push with revision adds -r flag in default scope`() {
            val revision = ChangeId("abc123", "abc")
            gitPushArgs(revision = revision).args shouldBe listOf("git", "push", "-r", "abc123")
        }

        @Test
        fun `push with revision and remote`() {
            val revision = ChangeId("abc123", "abc")
            gitPushArgs(remote = Remote("origin"), revision = revision).args shouldBe
                listOf("git", "push", "--remote", "origin", "-r", "abc123")
        }

        @Test
        fun `revision is ignored when specific bookmark is selected`() {
            val revision = ChangeId("abc123", "abc")
            gitPushArgs(bookmark = Bookmark("main"), revision = revision).args shouldBe
                listOf("git", "push", "--bookmark", "main")
        }

        @Test
        fun `revision is ignored when all bookmarks is selected`() {
            val revision = ChangeId("abc123", "abc")
            gitPushArgs(allBookmarks = true, revision = revision).args shouldBe listOf("git", "push", "--all")
        }

        @Test
        fun `push a change, auto-generating a bookmark`() {
            val change = ChangeId("abc123", "abc")
            gitPushArgs(changeRevisions = listOf(change)).args shouldBe listOf("git", "push", "--change", "abc123")
        }

        @Test
        fun `push multiple changes as repeated --change flags`() {
            val a = ChangeId("abc123", "abc")
            val b = ChangeId("def456", "def")
            gitPushArgs(changeRevisions = listOf(a, b)).args shouldBe
                listOf("git", "push", "--change", "abc123", "--change", "def456")
        }

        @Test
        fun `push a change to a specific remote`() {
            val change = ChangeId("abc123", "abc")
            gitPushArgs(remote = Remote("origin"), changeRevisions = listOf(change)).args shouldBe
                listOf("git", "push", "--remote", "origin", "--change", "abc123")
        }

        @Test
        fun `bookmark takes precedence over change revisions`() {
            val change = ChangeId("abc123", "abc")
            gitPushArgs(bookmark = Bookmark("main"), changeRevisions = listOf(change)).args shouldBe
                listOf("git", "push", "--bookmark", "main")
        }

        @Test
        fun `all bookmarks takes precedence over change revisions`() {
            val change = ChangeId("abc123", "abc")
            gitPushArgs(allBookmarks = true, changeRevisions = listOf(change)).args shouldBe
                listOf("git", "push", "--all")
        }

        @Test
        fun `change revisions take precedence over plain revision`() {
            val change = ChangeId("abc123", "abc")
            val revision = ChangeId("def456", "def")
            gitPushArgs(changeRevisions = listOf(change), revision = revision).args shouldBe
                listOf("git", "push", "--change", "abc123")
        }

        @Test
        fun `empty change revisions falls through to plain revision`() {
            val revision = ChangeId("abc123", "abc")
            gitPushArgs(changeRevisions = emptyList(), revision = revision).args shouldBe
                listOf("git", "push", "-r", "abc123")
        }

        @Test
        fun `push is irreversible - verified, op revert --what repo reports Nothing changed`() {
            gitPushArgs().reversibility shouldBe Reversibility.IRREVERSIBLE
        }
    }
}
