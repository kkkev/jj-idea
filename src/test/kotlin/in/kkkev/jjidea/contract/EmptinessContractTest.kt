package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.CliLogService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Real-jj coverage for jj-idea-2570.8: the log template skips `empty` for immutable merges (jj merges every parent
 * tree to answer it), and [CliLogService.getEmptiness] fetches the exact value. A clean merge equals the auto-merge of
 * its parents, so it is empty; a merge that also edits something (an "evil" merge) is not.
 */
@Tag("contract")
@RequiresJj
class EmptinessContractTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli
    private lateinit var logService: CliLogService

    private fun ok(vararg args: String) = jj.run(*args).also { check(it.isSuccess) { it.stderr } }

    private fun single(rev: String, template: String) =
        ok("log", "-r", rev, "--no-graph", "-T", template).stdout.trim()

    private fun commitIdOf(rev: String) = CommitId(single(rev, "commit_id"))

    private fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
        val root = mockk<VirtualFile> { every { path } returns tempDir.toString() }
        val repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns CliExecutor(root)
            every { directory } returns root
        }
        logService = CliLogService(repo)
    }

    /** a and b each add a file; returns (clean merge, merge that also edits a third file). */
    private fun buildMerges(): Pair<CommitId, CommitId> {
        ok("new", "root()", "-m", "a")
        jj.createFile("a.txt", "a")
        val a = single("@", "change_id") // snapshots a.txt
        ok("new", "root()", "-m", "b")
        jj.createFile("b.txt", "b")
        val b = single("@", "change_id")
        ok("new", a, b, "-m", "clean")
        val clean = commitIdOf("@")
        ok("new", a, b, "-m", "evil")
        jj.createFile("c.txt", "hand edit made as part of the merge")
        single("@", "change_id")
        return clean to commitIdOf("description(substring:evil)")
    }

    @Test
    fun `getEmptiness is exact for clean and evil merges in a single call`() {
        setUp()
        val (clean, evil) = buildMerges()

        logService.getEmptiness(listOf(clean, evil)).getOrThrow() shouldBe mapOf(clean to true, evil to false)
    }

    @Test
    fun `the log template defers rather than guesses, only for immutable merges`() {
        setUp()
        val (clean, _) = buildMerges()
        val pin = "revset-aliases.'immutable_heads()'='${clean.full}'"
        val template = CliLogService.LogFields().empty.spec

        fun templateEmpty(vararg config: String) =
            ok("log", *config, "-r", clean.full, "--no-graph", "-T", template).stdout.trim().substringBefore("\u0000")

        templateEmpty() shouldBe "true" // mutable: exact
        templateEmpty("--config", pin) shouldBe "false" // immutable merge: skipped (the entry is flagged deferred)

        val entry = logService.getLog(revset = Expression(clean.full)).getOrThrow().single()
        entry.emptyDeferred shouldBe false // mutable here, since the default immutable_heads() is trunk/untracked
        entry.templateEmpty shouldBe true
    }

    @Test
    fun `getEmptiness fails for an id that does not exist`() {
        setUp()

        val result = logService.getEmptiness(listOf(CommitId("0123456789abcdef0123456789abcdef01234567")))

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message.orEmpty() shouldContain "Error from jj log"
    }

    @Test
    fun `getEmptiness with no ids runs no command`() {
        setUp()

        logService.getEmptiness(emptyList()).getOrThrow() shouldBe emptyMap()
    }
}
