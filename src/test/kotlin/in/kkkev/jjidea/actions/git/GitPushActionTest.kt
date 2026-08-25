package `in`.kkkev.jjidea.actions.git

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GitPushActionTest {
    @Nested
    inner class `parseRefusedNewBookmarks` {
        @Test
        fun `returns empty list when nothing was refused`() {
            val stderr =
                """
                Changes to push to origin:
                  Move forward bookmark main from abc1234 to def5678
                """.trimIndent()
            parseRefusedNewBookmarks(stderr) shouldBe emptyList()
        }

        @Test
        fun `detects a specific-bookmark refusal (Error, exit 1)`() {
            val stderr =
                """
                Error: Refusing to create new remote bookmark feature@origin
                Hint: Run `jj bookmark track feature --remote=origin` and try again.
                """.trimIndent()
            parseRefusedNewBookmarks(stderr) shouldBe listOf(BookmarkName("feature@origin"))
        }

        @Test
        fun `detects a default-scope refusal (Warning, exit 0)`() {
            val stderr =
                """
                Warning: Refusing to create new remote bookmark feature@origin
                Hint: Run `jj bookmark track feature --remote=origin` and try again.
                Nothing changed.
                """.trimIndent()
            parseRefusedNewBookmarks(stderr) shouldBe listOf(BookmarkName("feature@origin"))
        }

        @Test
        fun `detects multiple refused bookmarks`() {
            val stderr =
                """
                Warning: Refusing to create new remote bookmark feature-a@origin
                Warning: Refusing to create new remote bookmark feature-b@origin
                Nothing changed.
                """.trimIndent()
            parseRefusedNewBookmarks(stderr) shouldBe
                listOf(BookmarkName("feature-a@origin"), BookmarkName("feature-b@origin"))
        }

        @Test
        fun `returns empty list for empty output`() {
            parseRefusedNewBookmarks("") shouldBe emptyList()
        }
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

    @Nested
    inner class `pushSuccessMessage` {
        @Test
        fun `prefers stdout when present`() {
            pushSuccessMessage("Changes pushed to origin", "") shouldBe "Changes pushed to origin"
        }

        @Test
        fun `falls back to stderr when stdout is blank so a no-op push isn't reported as success`() {
            // jj writes "Nothing changed." to stderr, not stdout, on a no-op push (jj-idea-idm0)
            pushSuccessMessage("", "Nothing changed.") shouldBe "Nothing changed."
        }

        @Test
        fun `falls back to the generic default when both are blank`() {
            pushSuccessMessage("", "") shouldBe JujutsuBundle.message("action.git.push.success.message.default")
        }

        @Test
        fun `blank stdout with whitespace-only stderr still falls through to the default`() {
            pushSuccessMessage("  \n", "  ") shouldBe JujutsuBundle.message("action.git.push.success.message.default")
        }
    }

    @Nested
    inner class `changeTargetsFor` {
        private val repo = mockk<JujutsuRepository>()

        private fun workingCopyEntry(isEmpty: Boolean) = LogEntry(
            repo = repo,
            id = ChangeId("qpvuntsm", "qp"),
            commitId = CommitId("abc123def456", "ab"),
            underlyingDescription = "",
            isEmpty = isEmpty
        )

        private fun selectedEntry(id: String, commit: String) = LogEntry(
            repo = repo,
            id = ChangeId(id, id.take(2)),
            commitId = CommitId(commit, commit.take(2)),
            underlyingDescription = ""
        )

        @Test
        fun `defaults to the working copy when it is not empty and nothing is selected`() {
            every { repo.workingCopy } returns workingCopyEntry(isEmpty = false)
            changeTargetsFor(repo, entries = emptyList()) shouldBe listOf(WorkingCopy)
        }

        @Test
        fun `falls back to the working copy's parent when it is empty and nothing is selected`() {
            every { repo.workingCopy } returns workingCopyEntry(isEmpty = true)
            changeTargetsFor(repo, entries = emptyList()) shouldBe listOf(WorkingCopy.parent)
        }

        @Test
        fun `uses a single selected entry's own id, ignoring the working copy`() {
            val entry = selectedEntry("mzvwutvl", "def789")
            changeTargetsFor(repo, entries = listOf(entry)) shouldBe listOf(entry.id)
        }

        @Test
        fun `uses every selected entry's id in order for a multi-selection (jj-idea-ikof)`() {
            val a = selectedEntry("mzvwutvl", "def789")
            val b = selectedEntry("rlvkpnrz", "abc123")
            changeTargetsFor(repo, entries = listOf(a, b)) shouldBe listOf(a.id, b.id)
        }
    }
}
