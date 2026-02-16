package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [rebaseArgs] — the argument building logic used by [CliExecutor.rebase].
 *
 * Tests verify the correct CLI arguments are constructed for various combinations
 * of source modes, destination modes, and single/multi selections.
 */
class CliExecutorRebaseTest {
    @Nested
    inner class `single revision single destination` {
        @Test
        fun `rebase -r revision -d destination`() {
            val result = rebaseArgs(
                listOf(ChangeId("abc123def456", "abc123de", null)),
                listOf(Bookmark("main"))
            )

            result shouldBe listOf("rebase", "-r", "abc123def456", "-d", "main")
        }

        @Test
        fun `rebase -s revision -d destination`() {
            val result = rebaseArgs(
                listOf(ChangeId("abc123def456", "abc123de", null)),
                listOf(Bookmark("main")),
                RebaseSourceMode.SOURCE
            )

            result shouldBe listOf("rebase", "-s", "abc123def456", "-d", "main")
        }

        @Test
        fun `rebase -b revision -d destination`() {
            val result = rebaseArgs(
                listOf(ChangeId("abc123def456", "abc123de", null)),
                listOf(Bookmark("main")),
                RebaseSourceMode.BRANCH
            )

            result shouldBe listOf("rebase", "-b", "abc123def456", "-d", "main")
        }
    }

    @Nested
    inner class `destination modes` {
        private val revision = ChangeId("abc123def456", "abc123de", null)
        private val destination = Bookmark("main")

        @Test
        fun `insert after uses -A flag`() {
            val result = rebaseArgs(
                listOf(revision),
                listOf(destination),
                destinationMode = RebaseDestinationMode.INSERT_AFTER
            )

            result shouldBe listOf("rebase", "-r", "abc123def456", "-A", "main")
        }

        @Test
        fun `insert before uses -B flag`() {
            val result = rebaseArgs(
                listOf(revision),
                listOf(destination),
                destinationMode = RebaseDestinationMode.INSERT_BEFORE
            )

            result shouldBe listOf("rebase", "-r", "abc123def456", "-B", "main")
        }
    }

    @Nested
    inner class `multiple revisions` {
        @Test
        fun `multiple revisions each get source flag`() {
            val result = rebaseArgs(
                listOf(
                    ChangeId("abc123def456", "abc123de", null),
                    ChangeId("fed987cba654", "fed987cb", null)
                ),
                listOf(Bookmark("main"))
            )

            result shouldBe listOf("rebase", "-r", "abc123def456", "-r", "fed987cba654", "-d", "main")
        }

        @Test
        fun `multiple revisions with source mode`() {
            val result = rebaseArgs(
                listOf(
                    ChangeId("abc123def456", "abc123de", null),
                    ChangeId("fed987cba654", "fed987cb", null)
                ),
                listOf(Bookmark("main")),
                RebaseSourceMode.SOURCE
            )

            result shouldBe listOf("rebase", "-s", "abc123def456", "-s", "fed987cba654", "-d", "main")
        }
    }

    @Nested
    inner class `multiple destinations` {
        @Test
        fun `multiple destinations each get destination flag for merge`() {
            val result = rebaseArgs(
                listOf(ChangeId("abc123def456", "abc123de", null)),
                listOf(Bookmark("main"), Bookmark("feature"))
            )

            result shouldBe listOf("rebase", "-r", "abc123def456", "-d", "main", "-d", "feature")
        }

        @Test
        fun `multiple destinations with insert after`() {
            val result = rebaseArgs(
                listOf(ChangeId("abc123def456", "abc123de", null)),
                listOf(
                    ChangeId("aaa111bbb222", "aaa111bb", null),
                    ChangeId("ccc333ddd444", "ccc333dd", null)
                ),
                destinationMode = RebaseDestinationMode.INSERT_AFTER
            )

            result shouldBe listOf("rebase", "-r", "abc123def456", "-A", "aaa111bbb222", "-A", "ccc333ddd444")
        }
    }

    @Nested
    inner class `mixed revision types` {
        @Test
        fun `working copy as source`() {
            val result = rebaseArgs(
                listOf(WorkingCopy),
                listOf(Bookmark("main")),
                RebaseSourceMode.BRANCH
            )

            result shouldBe listOf("rebase", "-b", "@", "-d", "main")
        }

        @Test
        fun `change id as destination`() {
            val result = rebaseArgs(
                listOf(ChangeId("abc123def456", "abc123de", null)),
                listOf(ChangeId("fed987cba654", "fed987cb", null))
            )

            result shouldBe listOf("rebase", "-r", "abc123def456", "-d", "fed987cba654")
        }

        @Test
        fun `revision expression as destination`() {
            val result = rebaseArgs(
                listOf(ChangeId("abc123def456", "abc123de", null)),
                listOf(RevisionExpression("main@origin"))
            )

            result shouldBe listOf("rebase", "-r", "abc123def456", "-d", "main@origin")
        }
    }

    @Nested
    inner class `default parameters` {
        @Test
        fun `defaults to REVISION source mode and ONTO destination mode`() {
            val result = rebaseArgs(
                listOf(ChangeId("abc123def456", "abc123de", null)),
                listOf(Bookmark("main"))
            )

            result shouldBe listOf("rebase", "-r", "abc123def456", "-d", "main")
        }
    }
}
