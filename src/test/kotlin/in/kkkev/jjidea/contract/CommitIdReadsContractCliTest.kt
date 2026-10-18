package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.CliLogService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * jj-idea-29l3 (spike for jj-idea-wiz0, GitHub #130): pins the jj behaviours the "reads address by
 * full commit_id, writes keep the change id" design relies on. Findings with verbatim output for
 * jj 0.37.0 (`JjVersion.MINIMUM`) and 0.44.0 are in `docs/jj-commit-id-reads.md`.
 *
 * CLI-only: [JjStub] cannot model hidden or divergent commits. Run against the minimum with a PATH
 * shim, e.g. `PATH=<dir containing jj -> jj-0.37.0>:$PATH ./gradlew contractTest --tests '*CommitIdReads*'`.
 *
 * Fixture: change A pushed to a bare remote as bookmark `cl/X` (commit a1), then amended locally
 * (commit a2). a1 is now hidden but is still the target of `cl/X@origin`.
 */
@Tag("contract")
@RequiresJj
class CommitIdReadsContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli
    private lateinit var changeId: String
    private lateinit var a1: String
    private lateinit var a2: String

    @BeforeEach
    fun setUp() {
        val remote = tempDir.resolve("remote.git")
        ProcessBuilder("git", "init", "--bare", "-q", remote.toString()).start().waitFor()
        val work = tempDir.resolve("work").also { it.toFile().mkdirs() }
        jj = JjCli(work)
        jj.init()
        jj.addGitRemote("origin", remote.toString())
        jj.createFile("f", "v1\n")
        jj.describe("A")
        jj.bookmarkCreate("cl/X")
        // `--allow-new` was dropped from `jj git push` in newer jj; fall back like PushDryRunContractCliTest.
        check(
            jj.run("git", "push", "--bookmark", "cl/X", "--allow-new").isSuccess ||
                jj.run("git", "push", "--bookmark", "cl/X").isSuccess
        )
        jj.run("bookmark", "track", "cl/X@origin")
        changeId = log("@", "change_id")
        a1 = log("@", "commit_id")
        jj.createFile("f", "v2\n")
        a2 = log("@", "commit_id") // `log` snapshots the working copy, amending the change in place
    }

    private fun log(revset: String, template: String): String {
        val r = jj.run("log", "--no-graph", "-r", revset, "-T", template)
        check(r.isSuccess) { "jj log -r $revset failed: ${r.stderr}" }
        return r.stdout.trim()
    }

    /** Makes the working-copy change divergent: two describes of one change racing from the same op. */
    private fun makeDivergent() {
        val op = jj.run("op", "log", "--no-graph", "--limit", "1", "-T", "id.short()").stdout.trim()
        check(jj.run("describe", "-r", "@", "-m", "left", "--at-op", op).isSuccess)
        check(jj.run("describe", "-r", "@", "-m", "right", "--at-op", op).isSuccess)
    }

    @Test
    fun `fixture amends the change so a1 is hidden and a2 is visible with the same change id`() {
        (a1 == a2) shouldBe false
        log(a1, "hidden") shouldBe "true"
        log(a2, "hidden") shouldBe "false"
        log(a1, "change_id") shouldBe changeId
    }

    @Test
    fun `file show by hidden commit_id returns the old content while the bare change id returns the visible one`() {
        jj.run("file", "show", "-r", a1, "f").stdout shouldBe "v1\n"
        jj.run("file", "show", "-r", changeId, "f").stdout shouldBe "v2\n" // the #130 bug
    }

    @Test
    fun `diff and log resolve a hidden commit_id with the default revset`() {
        val show = jj.run("diff", "-r", a1, "--git")
        show.isSuccess shouldBe true
        show.stdout shouldContain "+v1"
        val between = jj.run("diff", "--from", a1, "--to", a2, "--git")
        between.isSuccess shouldBe true
        between.stdout shouldContain "-v1"
        between.stdout shouldContain "+v2"
        log(a1, "commit_id") shouldBe a1
    }

    @Test
    fun `remote bookmark target template exposes the hidden commit_id`() {
        val target = "normal_target.commit_id() ++ \" \" ++ normal_target.hidden() ++ \"\\n\""
        val template = "if(remote == \"origin\", $target)"
        val r = jj.run("bookmark", "list", "--all-remotes", "cl/X", "-T", template)
        r.isSuccess shouldBe true
        r.stdout.trim() shouldBe "$a1 true"
    }

    @Test
    fun `template keywords hidden divergent change_offset commit_id are available`() {
        log(a1, "divergent ++ \" \" ++ change_offset ++ \" \" ++ hidden ++ \" \" ++ commit_id") shouldBe
            "false 1 true $a1"
    }

    @Test
    fun `bare divergent change id errors in single and multi revision args`() {
        makeDivergent()
        val expected = "Change ID `$changeId` is divergent"
        val attempts = listOf(
            listOf("log", "-r", changeId),
            listOf("file", "show", "-r", changeId, "f"),
            listOf("diff", "-r", changeId),
            listOf("diff", "--from", changeId, "--to", "@"),
            listOf("log", "-r", "$changeId | root()"),
            listOf("describe", "-r", changeId, "-m", "x")
        )
        attempts.forEach {
            val r = jj.run(*it.toTypedArray())
            (r.exitCode != 0) shouldBe true
            r.stderr shouldContain expected
        }
    }

    private fun readers(): Triple<CliExecutor, CliLogService, FilePath> {
        val root = mockk<VirtualFile> { every { path } returns tempDir.resolve("work").toString() }
        val executor = CliExecutor(root)
        val repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns executor
            every { directory } returns root
        }
        return Triple(executor, CliLogService(repo), LocalFilePath(tempDir.resolve("work/f").toString(), false))
    }

    @Test
    fun `hidden commit reads as itself through the log service and executor`() {
        val (executor, logService, file) = readers()

        val entry = logService.getLog(CommitId(a1)).getOrThrow().single()
        entry.isHidden shouldBe true
        entry.commitId.full shouldBe a1
        entry.parentCommitIds.map { it.full } shouldBe listOf(log("root()", "commit_id"))

        // The details pane reads the hidden commit by its commit id, never by the shared change id.
        entry.contentLocator.full shouldBe a1

        executor.show(file, CommitId(a1)).stdout shouldBe "v1\n"
        executor.show(file, ChangeId(changeId)).stdout shouldBe "v2\n" // what the change id alone would have read
        executor.diffSummary(CommitId(a1)).stdout shouldContain "A f"
    }

    @Test
    fun `annotating the working copy parent by its commit id yields every line`() {
        val (executor, logService, _) = readers()
        jj.newChange("child")
        jj.createFile("f", "v2\nmore\n") // working copy changed; the parent still has f
        val wc = logService.getLog(WorkingCopy).getOrThrow().single()

        val parent = wc.parentContentLocator as CommitId
        val file = mockk<VirtualFile> { every { path } returns tempDir.resolve("work/f").toString() }
        val result = executor.annotate(file, parent, AnnotationParser.TEMPLATE)

        result.shouldBeInstanceOf<CommandResult.Success>()
        AnnotationParser.parse(result.stdout).size shouldBe 1
    }

    @Test
    fun `a commit id jj cannot find is reported as no longer available`() {
        val (executor, _, file) = readers()

        val result = executor.show(file, CommitId("0123456789abcdef0123456789abcdef01234567"))

        result.shouldBeInstanceOf<CommandResult.Failure>().message shouldContain "no longer available"
    }

    @Test
    fun `executor maps a divergent write to Divergent and an offset write succeeds`() {
        makeDivergent()
        val root = mockk<VirtualFile> { every { path } returns tempDir.resolve("work").toString() }
        val executor = CliExecutor(root)

        executor.describe(Description("x"), ChangeId(changeId))
            .shouldBeInstanceOf<CommandResult.Failure.Divergent>().changeId shouldBe changeId
        executor.edit(ChangeId(changeId))
            .shouldBeInstanceOf<CommandResult.Failure.Divergent>().changeId shouldBe changeId
        executor.describe(Description("x"), ChangeId(changeId, offset = 0))
            .shouldBeInstanceOf<CommandResult.Success>()
    }

    @Test
    fun `divergent change id with offset resolves to one commit each`() {
        makeDivergent()
        val c0 = log("$changeId/0", "commit_id")
        val c1 = log("$changeId/1", "commit_id")
        c0 shouldNotBe c1
        log("change_id($changeId)", "commit_id ++ \"\\n\"").lines().toSet() shouldBe setOf(c0, c1)
    }

    @Test
    fun `hidden commit stays readable by commit_id after op abandon and gc`() {
        jj.createFile("g", "gone\n")
        val hidden = log("@", "commit_id")
        jj.createFile("g", "gone2\n")
        log("@", "commit_id") // snapshot: `hidden` is now an unreferenced-by-ref hidden commit
        val ops = jj.run("op", "log", "--no-graph", "--limit", "2", "-T", "id.short() ++ \"\\n\"").stdout.lines()
        check(jj.run("op", "abandon", "..${ops[1]}").isSuccess)
        check(jj.run("util", "gc", "--expire=now").isSuccess)
        jj.run("file", "show", "-r", hidden, "g").stdout shouldBe "gone\n"
        jj.run("file", "show", "-r", a1, "f").stdout shouldBe "v1\n"
    }

    @Test
    fun `at-op reads see the old state and do not snapshot the working copy`() {
        val op = jj.run("op", "log", "--no-graph", "--limit", "1", "-T", "id.short()").stdout.trim()
        jj.createFile("f", "v3\n")
        jj.run("--at-op", op, "file", "show", "-r", "@", "f").stdout shouldBe "v2\n"
        // The edit made before the --at-op read is still unsnapshotted by it.
        val after = jj.run("--ignore-working-copy", "op", "log", "--no-graph", "--limit", "1", "-T", "id.short()")
        after.stdout.trim() shouldBe op
    }
}
