package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** jj-idea-wiz0: reads address a commit by its full id, never by a prefix or the change id. */
class CliExecutorCommitIdReadTest {
    private val full = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `diffSummaryBetweenArgs uses the full commit id, not its short form`() {
        val args = diffSummaryBetweenArgs(ChangeId("abc", "abc"), CommitId(full, "0123"), null, null).args

        args shouldBe listOf("diff", "--summary", "--from", "abc", "--to", full)
    }

    @Test
    fun `diffSummaryBetweenArgs leaves change ids as they are`() {
        val args = diffSummaryBetweenArgs(ChangeId("abc", "ab"), ChangeId("def", "de", 1), null, null).args

        args shouldBe listOf("diff", "--summary", "--from", "abc", "--to", "def/1")
    }
}
