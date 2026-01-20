package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Tests for FileChange DTO and FileChangeStatus enum.
 */
class FileChangeTest {
    @Test
    fun `create modified file change`() {
        val change = FileChange("src/Main.kt", FileChangeStatus.MODIFIED)

        change.filePath shouldBe "src/Main.kt"
        change.status shouldBe FileChangeStatus.MODIFIED
    }

    @Test
    fun `create added file change`() {
        val change = FileChange("src/New.kt", FileChangeStatus.ADDED)

        change.filePath shouldBe "src/New.kt"
        change.status shouldBe FileChangeStatus.ADDED
    }

    @Test
    fun `create deleted file change`() {
        val change = FileChange("src/Old.kt", FileChangeStatus.DELETED)

        change.filePath shouldBe "src/Old.kt"
        change.status shouldBe FileChangeStatus.DELETED
    }

    @Test
    fun `create unknown status file change`() {
        val change = FileChange("src/Unknown.kt", FileChangeStatus.UNKNOWN)

        change.filePath shouldBe "src/Unknown.kt"
        change.status shouldBe FileChangeStatus.UNKNOWN
    }

    @Test
    fun `data class equality based on filePath and status`() {
        val change1 = FileChange("src/Main.kt", FileChangeStatus.MODIFIED)
        val change2 = FileChange("src/Main.kt", FileChangeStatus.MODIFIED)
        val change3 = FileChange("src/Main.kt", FileChangeStatus.ADDED)
        val change4 = FileChange("src/Other.kt", FileChangeStatus.MODIFIED)

        change1 shouldBe change2
        change1 shouldNotBe change3
        change1 shouldNotBe change4
    }

    @Test
    fun `data class copy works correctly`() {
        val original = FileChange("src/Main.kt", FileChangeStatus.MODIFIED)
        val copied = original.copy(status = FileChangeStatus.ADDED)

        copied.filePath shouldBe "src/Main.kt"
        copied.status shouldBe FileChangeStatus.ADDED
        original.status shouldBe FileChangeStatus.MODIFIED
    }

    @Test
    fun `FileChangeStatus enum has all expected values`() {
        val values = FileChangeStatus.entries

        values shouldBe
            listOf(
                FileChangeStatus.MODIFIED,
                FileChangeStatus.ADDED,
                FileChangeStatus.DELETED,
                FileChangeStatus.UNKNOWN
            )
    }

    @Test
    fun `FileChangeStatus valueOf works`() {
        FileChangeStatus.valueOf("MODIFIED") shouldBe FileChangeStatus.MODIFIED
        FileChangeStatus.valueOf("ADDED") shouldBe FileChangeStatus.ADDED
        FileChangeStatus.valueOf("DELETED") shouldBe FileChangeStatus.DELETED
        FileChangeStatus.valueOf("UNKNOWN") shouldBe FileChangeStatus.UNKNOWN
    }

    @Test
    fun `file changes with different paths are not equal`() {
        val change1 = FileChange("a/b/c.kt", FileChangeStatus.MODIFIED)
        val change2 = FileChange("x/y/z.kt", FileChangeStatus.MODIFIED)

        change1 shouldNotBe change2
    }

    @Test
    fun `file paths can contain special characters`() {
        val change = FileChange("src/some file with spaces.kt", FileChangeStatus.MODIFIED)

        change.filePath shouldBe "src/some file with spaces.kt"
    }

    @Test
    fun `file paths can be deeply nested`() {
        val deepPath = "a/b/c/d/e/f/g/h/i/j/file.kt"
        val change = FileChange(deepPath, FileChangeStatus.ADDED)

        change.filePath shouldBe deepPath
    }
}
