package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class AnnotateContractCliTest : AnnotateContractTest() {
    override fun createBackend(tempDir: Path) = JjCli(tempDir)

    // Regression test for GitHub #73 (see FileShowContractCliTest for background). Unlike the
    // other path-taking commands, `jj file annotate` takes a single literal <PATH>, not a
    // <FILESETS>... list, so the bare path is already correct here - no `toFileset()` wrapping.
    // This pins that: the bare meta-character path must keep working un-wrapped.
    @Test
    fun `annotate succeeds for a path with parens and brackets passed bare`() {
        val path = "app/(app)/users/[id]/settings.tsx"
        jj.createFile(path, "hello\n")
        jj.describe("Initial content")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, path)

        result.isSuccess shouldBe true
    }
}
