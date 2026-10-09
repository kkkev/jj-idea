package `in`.kkkev.jjidea.contract

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
