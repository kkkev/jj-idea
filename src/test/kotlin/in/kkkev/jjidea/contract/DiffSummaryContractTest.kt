package `in`.kkkev.jjidea.contract

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class DiffSummaryContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjCli

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
    }

    @Test
    fun `diff summary shows added files`() {
        jj.createFile("new.txt", "content")

        val result = jj.run("diff", "--summary", "-r", "@")
        result.isSuccess shouldBe true

        val lines = parseDiffLines(result.stdout)
        lines shouldHaveSize 1
        lines[0] shouldBe DiffLine('A', "new.txt")
    }

    @Test
    fun `diff summary shows modified files`() {
        jj.createFile("file.txt", "original")
        jj.newChange()
        jj.createFile("file.txt", "modified")

        val result = jj.run("diff", "--summary", "-r", "@")
        result.isSuccess shouldBe true

        val lines = parseDiffLines(result.stdout)
        lines shouldHaveSize 1
        lines[0] shouldBe DiffLine('M', "file.txt")
    }

    @Test
    fun `diff summary shows deleted files`() {
        jj.createFile("doomed.txt", "bye")
        jj.newChange()
        tempDir.resolve("doomed.txt").toFile().delete()

        val result = jj.run("diff", "--summary", "-r", "@")
        result.isSuccess shouldBe true

        val lines = parseDiffLines(result.stdout)
        lines shouldHaveSize 1
        lines[0] shouldBe DiffLine('D', "doomed.txt")
    }

    @Test
    fun `diff summary lines match status-space-path format`() {
        jj.createFile("a.txt", "aaa")
        jj.createFile("dir/b.txt", "bbb")

        val result = jj.run("diff", "--summary", "-r", "@")
        result.isSuccess shouldBe true

        result.stdout.trim().lines().forEach { line ->
            line shouldMatch Regex("[MADR] .+")
        }
    }

    @Test
    fun `diff summary for multiple file operations`() {
        jj.createFile("keep.txt", "original")
        jj.createFile("remove.txt", "will go")
        jj.newChange()
        jj.createFile("keep.txt", "modified")
        jj.createFile("added.txt", "new")
        tempDir.resolve("remove.txt").toFile().delete()

        val result = jj.run("diff", "--summary", "-r", "@")
        result.isSuccess shouldBe true

        val lines = parseDiffLines(result.stdout)
        lines shouldHaveSize 3
    }

    private data class DiffLine(val status: Char, val path: String)

    private fun parseDiffLines(output: String) = output.trim().lines()
        .filter { it.isNotBlank() && it.length >= 3 }
        .map { DiffLine(it[0], it.substring(2).trim()) }
}
