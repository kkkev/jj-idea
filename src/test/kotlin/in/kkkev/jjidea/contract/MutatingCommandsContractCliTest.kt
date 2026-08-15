package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.toFileset
import io.kotest.matchers.shouldBe
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
}
