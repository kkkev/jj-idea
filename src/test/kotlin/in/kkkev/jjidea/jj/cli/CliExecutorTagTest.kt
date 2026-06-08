package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for tag argument building functions used by [CliExecutor].
 */
class CliExecutorTagTest {
    @Nested
    inner class `tag set` {
        @Test
        fun `set at working copy`() {
            tagSetArgs(Tag("v1.0")) shouldBe listOf("tag", "set", "v1.0", "-r", "@")
        }

        @Test
        fun `set at specific revision`() {
            tagSetArgs(Tag("v1.0"), ChangeId("abc123", "abc1", null)) shouldBe
                listOf("tag", "set", "v1.0", "-r", "abc123")
        }

        @Test
        fun `set with allow move`() {
            tagSetArgs(Tag("v1.0"), WorkingCopy, allowMove = true) shouldBe
                listOf("tag", "set", "v1.0", "-r", "@", "--allow-move")
        }

        @Test
        fun `set without allow move does not include flag`() {
            tagSetArgs(Tag("v1.0"), WorkingCopy, allowMove = false) shouldBe
                listOf("tag", "set", "v1.0", "-r", "@")
        }
    }

    @Nested
    inner class `tag delete` {
        @Test
        fun `delete tag`() {
            tagDeleteArgs(Tag("v1.0")) shouldBe listOf("tag", "delete", "v1.0")
        }
    }
}
