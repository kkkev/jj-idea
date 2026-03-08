package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class AnnotateContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjCli

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
    }

    @Test
    fun `annotate output has correct field count per line`() {
        jj.createFile("test.txt", "line1\nline2\nline3\n")
        jj.describe("Initial content")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        result.isSuccess shouldBe true

        val fields = result.stdout.trim().split("\u0000")
        val records = fields.chunked(FIELDS_PER_LINE).filter { it.size == FIELDS_PER_LINE }

        records.size shouldBe 3
    }

    @Test
    fun `annotate timestamps are numeric`() {
        jj.createFile("test.txt", "hello\n")
        jj.describe("Test")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        result.isSuccess shouldBe true

        val fields = result.stdout.trim().split("\u0000")
        val records = fields.chunked(FIELDS_PER_LINE).filter { it.size == FIELDS_PER_LINE }

        records.forEach { record ->
            record[7].toLong() shouldBeGreaterThan 0L // author timestamp
        }
    }

    @Test
    fun `annotate change id fields have expected format`() {
        jj.createFile("test.txt", "content\n")
        jj.describe("Test")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        val fields = result.stdout.trim().split("\u0000")
        val records = fields.chunked(FIELDS_PER_LINE).filter { it.size == FIELDS_PER_LINE }

        records.forEach { record ->
            record[0].length shouldBeGreaterThan 0 // full change id
            record[1].length shouldBeGreaterThan 0 // short change id
            // record[2] is offset, can be empty
            record[3].length shouldBeGreaterThan 0 // full commit id
            record[4].length shouldBeGreaterThan 0 // short commit id
        }
    }

    @Test
    fun `annotate line content matches file`() {
        val content = "first\nsecond\nthird\n"
        jj.createFile("test.txt", content)
        jj.describe("Content test")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        val fields = result.stdout.trim().split("\u0000")
        val records = fields.chunked(FIELDS_PER_LINE).filter { it.size == FIELDS_PER_LINE }

        records[0][9] shouldBe "first\n"
        records[1][9] shouldBe "second\n"
        records[2][9] shouldBe "third\n"
    }

    @Test
    fun `annotate across multiple changes`() {
        jj.createFile("test.txt", "line1\n")
        jj.describe("First commit")
        jj.newChange("Second commit")
        jj.createFile("test.txt", "line1\nline2\n")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        result.isSuccess shouldBe true

        val fields = result.stdout.trim().split("\u0000")
        val records = fields.chunked(FIELDS_PER_LINE).filter { it.size == FIELDS_PER_LINE }

        records.size shouldBe 2
        // Lines should come from different changes
        (records[0][0] != records[1][0]) shouldBe true
    }

    @Test
    fun `annotate output is parseable by AnnotationParser`() {
        jj.createFile("test.txt", "hello\nworld\n")
        jj.describe("Parse test")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        result.isSuccess shouldBe true

        val parsed = AnnotationParser.parse(result.stdout)
        parsed.size shouldBe 2
        parsed[0].lineNumber shouldBe 1
        parsed[1].lineNumber shouldBe 2
        parsed[0].lineContent shouldBe "hello\n"
        parsed[1].lineContent shouldBe "world\n"
    }

    companion object {
        private const val FIELDS_PER_LINE = 10
    }
}
