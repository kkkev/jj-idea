package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [duplicateArgs] — the argument building logic used by [CliExecutor.duplicate].
 */
class CliExecutorDuplicateTest {
    private val revision = ChangeId("abc123def456", "abc123de", null)

    @Nested
    inner class `in place (no destination)` {
        @Test
        fun `single revision`() {
            val result = duplicateArgs(listOf(revision)).args

            result shouldBe listOf("duplicate", "abc123def456")
        }

        @Test
        fun `working copy`() {
            val result = duplicateArgs(listOf(WorkingCopy)).args

            result shouldBe listOf("duplicate", "@")
        }

        @Test
        fun `multiple revisions preserve order`() {
            val result = duplicateArgs(
                listOf(
                    ChangeId("aaa111bbb222", "aaa111bb", null),
                    ChangeId("bbb222ccc333", "bbb222cc", null),
                    ChangeId("ccc333ddd444", "ccc333dd", null)
                )
            ).args

            result shouldBe listOf("duplicate", "aaa111bbb222", "bbb222ccc333", "ccc333ddd444")
        }
    }

    @Nested
    inner class `with destination` {
        @Test
        fun `onto uses --onto flag`() {
            val result = duplicateArgs(listOf(revision), listOf(BookmarkName("main"))).args

            result shouldBe listOf("duplicate", "abc123def456", "--onto", "main")
        }

        @Test
        fun `insert after uses -A flag`() {
            val result = duplicateArgs(
                listOf(revision),
                listOf(BookmarkName("main")),
                RebaseDestinationMode.INSERT_AFTER
            ).args

            result shouldBe listOf("duplicate", "abc123def456", "-A", "main")
        }

        @Test
        fun `insert before uses -B flag`() {
            val result = duplicateArgs(
                listOf(revision),
                listOf(BookmarkName("main")),
                RebaseDestinationMode.INSERT_BEFORE
            ).args

            result shouldBe listOf("duplicate", "abc123def456", "-B", "main")
        }

        @Test
        fun `multiple destinations each get destination flag`() {
            val result = duplicateArgs(
                listOf(revision),
                listOf(BookmarkName("main"), BookmarkName("feature"))
            ).args

            result shouldBe listOf("duplicate", "abc123def456", "--onto", "main", "--onto", "feature")
        }

        @Test
        fun `multiple revisions with a destination`() {
            val result = duplicateArgs(
                listOf(
                    ChangeId("aaa111bbb222", "aaa111bb", null),
                    ChangeId("bbb222ccc333", "bbb222cc", null)
                ),
                listOf(BookmarkName("main"))
            ).args

            result shouldBe listOf("duplicate", "aaa111bbb222", "bbb222ccc333", "--onto", "main")
        }
    }

    @Test
    fun `duplicate is reversible`() {
        duplicateArgs(listOf(revision)).reversibility shouldBe Reversibility.REVERSIBLE
    }
}
