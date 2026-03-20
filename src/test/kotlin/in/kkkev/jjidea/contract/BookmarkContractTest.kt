package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.jj.cli.TemplateParts
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class BookmarkContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjBackend

    abstract fun createBackend(tempDir: Path): JjBackend

    private val fields = CliLogService.LogFields()
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
    fun `bookmark list with template produces correct field count`() {
        jj.describe("Bookmarked commit")
        jj.bookmarkCreate("test-bm")

        val result = jj.run("bookmark", "list", "-T", bookmarkSpec)
        result.isSuccess shouldBe true

        val allFields = result.stdout.trim().split("\u0000")
        val records = allFields.chunked(FIELDS_PER_BOOKMARK).filter { it.size == FIELDS_PER_BOOKMARK }

        records.size shouldBe 1
        records[0][0] shouldBe "test-bm"
    }

    @Test
    fun `bookmark target has qualified change id format`() {
        jj.describe("Target")
        jj.bookmarkCreate("my-bookmark")

        val result = jj.run("bookmark", "list", "-T", bookmarkSpec)
        val allFields = result.stdout.trim().split("\u0000")
        val records = allFields.chunked(FIELDS_PER_BOOKMARK).filter { it.size == FIELDS_PER_BOOKMARK }

        val changeIdField = records[0][1]
        val parts = changeIdField.split("~")
        parts.size shouldBe 3
        parts[0].length shouldBeGreaterThan 0 // full change id
        parts[1].length shouldBeGreaterThan 0 // short change id
    }

    @Test
    fun `multiple bookmarks listed correctly`() {
        jj.describe("First")
        jj.bookmarkCreate("alpha")
        jj.newChange("Second")
        jj.bookmarkCreate("beta")

        val result = jj.run("bookmark", "list", "-T", bookmarkSpec)
        result.isSuccess shouldBe true

        val allFields = result.stdout.trim().split("\u0000")
        val records = allFields.chunked(FIELDS_PER_BOOKMARK).filter { it.size == FIELDS_PER_BOOKMARK }

        records.size shouldBe 2
        val names = records.map { it[0] }.toSet()
        names shouldBe setOf("alpha", "beta")
    }

    companion object {
        private const val FIELDS_PER_BOOKMARK = 2
    }
}
