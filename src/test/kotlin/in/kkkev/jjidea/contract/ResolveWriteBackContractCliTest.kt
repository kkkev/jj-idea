package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.diffedit.DiffEditTool
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Round-trips the jj-idea-cf2c (S4 spike) write-back prototype end to end against real jj:
 * stage a resolved file, register it as an ephemeral 3-way merge tool via
 * [DiffEditTool.mergeToolConfigArgs], and run `jj resolve -r <non-@> --tool <name>` - the same
 * mechanism `jj split --tool`/`jj squash --interactive` already use in production
 * ([DiffEditTool.diffEditConfigArgs]), applied to the merge-tool side of the protocol.
 *
 * Calls [DiffEditTool.mergeToolConfigArgs] itself rather than reimplementing it: its
 * classpath discovery falls back to `java.class.path` when the classloader isn't a plugin
 * [com.intellij.util.lang.UrlClassLoader] (true for this plain-JVM test), so this is a real
 * exercise of the production helper, not a hand-rolled substitute.
 */
@Tag("contract")
@RequiresJj
class ResolveWriteBackContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
    }

    @Test
    fun `interactive write-back resolves a non-working-copy conflict via an ephemeral merge tool`() {
        jj.createFile("file.txt", "line 1\nshared line\nline 3\n")
        jj.describe("initial")
        jj.newChange("change A")
        jj.createFile("file.txt", "line 1\nchanged by A\nline 3\n")
        jj.bookmarkCreate("change-a")
        val newB = jj.run("new", "-r", "change-a-", "-m", "change B")
        check(newB.isSuccess) { "jj new failed: ${newB.stderr}" }
        jj.createFile("file.txt", "line 1\nchanged by B\nline 3\n")
        jj.bookmarkCreate("change-b")
        val rebase = jj.run("rebase", "-r", "change-a", "-d", "change-b")
        check(rebase.isSuccess) { "jj rebase failed: ${rebase.stderr}" }
        val edit = jj.run("edit", "change-b") // @ stays off the conflict throughout.
        check(edit.isSuccess) { "jj edit failed: ${edit.stderr}" }

        val stagedFile = tempDir.resolve("staged-resolution.txt")
        stagedFile.writeText("line 1\nresolved by hand\nline 3\n")
        val toolName = "cf2c-spike-merge-apply"
        val configArgs = DiffEditTool.mergeToolConfigArgs(toolName, stagedFile)

        val resolveArgs = buildList {
            for (kv in configArgs) {
                add("--config")
                add(kv)
            }
            add("resolve")
            add("-r")
            add("change-a")
            add("--tool")
            add(toolName)
            add("file.txt")
        }
        val resolve = jj.run(*resolveArgs.toTypedArray())

        resolve.isSuccess shouldBe true
        val show = jj.run("file", "show", "-r", "change-a", "file.txt")
        show.isSuccess shouldBe true
        show.stdout shouldBe "line 1\nresolved by hand\nline 3\n"
        val conflictFlag = jj.run("log", "-r", "change-a", "--no-graph", "-T", "conflict")
        conflictFlag.stdout.trim() shouldBe "false"
    }
}
