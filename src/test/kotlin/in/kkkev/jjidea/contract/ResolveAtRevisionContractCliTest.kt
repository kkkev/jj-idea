package `in`.kkkev.jjidea.contract

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Evidence for jj-idea-cf2c (S4 spike, `docs/design/jj-idea-n6fz-native-conflict-ux.md` § S4):
 * does `jj resolve -r <rev>` at a **non-working-copy** revision behave the way a UI that retires
 * `resolveConflictsAvailability.kt`'s `NEEDS_EDIT` would need it to? [JjStub] has no real conflict
 * materialisation or rebase semantics to fake this against, so - like
 * [FileShowConflictContractCliTest] - this is CLI-only.
 *
 * Topology (mirrors `scripts/fixtures/fx-conflict.sh`, extended with descendants):
 * `initial -> change-a / change-b` (divergent edits to the same line), `change-a` rebased onto
 * `change-b` conflicts, then `change-c -> change-d` stack on top of the conflicted `change-a`
 * and inherit the conflict.
 */
@Tag("contract")
@RequiresJj
class ResolveAtRevisionContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
    }

    /** Builds change-a/change-b/conflict, then a change-c -> change-d stack on top of change-a. */
    private fun buildConflictWithDescendants() {
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
        val newC = jj.run("new", "change-a", "-m", "change C")
        check(newC.isSuccess) { "jj new failed: ${newC.stderr}" }
        jj.bookmarkCreate("change-c")
        val newD = jj.run("new", "-m", "change D")
        check(newD.isSuccess) { "jj new failed: ${newD.stderr}" }
        jj.bookmarkCreate("change-d")
        // Leave @ on change-d for setup; individual tests re-`jj edit` as needed.
    }

    private fun conflictFlag(revset: String): String {
        val result = jj.run("log", "-r", revset, "--no-graph", "-T", "conflict ++ \"\\n\"")
        check(result.isSuccess) { "jj log failed: ${result.stderr}" }
        return result.stdout.trim()
    }

    private fun changeId(revset: String): String {
        val result = jj.run("log", "-r", revset, "--no-graph", "-T", "change_id")
        check(result.isSuccess) { "jj log failed: ${result.stderr}" }
        return result.stdout.trim()
    }

    private fun commitId(revset: String): String {
        val result = jj.run("log", "-r", revset, "--no-graph", "-T", "commit_id")
        check(result.isSuccess) { "jj log failed: ${result.stderr}" }
        return result.stdout.trim()
    }

    @Test
    fun `resolve at a non-working-copy revision succeeds and propagates to descendants`() {
        buildConflictWithDescendants()
        val edit = jj.run("edit", "change-b") // @ is a clean sibling, untouched by the conflict.
        check(edit.isSuccess) { "jj edit failed: ${edit.stderr}" }

        val changeIdABefore = changeId("change-a")
        val changeIdCBefore = changeId("change-c")
        val changeIdDBefore = changeId("change-d")
        val commitIdCBefore = commitId("change-c")

        conflictFlag("change-a") shouldBe "true"
        conflictFlag("change-c") shouldBe "true"
        conflictFlag("change-d") shouldBe "true"

        val resolve = jj.run("resolve", "-r", "change-a", "--tool", ":ours", "file.txt")

        resolve.isSuccess shouldBe true

        // The conflict clears at change-a and propagates to both descendants.
        conflictFlag("change-a") shouldBe "false"
        conflictFlag("change-c") shouldBe "false"
        conflictFlag("change-d") shouldBe "false"

        // Change ids (jj's stable identity) survive the rebase; only commit ids move.
        changeId("change-a") shouldBe changeIdABefore
        changeId("change-c") shouldBe changeIdCBefore
        changeId("change-d") shouldBe changeIdDBefore
        commitId("change-c") shouldNotBe commitIdCBefore

        // @ was outside the rewritten set: unaffected.
        val status = jj.run("log", "-r", "@", "--no-graph", "-T", "bookmarks")
        check(status.isSuccess) { "jj log failed: ${status.stderr}" }
        status.stdout shouldBe "change-b"
    }

    @Test
    fun `resolving an ancestor of the working copy rewrites the working copy underneath the user`() {
        buildConflictWithDescendants()
        val edit = jj.run("edit", "change-c") // @ is a descendant of the conflict.
        check(edit.isSuccess) { "jj edit failed: ${edit.stderr}" }

        val commitIdWcBefore = commitId("@")
        conflictFlag("@") shouldBe "true"

        val resolve = jj.run("resolve", "-r", "change-a", "--tool", ":ours", "file.txt")

        resolve.isSuccess shouldBe true

        // @ itself was rewritten and its conflict cleared - this is the fact a UI warning
        // needs to communicate before the user commits to resolving an ancestor.
        conflictFlag("@") shouldBe "false"
        commitId("@") shouldNotBe commitIdWcBefore

        // The on-disk working copy reflects the new, resolved content - no `jj new`/`jj edit`
        // needed to see it.
        val content = tempDir.resolve("file.txt").toFile().readText()
        content shouldBe "line 1\nchanged by B\nline 3\n"
    }
}
