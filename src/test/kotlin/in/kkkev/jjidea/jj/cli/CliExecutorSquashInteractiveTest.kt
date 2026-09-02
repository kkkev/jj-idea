package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [squashIntoInteractiveArgs] — the argument building logic used by
 * [CliExecutor.squashIntoInteractive], the squash analog of [splitInteractiveArgs].
 */
class CliExecutorSquashInteractiveTest {
    private val source = ChangeId("abc123def456", "abc123de", null)
    private val dest = ChangeId("fff111222333", "fff11122", null)

    @Nested
    inner class `basic wiring` {
        @Test
        fun `squash --from source --into dest --tool tool`() {
            val result = squashIntoInteractiveArgs(source, dest, tool = "jj-idea-hunk-apply").args

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--tool=jj-idea-hunk-apply"
            )
        }

        @Test
        fun `working copy as source`() {
            val result = squashIntoInteractiveArgs(WorkingCopy, dest, tool = "jj-idea-hunk-apply").args

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                WorkingCopy.REF,
                "--tool=jj-idea-hunk-apply"
            )
        }

        @Test
        fun `no filesets are ever passed`() {
            val result = squashIntoInteractiveArgs(source, dest, tool = "t").args

            result.none { it.startsWith("cwd:") } shouldBe true
            result.contains("--") shouldBe false
        }
    }

    @Nested
    inner class `config args precede the subcommand` {
        @Test
        fun `single config arg`() {
            val result = squashIntoInteractiveArgs(
                source,
                dest,
                configArgs = listOf("merge-tools.t.program=/usr/bin/java"),
                tool = "t"
            ).args

            result shouldBe listOf(
                "--config",
                "merge-tools.t.program=/usr/bin/java",
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--tool=t"
            )
        }

        @Test
        fun `multiple config args preserve order`() {
            val result = squashIntoInteractiveArgs(
                source,
                dest,
                configArgs = listOf("a=1", "b=2", "c=3"),
                tool = "t"
            ).args

            result.take(6) shouldBe listOf("--config", "a=1", "--config", "b=2", "--config", "c=3")
            result.subList(6, result.size) shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--tool=t"
            )
        }

        @Test
        fun `no config args means no --config flags`() {
            val result = squashIntoInteractiveArgs(source, dest, tool = "t").args

            result.none { it == "--config" } shouldBe true
        }
    }

    @Nested
    inner class `description and keep-emptied` {
        @Test
        fun `message is passed inline, never via editor`() {
            val result = squashIntoInteractiveArgs(source, dest, description = Description("Combined"), tool = "t").args

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--message=Combined",
                "--tool=t"
            )
        }

        @Test
        fun `keep-emptied flag`() {
            val result = squashIntoInteractiveArgs(source, dest, keepEmptied = true, tool = "t").args

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--keep-emptied",
                "--tool=t"
            )
        }

        @Test
        fun `all options together`() {
            val result = squashIntoInteractiveArgs(
                source,
                dest,
                description = Description("All"),
                keepEmptied = true,
                configArgs = listOf("k=v"),
                tool = "jj-idea-hunk-apply"
            ).args

            result shouldBe listOf(
                "--config",
                "k=v",
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--message=All",
                "--keep-emptied",
                "--tool=jj-idea-hunk-apply"
            )
        }
    }

    @Test
    fun `squash into interactive is reversible`() {
        squashIntoInteractiveArgs(source, dest, tool = "t").reversibility shouldBe Reversibility.REVERSIBLE
    }
}
