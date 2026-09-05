package `in`.kkkev.jjidea.actions.change

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Regression test for GitHub #76 / jj-idea-is97: the Describe dialog's prompt must show the
 * *short* change id, matching the id shown everywhere else in the UI, not the full 64-char id
 * that `ChangeId.toString()` returns (which is still correct for `jj` CLI arguments).
 */
class DescribeActionMessageTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(id: ChangeId) = LogEntry(
        repo = repo,
        id = id,
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = "Test commit",
        bookmarks = emptyList(),
        parentIds = emptyList(),
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = true,
        authorTimestamp = null,
        committerTimestamp = null,
        author = null,
        committer = null,
        immutable = false
    )

    @Test
    fun `prompt id is the short id, not the full id`() {
        val full = "ab12" + "a".repeat(60)
        val target = entry(ChangeId(full, "ab12"))

        describePromptId(target) shouldBe "ab12"

        val message = JujutsuBundle.message("dialog.describe.input.message", describePromptId(target))

        message shouldContain "ab12"
        message shouldNotContain full
    }

    @Test
    fun `divergent change keeps its offset suffix in the prompt`() {
        val full = "cd34" + "b".repeat(60)
        val target = entry(ChangeId(full, "cd34", offset = 1))

        describePromptId(target) shouldBe "cd34/1"

        val message = JujutsuBundle.message("dialog.describe.input.message", describePromptId(target))

        message shouldContain "cd34/1"
        message shouldNotContain full
    }
}
