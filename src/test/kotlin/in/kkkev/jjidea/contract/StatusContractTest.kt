package `in`.kkkev.jjidea.contract

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class StatusContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjCli

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
    }

    @Test
    fun `status shows working copy changes header`() {
        jj.createFile("test.txt", "hello")

        val result = jj.run("status")
        result.isSuccess shouldBe true
        result.stdout shouldContain "Working copy changes:"
    }

    @Test
    fun `added file shows A status`() {
        jj.createFile("new-file.txt", "content")

        val result = jj.run("status")
        result.isSuccess shouldBe true

        val changeLines = parseStatusLines(result.stdout)
        changeLines shouldContain StatusLine('A', "new-file.txt")
    }

    @Test
    fun `modified file shows M status`() {
        jj.createFile("file.txt", "original")
        jj.newChange()
        jj.createFile("file.txt", "modified")

        val result = jj.run("status")
        result.isSuccess shouldBe true

        val changeLines = parseStatusLines(result.stdout)
        changeLines shouldContain StatusLine('M', "file.txt")
    }

    @Test
    fun `deleted file shows D status`() {
        jj.createFile("doomed.txt", "will be deleted")
        jj.newChange()
        tempDir.resolve("doomed.txt").toFile().delete()

        val result = jj.run("status")
        result.isSuccess shouldBe true

        val changeLines = parseStatusLines(result.stdout)
        changeLines shouldContain StatusLine('D', "doomed.txt")
    }

    @Test
    fun `status lines match expected format`() {
        jj.createFile("a.txt", "aaa")
        jj.createFile("b.txt", "bbb")

        val result = jj.run("status")
        result.isSuccess shouldBe true

        val changeLines = parseStatusLines(result.stdout)
        changeLines.forEach { line ->
            line.status shouldBe 'A'
            line.path.isNotEmpty() shouldBe true
        }
    }

    private data class StatusLine(val status: Char, val path: String)

    private fun parseStatusLines(output: String): List<StatusLine> {
        var inWorkingCopy = false
        return output.lines().mapNotNull { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Working copy changes:") -> {
                    inWorkingCopy = true
                    null
                }
                !inWorkingCopy || trimmed.isEmpty() || trimmed.length < 3 -> null
                trimmed.startsWith("Working copy") -> {
                    inWorkingCopy = false
                    null
                }
                else -> StatusLine(trimmed[0], trimmed.substring(2).trim())
            }
        }
    }
}
