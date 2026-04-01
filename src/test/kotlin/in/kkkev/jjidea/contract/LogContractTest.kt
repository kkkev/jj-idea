package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.jj.cli.TemplateParts
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class LogContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjBackend

    abstract fun createBackend(tempDir: Path): JjBackend

    private val fields = CliLogService.LogFields()

    // Build the same template specs the plugin uses
    private val basicSpec = listOf(
        fields.changeId,
        fields.commitId,
        fields.description,
        fields.bookmarks,
        fields.parents,
        fields.currentWorkingCopy,
        fields.conflict,
        fields.empty,
        fields.immutable
    ).joinToString(" ++ ") { it.spec }

    private val fullSpec = basicSpec + " ++ " +
        fields.author.spec + " ++ " + fields.committer.spec

    private val bookmarkSpec = listOf(
        fields.run { singleField("name") { it } },
        fields.run { singleField(TemplateParts.qualifiedChangeId("normal_target")) { it } }
    ).joinToString(" ++ ") { it.spec }

    @BeforeEach
    fun setUp() {
        jj = createBackend(tempDir)
        jj.init()
    }

    @Test
    fun `basic log template produces correct field count`() {
        jj.describe("Initial commit")

        val result = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        result.isSuccess shouldBe true

        val fields = result.stdout.trim().split("\u0000")
        // 9 fields + trailing empty from final \0
        fields.size shouldBe BASIC_FIELD_COUNT + 1
    }

    @Test
    fun `full log template produces correct field count`() {
        jj.describe("Initial commit")

        val result = jj.run("log", "-r", "@", "--no-graph", "-T", fullSpec)
        result.isSuccess shouldBe true

        val fields = result.stdout.trim().split("\u0000")
        // 15 fields + trailing empty from final \0
        fields.size shouldBe FULL_FIELD_COUNT + 1
    }

    @Test
    fun `change id field has full~short~offset format`() {
        jj.describe("Test")

        val result = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val fields = result.stdout.trim().split("\u0000")
        val changeIdField = fields[0]

        // Format: full~short~offset (offset empty when not divergent)
        val parts = changeIdField.split("~")
        parts.size shouldBe 3
        parts[0].length shouldBeGreaterThan 0 // full change id
        parts[1].length shouldBeGreaterThan 0 // shortest
    }

    @Test
    fun `commit id field has full~short format`() {
        jj.describe("Test")

        val result = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val fields = result.stdout.trim().split("\u0000")
        val commitIdField = fields[1]

        val parts = commitIdField.split("~")
        parts.size shouldBe 2
        parts[0].length shouldBeGreaterThan 0
        parts[1].length shouldBeGreaterThan 0
    }

    @Test
    fun `boolean fields are true or false strings`() {
        jj.describe("Test")

        val result = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val fields = result.stdout.trim().split("\u0000")

        val booleanFields = listOf(
            5 to "currentWorkingCopy",
            6 to "conflict",
            7 to "empty",
            8 to "immutable"
        )
        booleanFields.forEach { (idx, name) ->
            fields[idx] shouldMatch Regex("true|false")
        }
    }

    @Test
    fun `exactly one working copy entry`() {
        jj.describe("First")
        jj.newChange("Second")

        val result = jj.run("log", "-r", "all()", "--no-graph", "-T", basicSpec)
        result.isSuccess shouldBe true

        val allFields = result.stdout.trim().split("\u0000")
        val records = allFields.chunked(BASIC_FIELD_COUNT).filter { it.size == BASIC_FIELD_COUNT }
        val workingCopyCount = records.count { it[5] == "true" }

        workingCopyCount shouldBe 1
    }

    @Test
    fun `multiline description does not break field alignment`() {
        jj.describe("Line one\nLine two\nLine three")

        val result = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        result.isSuccess shouldBe true

        val fields = result.stdout.trim().split("\u0000")
        fields.size shouldBe BASIC_FIELD_COUNT + 1

        // Description should contain the newlines
        fields[2] shouldBe "Line one\nLine two\nLine three\n"
    }

    @Test
    fun `timestamps are epoch seconds`() {
        jj.describe("Test")

        val result = jj.run("log", "-r", "@", "--no-graph", "-T", fullSpec)
        val fields = result.stdout.trim().split("\u0000")

        // Author timestamp at index 11, committer timestamp at index 14
        val authorTs = fields[11]
        val committerTs = fields[14]

        authorTs.toLong() shouldBeGreaterThan 0L
        committerTs.toLong() shouldBeGreaterThan 0L
    }

    @Test
    fun `bookmarks appear in bookmarks field`() {
        jj.describe("Bookmarked commit")
        jj.bookmarkCreate("test-bookmark")

        val result = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val fields = result.stdout.trim().split("\u0000")

        fields[3] shouldBe "test-bookmark;true"
    }

    @Test
    fun `parents field contains parent identifiers`() {
        jj.describe("Parent commit")
        jj.newChange("Child commit")

        val result = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val fields = result.stdout.trim().split("\u0000")
        val parentsField = fields[4]

        // Should have at least one parent with changeId|commitId format
        parentsField.length shouldBeGreaterThan 0
        val parentParts = parentsField.split(",")
        parentParts.forEach { parent ->
            val halves = parent.split("|")
            halves.size shouldBe 2
            // Each half should have ~ separators
            halves[0].split("~").size shouldBe 3 // change id: full~short~offset
            halves[1].split("~").size shouldBe 2 // commit id: full~short
        }
    }

    @Test
    fun `multiple records parse correctly`() {
        jj.describe("First")
        jj.newChange("Second")
        jj.newChange("Third")

        val result = jj.run("log", "-r", "all()", "--no-graph", "-T", basicSpec)
        result.isSuccess shouldBe true

        val allFields = result.stdout.trim().split("\u0000")
        val records = allFields.chunked(BASIC_FIELD_COUNT).filter { it.size == BASIC_FIELD_COUNT }
        records.size shouldBeGreaterThan 2
    }

    companion object {
        private const val BASIC_FIELD_COUNT = 9
        private const val FULL_FIELD_COUNT = 15
    }
}
