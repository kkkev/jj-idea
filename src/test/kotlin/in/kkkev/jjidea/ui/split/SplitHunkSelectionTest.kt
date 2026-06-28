package `in`.kkkev.jjidea.ui.split

import com.intellij.openapi.vcs.LocalFilePath
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure tests for [SplitHunkSelection] and [FileFirstCommit].
 * No platform dependency; runs in the default (unit) test task.
 */
class SplitHunkSelectionTest {
    private val fp1 = LocalFilePath("src/Auth.kt", false)
    private val fp2 = LocalFilePath("src/Logger.kt", false)
    private val fp3 = LocalFilePath("src/Utils.kt", false)

    @Test
    fun `buildPerFileContent maps relPath to content`() {
        val files = listOf(
            FileFirstCommit(relPath = "src/Auth.kt", filePath = fp1, content = "new content\n"),
            FileFirstCommit(relPath = "src/Logger.kt", filePath = fp2, content = null)
        )
        val selection = SplitHunkSelection(files)
        val map = selection.buildPerFileContent()
        map["src/Auth.kt"] shouldBe "new content\n"
        map["src/Logger.kt"] shouldBe null
    }

    @Test
    fun `firstCommitFilePaths returns only non-null content files`() {
        val files = listOf(
            FileFirstCommit("src/Auth.kt", fp1, "content\n"),
            FileFirstCommit("src/Logger.kt", fp2, null),
            FileFirstCommit("src/Utils.kt", fp3, "other\n")
        )
        val selection = SplitHunkSelection(files)
        val paths = selection.firstCommitFilePaths()
        paths.size shouldBe 2
        paths shouldBe listOf(fp1, fp3)
    }

    @Test
    fun `firstCommitFileCount counts non-null entries`() {
        val files = listOf(
            FileFirstCommit("a.kt", fp1, "x"),
            FileFirstCommit("b.kt", fp2, null),
            FileFirstCommit("c.kt", fp3, "y")
        )
        SplitHunkSelection(files).firstCommitFileCount shouldBe 2
    }

    @Test
    fun `secondCommitFileCount counts null entries`() {
        val files = listOf(
            FileFirstCommit("a.kt", fp1, "x"),
            FileFirstCommit("b.kt", fp2, null)
        )
        SplitHunkSelection(files).secondCommitFileCount shouldBe 1
    }

    @Test
    fun `hasPartialFiles is always true`() {
        val files = listOf(FileFirstCommit("a.kt", fp1, "x"))
        SplitHunkSelection(files).hasPartialFiles shouldBe true
    }

    @Test
    fun `empty file list produces empty map`() {
        val selection = SplitHunkSelection(emptyList())
        selection.buildPerFileContent() shouldBe emptyMap()
        selection.firstCommitFilePaths() shouldBe emptyList()
        selection.firstCommitFileCount shouldBe 0
        selection.secondCommitFileCount shouldBe 0
    }
}
