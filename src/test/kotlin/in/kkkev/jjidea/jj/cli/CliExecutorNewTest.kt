package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [newArgs] — the argument building logic used by [CliExecutor.new].
 */
class CliExecutorNewTest {
    private val revision = ChangeId("abc123def456", "abc123de", null)

    @Nested
    inner class `onto (default placement)` {
        @Test
        fun `single revision is positional, not flagged`() {
            val result = newArgs(Description.EMPTY, listOf(revision)).args

            // jj new has no --onto flag - RebaseDestinationMode.ONTO's `flag` string must never
            // appear here, unlike duplicateArgs/rebaseArgs which do emit it.
            result shouldBe listOf("new", "abc123def456")
        }

        @Test
        fun `working copy default`() {
            val result = newArgs(Description.EMPTY, listOf(WorkingCopy)).args

            result shouldBe listOf("new", "@")
        }

        @Test
        fun `multiple revisions are all positional (merge parents)`() {
            val result = newArgs(
                Description.EMPTY,
                listOf(
                    ChangeId("aaa111bbb222", "aaa111bb", null),
                    ChangeId("bbb222ccc333", "bbb222cc", null)
                )
            ).args

            result shouldBe listOf("new", "aaa111bbb222", "bbb222ccc333")
        }

        @Test
        fun `description is fused with --message=`() {
            val result = newArgs(Description("A new task"), listOf(revision)).args

            result shouldBe listOf("new", "--message=A new task", "abc123def456")
        }
    }

    @Nested
    inner class `insert after (-A)` {
        @Test
        fun `single target`() {
            val result = newArgs(Description.EMPTY, listOf(revision), RebaseDestinationMode.INSERT_AFTER).args

            result shouldBe listOf("new", "-A", "abc123def456")
        }

        @Test
        fun `multiple targets each get their own -A flag`() {
            val result = newArgs(
                Description.EMPTY,
                listOf(
                    ChangeId("aaa111bbb222", "aaa111bb", null),
                    ChangeId("bbb222ccc333", "bbb222cc", null)
                ),
                RebaseDestinationMode.INSERT_AFTER
            ).args

            result shouldBe listOf("new", "-A", "aaa111bbb222", "-A", "bbb222ccc333")
        }
    }

    @Nested
    inner class `insert before (-B)` {
        @Test
        fun `single target`() {
            val result = newArgs(Description.EMPTY, listOf(revision), RebaseDestinationMode.INSERT_BEFORE).args

            result shouldBe listOf("new", "-B", "abc123def456")
        }
    }

    @Nested
    inner class `--no-edit` {
        @Test
        fun `edit=false emits --no-edit`() {
            val result = newArgs(Description.EMPTY, listOf(revision), edit = false).args

            result shouldBe listOf("new", "--no-edit", "abc123def456")
        }

        @Test
        fun `edit=true (default) omits --no-edit`() {
            val result = newArgs(Description.EMPTY, listOf(revision), edit = true).args

            result shouldBe listOf("new", "abc123def456")
        }

        @Test
        fun `combines with description and insert-after`() {
            val result = newArgs(
                Description("Placeholder"),
                listOf(revision),
                RebaseDestinationMode.INSERT_AFTER,
                edit = false
            ).args

            result shouldBe listOf("new", "--message=Placeholder", "--no-edit", "-A", "abc123def456")
        }
    }

    @Test
    fun `new is reversible`() {
        newArgs(Description.EMPTY, listOf(revision)).reversibility shouldBe Reversibility.REVERSIBLE
    }
}
