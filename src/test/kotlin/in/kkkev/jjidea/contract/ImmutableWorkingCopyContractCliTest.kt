package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.immutableWorkingCopyMoved
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins the jj behaviour the immutable-working-copy handling relies on (jj-idea-bzkf, GitHub #143):
 * a snapshot of an edited immutable `@` creates a child and warns; no edit leaves `@` alone; and
 * `squash --ignore-immutable -u` can fold the child back.
 */
@Tag("contract")
@RequiresJj
class ImmutableWorkingCopyContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli

    private fun ids(revset: String = "@") =
        jj.run("log", "--ignore-working-copy", "--no-graph", "-r", revset, "-T", "change_id ++ \"\\n\"")
            .stdout.trim()

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
        jj.createFile("a.txt", "base\n")
        jj.run("commit", "-m", "base").also { check(it.isSuccess) { it.stderr } }
        jj.run("bookmark", "create", "main", "-r", "@-").also { check(it.isSuccess) { it.stderr } }
        jj.run("config", "set", "--repo", "revset-aliases.\"trunk()\"", "main")
            .also { check(it.isSuccess) { it.stderr } }
        jj.run("edit", "--ignore-immutable", "main").also { check(it.isSuccess) { it.stderr } }
    }

    @Test
    fun `snapshot of an edited immutable working copy creates a child and warns`() {
        val trunk = ids()
        jj.createFile("a.txt", "base\nedited\n")

        val status = jj.run("status")

        immutableWorkingCopyMoved(status.stderr) shouldBe true
        ids("@-") shouldBe trunk
        (ids() == trunk) shouldBe false
        jj.run("op", "log", "--ignore-working-copy", "--no-graph", "-n", "1", "-T", "snapshot ++ \" \" ++ description")
            .stdout.trim() shouldBe "true snapshot working copy"
    }

    @Test
    fun `without an edit the working copy stays put and nothing warns`() {
        val trunk = ids()

        val status = jj.run("status")

        immutableWorkingCopyMoved(status.stderr) shouldBe false
        ids() shouldBe trunk
    }

    @Test
    fun `squash --ignore-immutable -u folds the child back, leaving an empty working copy on top`() {
        val trunk = ids()
        jj.createFile("a.txt", "base\nedited\n")
        jj.run("status")
        val child = ids()

        val squash = jj.run(
            "squash",
            "--from",
            child,
            "--into",
            trunk,
            "--use-destination-message",
            "--ignore-immutable"
        )

        squash.isSuccess shouldBe true
        ids("@-") shouldBe trunk
        jj.run("diff", "--ignore-working-copy", "-r", "@", "--summary").stdout.trim() shouldBe ""
        jj.run("file", "show", "--ignore-working-copy", "-r", trunk, "a.txt").stdout shouldBe "base\nedited\n"
    }
}
