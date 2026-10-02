package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.actions.git.PushAction
import `in`.kkkev.jjidea.actions.git.PushAction.Kind
import `in`.kkkev.jjidea.actions.git.parsePushPlan
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * jj-idea-spwt: the plugin's force-push/delete confirmations depend on parsing
 * `jj git push --dry-run`. Runs the real jj (whichever is on PATH, or the pinned one - see
 * contributing.md "Testing against multiple jj versions") and asserts the parser still understands it.
 */
@Tag("contract")
@RequiresJj
class PushDryRunContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli

    @BeforeEach
    fun setUp() {
        val remote = tempDir.resolve("remote.git")
        ProcessBuilder("git", "init", "--bare", "-q", remote.toString()).start().waitFor()
        val work = tempDir.resolve("work").also { it.toFile().mkdirs() }
        jj = JjCli(work)
        jj.init()
        jj.addGitRemote("origin", remote.toString())
        jj.createFile("a.txt", "a\n")
        jj.describe("one")
        jj.bookmarkCreate("main")
        jj.run("bookmark", "track", "main@origin") // may be a no-op before the first push
        check(
            jj.run("git", "push", "--bookmark", "main", "--allow-new").isSuccess ||
                jj.run("git", "push", "--bookmark", "main").isSuccess
        )
        jj.run("bookmark", "track", "main@origin")
    }

    private fun dryRun(vararg args: String): String {
        val r = jj.run("git", "push", "--dry-run", *args)
        return r.stderr + "\n" + r.stdout
    }

    @Test
    fun `deleting a pushed bookmark is reported as a delete`() {
        jj.run("bookmark", "delete", "main")
        parsePushPlan(dryRun("--bookmark", "main")) shouldContainExactly listOf(PushAction(Kind.DELETE, "main"))
    }

    @Test
    fun `moving a pushed bookmark to a diverging commit is reported as sideways`() {
        val new = jj.run("new", "main@origin-", "-m", "side")
        check(new.isSuccess) { new.stderr }
        jj.createFile("b.txt", "b\n")
        check(jj.run("bookmark", "set", "main", "-r", "@", "--allow-backwards").isSuccess)
        parsePushPlan(dryRun("--bookmark", "main")) shouldContainExactly listOf(PushAction(Kind.MOVE_SIDEWAYS, "main"))
    }
}
