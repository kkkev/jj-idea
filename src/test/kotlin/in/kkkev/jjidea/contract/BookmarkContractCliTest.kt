package `in`.kkkev.jjidea.contract

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class BookmarkContractCliTest : BookmarkContractTest() {
    override fun createBackend(tempDir: Path) = JjCli(tempDir)

    /**
     * Regression test for jj-idea-tvch: the move-bookmark direction classifiers (BookmarkClassifier,
     * MoveBookmarkDialog, MoveBookmarkToChangeDialog) build an ancestor/descendant revset from a *union* of
     * change ids. If that union contains an unqualified divergent change id, jj refuses to resolve the whole
     * expression — even the non-divergent members — which silently defaulted every candidate to "backward or
     * sideways" in the dialogs. Offset-qualifying every id (matching [in.kkkev.jjidea.jj.ChangeId.full]) fixes it.
     *
     * Divergence is produced the same way a real user hits it (see MEMORY.md's "Parallel Session Isolation" note
     * and this repo's own history): two `describe`s at the same parent operation, which `jj` auto-resolves into
     * two revisions sharing one change id with different offsets.
     */
    @Test
    fun `union revset with a divergent change id requires offset qualification`() {
        val cli = jj as JjCli
        val opBeforeDescribe = cli.run("op", "log", "--no-graph", "-T", "id ++ \"\\n\"").stdout.lines().first()
        jj.describe("A")
        val changeId = cli.run("log", "-r", "@", "--no-graph", "-T", "change_id").stdout.trim()

        val fork = cli.run("--at-op", opBeforeDescribe, "describe", "-m", "B")
        fork.isSuccess shouldBe true

        val unqualified = cli.run("log", "-r", "($changeId) & ::@", "--no-graph", "-T", "change_id")
        unqualified.isSuccess shouldBe false
        unqualified.stderr.contains("divergent") shouldBe true

        val qualified = cli.run(
            "log",
            "-r",
            "($changeId/0 | $changeId/1) & ::@",
            "--no-graph",
            "-T",
            "change_id ++ if(divergent, \"/\" ++ change_offset, \"\")"
        )
        qualified.isSuccess shouldBe true
        qualified.stdout.trim() shouldBe "$changeId/1"
    }
}
