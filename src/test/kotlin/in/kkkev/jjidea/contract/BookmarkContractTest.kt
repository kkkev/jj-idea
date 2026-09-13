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

    // Mirrors CliLogService's real bookmarkListTemplate targets field (jj-idea-5r0g): unlike
    // normal_target above, added_targets stays parseable for a conflicted/divergent bookmark.
    private val conflictSpec = listOf(
        fields.run { singleField(TemplateParts.nameWithRemote()) { it } },
        fields.run { booleanField("conflict") },
        fields.run {
            singleField("""added_targets.map(|c| ${TemplateParts.qualifiedChangeId("c")}).join(",")""") { it }
        }
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

    @Test
    fun `conflicted bookmark reports conflict and multiple added_targets`() {
        // jj-idea-5r0g (GitHub #110): pins jj's real output shape for a conflicted/divergent
        // bookmark, which is what CliLogService.bookmarkListTemplate now reads via
        // `added_targets` instead of `normal_target` (see conflictSpec above).
        jj.describe("First")
        jj.newChange("Second")
        jj.makeBookmarkConflicted("conflicted-bm", "@-", "@")

        val result = jj.run("bookmark", "list", "-T", conflictSpec)
        result.isSuccess shouldBe true

        val allFields = result.stdout.trim().split("\u0000")
        // A conflicted local bookmark that fails to export cleanly to a colocated repo's Git
        // backing store (as this one always will, since neither target is a descendant of the
        // other) also surfaces a <name>@git row even without --all-remotes - filtered out here
        // the same way production code filters the git pseudo-remote (jj-idea-j0zv).
        val records = allFields.chunked(FIELDS_PER_CONFLICT_BOOKMARK)
            .filter { it.size == FIELDS_PER_CONFLICT_BOOKMARK }
            .filter { it[0] == "conflicted-bm" }

        records.size shouldBe 1
        records[0][0] shouldBe "conflicted-bm"
        records[0][1] shouldBe "true"
        val targets = records[0][2].split(",")
        targets.size shouldBe 2
        targets.forEach { target ->
            val parts = target.split("~")
            parts.size shouldBe 3
            parts[0].length shouldBeGreaterThan 0
        }
    }

    companion object {
        private const val FIELDS_PER_BOOKMARK = 2
        private const val FIELDS_PER_CONFLICT_BOOKMARK = 3
    }
}
