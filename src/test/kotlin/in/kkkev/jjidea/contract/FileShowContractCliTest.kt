package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.toFileset
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class FileShowContractCliTest : FileShowContractTest() {
    override fun createBackend(tempDir: Path) = JjCli(tempDir)

    // Regression tests for GitHub #73: paths with fileset meta-characters (parens, brackets —
    // e.g. Next.js app-router routes) were parsed as fileset expressions instead of literal
    // paths, so `jj file show` failed and the diff pane showed "Cannot get content from this
    // revision." These pin the fix: the plugin must pass paths wrapped via `toFileset()`.

    @Test
    fun `file show succeeds for a path with parens and brackets when wrapped as a fileset`() {
        val content = "hello\n"
        val path = "app/(app)/users/[id]/settings.tsx"
        jj.createFile(path, content)

        val result = jj.run("file", "show", "-r", "@", path.toFileset())

        result.isSuccess shouldBe true
        result.stdout shouldBe content
    }

    @Test
    fun `file show fails for the same path when passed bare, demonstrating the bug`() {
        val path = "app/(app)/users/[id]/settings.tsx"
        jj.createFile(path, "hello\n")

        val result = jj.run("file", "show", "-r", "@", path)

        result.isSuccess shouldBe false
    }

    // jj-idea-zf1j / GitHub #133: DiffbaseContentLoader relies on `file show` failing but
    // `file list` succeeding with empty output for a path absent at the revision.

    @Test
    fun `file show fails but file list is empty for a path absent at the revision`() {
        jj.createFile("base.txt", "base\n")
        jj.newChange("base done")
        jj.createFile("foo.rs", "foo\n")

        val show = jj.run("file", "show", "-r", "@-", "foo.rs".toFileset())
        val list = jj.run("file", "list", "-r", "@-", "foo.rs".toFileset())

        show.isSuccess shouldBe false
        list.isSuccess shouldBe true
        list.stdout.isBlank() shouldBe true
    }
}
