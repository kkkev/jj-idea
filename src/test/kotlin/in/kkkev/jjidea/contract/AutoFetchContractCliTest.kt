package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.gitFetchArgs
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * jj-idea-crik: the automatic fetch passes `--ignore-working-copy` so a background fetch never writes
 * a "snapshot working copy" operation of its own (which would become the target of Undo). Verifies
 * against real jj that the flag has that effect, that a plain fetch really does snapshot a dirty
 * working copy (so the flag is needed), and that a fetch which changes nothing still succeeds.
 */
@Tag("contract")
@RequiresJj
class AutoFetchContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var upstream: JjCli
    private lateinit var clone: JjCli
    private var pushes = 0

    @BeforeEach
    fun setUp() {
        val remote = tempDir.resolve("remote.git")
        ProcessBuilder("git", "init", "--bare", "-q", remote.toString()).start().waitFor()
        val upstreamDir = tempDir.resolve("upstream").also { it.toFile().mkdirs() }
        upstream = JjCli(upstreamDir)
        upstream.init()
        upstream.addGitRemote("origin", remote.toString())
        push()
        check(JjCli(tempDir).run("git", "clone", remote.toString(), "clone").isSuccess)
        clone = JjCli(tempDir.resolve("clone"))
    }

    /** Commits a new described change upstream and pushes it as `main`. */
    private fun push() {
        pushes++
        upstream.createFile("f.txt", "$pushes\n")
        check(upstream.run("commit", "-m", "c$pushes").isSuccess)
        check(upstream.run("bookmark", "set", "main", "-r", "@-").isSuccess)
        val r = upstream.run("git", "push", "--bookmark", "main")
        check(r.isSuccess) { r.stderr }
    }

    private fun fetch(ignoreWorkingCopy: Boolean) =
        clone.run(*gitFetchArgs(ignoreWorkingCopy = ignoreWorkingCopy).args.toTypedArray())

    private fun recentOps() = clone
        .run("op", "log", "-n", "3", "--no-graph", "--ignore-working-copy", "-T", OP_DESCRIPTION)
        .stdout.lines().filter { it.isNotBlank() }

    @Test
    fun `fetch with ignore-working-copy writes no snapshot operation for a dirty working copy`() {
        clone.createFile("dirty.txt", "x\n")
        push()

        fetch(ignoreWorkingCopy = true).isSuccess shouldBe true

        recentOps().first() shouldBe "fetch from git remote(s) origin"
        recentOps().none { it == "snapshot working copy" } shouldBe true
    }

    @Test
    fun `plain fetch snapshots a dirty working copy first, which is why the flag is needed`() {
        clone.createFile("dirty.txt", "x\n")
        push()

        fetch(ignoreWorkingCopy = false).isSuccess shouldBe true

        recentOps().take(2) shouldContainExactly listOf("fetch from git remote(s) origin", "snapshot working copy")
    }

    private fun headId() = clone
        .run("op", "log", "-n", "1", "--no-graph", "--ignore-working-copy", "-T", OP_ID)
        .stdout.trim()

    @Test
    fun `fetch with nothing new succeeds and writes no operation at all`() {
        // The IDE fetches on a timer: if a no-change fetch wrote an operation, the op log (and Undo)
        // would fill with noise. It does not, so only a fetch that moved a ref shows up.
        fetch(ignoreWorkingCopy = true).isSuccess shouldBe true
        val head = headId()

        repeat(3) { fetch(ignoreWorkingCopy = true).isSuccess shouldBe true }
        headId() shouldBe head
    }

    @Test
    fun `fetch that moves a remote bookmark writes exactly one operation`() {
        val before = headId()
        push()

        fetch(ignoreWorkingCopy = true).isSuccess shouldBe true

        (headId() != before) shouldBe true
        recentOps().first() shouldBe "fetch from git remote(s) origin"
    }

    private companion object {
        const val OP_DESCRIPTION = "description.first_line() ++ \"\\n\""
        const val OP_ID = "id.short(12) ++ \"\\n\""
    }
}
