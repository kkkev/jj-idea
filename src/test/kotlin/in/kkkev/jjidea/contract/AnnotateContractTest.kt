package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class AnnotateContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjBackend

    abstract fun createBackend(tempDir: Path): JjBackend

    @BeforeEach
    fun setUp() {
        jj = createBackend(tempDir)
        jj.init()
    }

    @Test
    fun `annotate output has correct field count per line`() {
        jj.createFile("test.txt", "line1\nline2\nline3\n")
        jj.describe("Initial content")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        result.isSuccess shouldBe true

        val records = parseRawRecords(result.stdout)

        records.size shouldBe 3
    }

    @Test
    fun `annotate timestamps are numeric`() {
        jj.createFile("test.txt", "hello\n")
        jj.describe("Test")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        result.isSuccess shouldBe true

        val records = parseRawRecords(result.stdout)

        records.forEach { record ->
            record[7].toLong() shouldBeGreaterThan 0L // author timestamp
        }
    }

    @Test
    fun `annotate change id fields have expected format`() {
        jj.createFile("test.txt", "content\n")
        jj.describe("Test")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        val records = parseRawRecords(result.stdout)

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
        val records = parseRawRecords(result.stdout)

        records[0][10] shouldBe "first\n"
        records[1][10] shouldBe "second\n"
        records[2][10] shouldBe "third\n"
    }

    @Test
    fun `annotate across multiple changes`() {
        jj.createFile("test.txt", "line1\n")
        jj.describe("First commit")
        jj.newChange("Second commit")
        jj.createFile("test.txt", "line1\nline2\n")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        result.isSuccess shouldBe true

        val records = parseRawRecords(result.stdout)

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

    // Regression test for jj-idea-3191: annotating a file whose content contains a literal null
    // byte (e.g. Kotlin source like `stdout.split('\u0000')`) used to throw
    // NumberFormatException, because the parser split its *entire* output on "\0" and the
    // embedded byte shifted every field of the following line by one.
    @Test
    fun `annotate succeeds on a file whose content contains a null byte`() {
        val nulByte = "\u0000"
        jj.createFile("test.txt", "before$nulByte-after\nnext line\n")
        jj.describe("Content with a null byte")

        val result = jj.run("file", "annotate", "-r", "@", "-T", AnnotationParser.TEMPLATE, "test.txt")
        result.isSuccess shouldBe true

        val parsed = AnnotationParser.parse(result.stdout)
        parsed.size shouldBe 2
        parsed[0].lineContent shouldBe "before$nulByte-after\n"
        parsed[1].lineContent shouldBe "next line\n"
        // The line *after* the null byte must not have had its fields shifted.
        parsed[1].id.full.shouldNotBeBlank()
    }

    /**
     * Parses raw `jj file annotate` output the same way [AnnotationParser.parse] does: one
     * record per source line (delimited by the "\n" that `content` itself ends with), fields
     * within a record delimited by "\0" — see AnnotationParser's doc comment for why records
     * can't be delimited by "\0" (jj-idea-3191).
     */
    private fun parseRawRecords(stdout: String): List<List<String>> {
        val records = stdout.split(RECORD_SEPARATOR_KEEP_DELIMITER).dropLastWhile { it.isEmpty() }
        return records
            .map { it.split(FIELD_SEPARATOR, limit = FIELDS_PER_LINE) }
            .filter { it.size == FIELDS_PER_LINE }
    }

    companion object {
        private const val FIELDS_PER_LINE = 11
        private const val FIELD_SEPARATOR = "\u0000"
        private val RECORD_SEPARATOR_KEEP_DELIMITER = Regex("(?<=\n)")
    }
}
