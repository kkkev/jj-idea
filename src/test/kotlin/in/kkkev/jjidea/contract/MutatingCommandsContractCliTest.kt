package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.actions.change.parseRemainingChangeId
import `in`.kkkev.jjidea.jj.cli.toFileset
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class MutatingCommandsContractCliTest : MutatingCommandsContractTest() {
    override fun createBackend(tempDir: Path) = JjCli(tempDir)

    // Regression tests for GitHub #73: see FileShowContractCliTest for background. These cover
    // the remaining path-taking mutating commands (split is already covered by the shared
    // MutatingCommandsContractTest suite, but not with a meta-character path).

    private val metaCharPath = "app/(app)/users/[id]/settings.tsx"

    @Test
    fun `split succeeds for a path with parens and brackets when wrapped as a fileset`() {
        jj.createFile(metaCharPath, "content\n")
        jj.createFile("other.txt", "other\n")
        jj.describe("Both files")

        val result = jj.run("split", "-r", "@", "-m", "First part", metaCharPath.toFileset())

        result.isSuccess shouldBe true
    }

    @Test
    fun `restore succeeds for a path with parens and brackets when wrapped as a fileset`() {
        jj.createFile(metaCharPath, "original\n")
        jj.describe("Original version")
        jj.newChange()
        jj.createFile(metaCharPath, "modified\n")

        val result = jj.run("restore", "-f", "@-", metaCharPath.toFileset())

        result.isSuccess shouldBe true
    }

    @Test
    fun `file untrack and re-track succeed for a path with parens and brackets when wrapped as a fileset`() {
        // `jj file untrack` requires the path to be ignored (docs/jj-track-untrack-model.md), so
        // gitignore it first. `[` and `]` are also gitignore glob meta-characters and must be
        // escaped there too, or the pattern silently matches the wrong thing - unrelated to the
        // fileset bug under test, but needed to set up a genuinely-ignored path.
        jj.createFile(".gitignore", "app/(app)/users/\\[id\\]/settings.tsx\n")
        jj.createFile(metaCharPath, "content\n")
        jj.describe("Add file")

        val untrackResult = jj.run("file", "untrack", metaCharPath.toFileset())
        untrackResult.isSuccess shouldBe true

        val trackResult = jj.run("file", "track", "--include-ignored", metaCharPath.toFileset())
        trackResult.isSuccess shouldBe true
    }

    // -- `jj split --parallel` (jj-idea-8khi, GitHub #101 UX follow-up) --
    //
    // No `--parallel` coverage existed anywhere in this suite before. These three pin down the
    // facts SplitDialog's redesigned wording depends on, verified against real jj 0.44 (not the
    // stub, which doesn't model --parallel at all - see JjStub.cmdSplit).

    // Full (unabbreviated) change id, so string comparisons don't depend on shortest-unique-prefix
    // rendering differing between the log template and jj's own stderr messages.
    private fun changeId(revset: String) = jj.run("log", "-r", revset, "--no-graph", "-T", "change_id").stdout.trim()

    // Note: `-m` re-describes the *selected* fileset side (jj's "Selected changes" line), not
    // the remaining side - same routing SplitDialog.doOKAction uses for `selectedDescription`.
    // So identifying a side by its post-split description only works for the selected side;
    // the remaining side is identified via `parseRemainingChangeId` on stderr instead, exactly
    // as the plugin itself does (actions/change/splitAction.kt).

    @Test
    fun `split --parallel keeps the original change ID on the selected (fileset) side`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Original commit")
        val originalChangeId = changeId("@")

        // a.txt is the fileset passed on the command line ("Selected changes" in jj's own
        // output) - b.txt is left as the "Remaining changes" side, which becomes the new sibling.
        val result = jj.run("split", "-r", "@", "--parallel", "-m", "Selected part", "a.txt")
        result.isSuccess shouldBe true
        result.stderr shouldContain "Selected changes"

        // The original change ID still resolves and now carries the -m description, proving it's
        // the selected (fileset) side that kept it - same invariant as the no-flag default and
        // -B, just with a sibling instead of a child/parent (SplitDialog.kt's class KDoc,
        // SplitSpec's KDoc).
        val afterLog = jj.run("log", "-r", originalChangeId, "--no-graph", "-T", "description")
        afterLog.stdout shouldBe "Selected part\n"

        // The remaining side (b.txt) got a genuinely new change ID.
        val remainingChangeId = parseRemainingChangeId(result.stderr)
        remainingChangeId.shouldNotBeNull()
        changeId(remainingChangeId.full) shouldNotBe originalChangeId
    }

    @Test
    fun `split --parallel on the working copy leaves the working copy on the new sibling`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Original commit")
        val originalChangeId = changeId("@")

        // Splitting @ always leaves the working copy on jj's "Remaining changes" side (verified
        // separately for the no-flag default and -B) - under --parallel that side is the new
        // sibling commit, not the side that kept the original change ID. So the dialog's existing
        // "the working copy (@) moves to the new commit" wording (dialog.split.wc.moves) already
        // covers parallel mode correctly; no separate parallel-specific working-copy string is
        // needed.
        val result = jj.run("split", "-r", "@", "--parallel", "-m", "Selected part", "a.txt")
        result.isSuccess shouldBe true

        val remainingChangeId = parseRemainingChangeId(result.stderr)
        remainingChangeId.shouldNotBeNull()

        val wcChangeId = changeId("@")
        wcChangeId shouldBe changeId(remainingChangeId.full)
        wcChangeId shouldNotBe originalChangeId
    }

    @Test
    fun `split --parallel makes an existing child a merge of both new siblings`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Target")
        val targetChangeId = changeId("@")
        jj.newChange("Child of target")

        val result = jj.run("split", "-r", targetChangeId, "--parallel", "-m", "Selected part", "a.txt")
        result.isSuccess shouldBe true
        result.stderr shouldContain "Rebased"
        result.stderr shouldContain "descendant"

        // The child now has two parents: the original change ID and the new sibling.
        val childParents = jj.run(
            "log",
            "-r",
            "description(exact:\"Child of target\\n\")",
            "--no-graph",
            "-T",
            "parents.map(|p| p.change_id().short()).join(\",\")"
        )
        childParents.stdout.trim().split(",").size shouldBe 2
    }
}
