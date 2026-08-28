package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.vcs.filePath
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Pure tests for [HunkSelection]/[FileHunkContent] (the shared model behind
 * [in.kkkev.jjidea.ui.split.SplitDialog] and [in.kkkev.jjidea.ui.squash.SquashIntoDialog]) and
 * [buildHunkSelection]. No platform dependency; runs in the default (unit) test task.
 */
class HunkSelectionModelTest {
    private val fp1 = LocalFilePath("src/Auth.kt", false)
    private val fp2 = LocalFilePath("src/Logger.kt", false)
    private val fp3 = LocalFilePath("src/Utils.kt", false)

    // ---- HunkSelection ----

    @Test
    fun `buildPerFileContent maps relPath to content`() {
        val files = listOf(
            FileHunkContent(relPath = "src/Auth.kt", filePath = fp1, content = "new content\n"),
            FileHunkContent(relPath = "src/Logger.kt", filePath = fp2, content = null)
        )
        val selection = HunkSelection(files)
        val map = selection.buildPerFileContent()
        map["src/Auth.kt"] shouldBe "new content\n"
        map["src/Logger.kt"] shouldBe null
    }

    @Test
    fun `explicitContentFilePaths returns only non-null content files`() {
        val files = listOf(
            FileHunkContent("src/Auth.kt", fp1, "content\n"),
            FileHunkContent("src/Logger.kt", fp2, null),
            FileHunkContent("src/Utils.kt", fp3, "other\n")
        )
        val selection = HunkSelection(files)
        val paths = selection.explicitContentFilePaths()
        paths.size shouldBe 2
        paths shouldBe listOf(fp1, fp3)
    }

    @Test
    fun `explicitContentFileCount counts non-null entries`() {
        val files = listOf(
            FileHunkContent("a.kt", fp1, "x"),
            FileHunkContent("b.kt", fp2, null),
            FileHunkContent("c.kt", fp3, "y")
        )
        HunkSelection(files).explicitContentFileCount shouldBe 2
    }

    @Test
    fun `defaultedFileCount counts null entries`() {
        val files = listOf(
            FileHunkContent("a.kt", fp1, "x"),
            FileHunkContent("b.kt", fp2, null)
        )
        HunkSelection(files).defaultedFileCount shouldBe 1
    }

    @Test
    fun `hasPartialFiles is always true`() {
        val files = listOf(FileHunkContent("a.kt", fp1, "x"))
        HunkSelection(files).hasPartialFiles shouldBe true
    }

    @Test
    fun `empty file list produces empty map`() {
        val selection = HunkSelection(emptyList())
        selection.buildPerFileContent() shouldBe emptyMap()
        selection.explicitContentFilePaths() shouldBe emptyList()
        selection.explicitContentFileCount shouldBe 0
        selection.defaultedFileCount shouldBe 0
    }

    @Test
    fun `deletedPaths defaults to empty`() {
        HunkSelection(emptyList()).deletedPaths shouldBe emptySet()
    }

    // ---- buildHunkSelection ----

    private val root: VirtualFile = mockk { every { path } returns "/repo" }

    private fun modifiedChange(path: String) = Change(
        SimpleContentRevision("before", LocalFilePath("/repo/$path", false), "1"),
        SimpleContentRevision("after", LocalFilePath("/repo/$path", false), "2")
    )

    private fun deletionChange(path: String) = Change(
        SimpleContentRevision("before", LocalFilePath("/repo/$path", false), "1"),
        null
    )

    @Test
    fun `override wins over included and deletion`() {
        val change = modifiedChange("Auth.kt")
        val selection = buildHunkSelection(
            changes = listOf(change),
            root = root,
            overrides = mapOf(change.filePath to "picked\n"),
            isIncluded = { true },
            isDeletion = { true },
            contentFor = { _, _ -> "should not be called" }
        )
        selection.files.single().content shouldBe "picked\n"
        selection.deletedPaths shouldBe emptySet()
    }

    @Test
    fun `included deletion with no override goes to deletedPaths, not content`() {
        val change = deletionChange("Removed.kt")
        val selection = buildHunkSelection(
            changes = listOf(change),
            root = root,
            overrides = emptyMap(),
            isIncluded = { true },
            isDeletion = { it.afterRevision == null },
            contentFor = { _, _ -> "should not be called for a deletion" }
        )
        selection.files.single().content shouldBe null
        selection.deletedPaths shouldBe setOf("Removed.kt")
    }

    @Test
    fun `excluded deletion is left to contentFor, not deletedPaths`() {
        val change = deletionChange("Removed.kt")
        val selection = buildHunkSelection(
            changes = listOf(change),
            root = root,
            overrides = emptyMap(),
            isIncluded = { false },
            isDeletion = { it.afterRevision == null },
            contentFor = { _, included -> if (included) "unexpected" else null }
        )
        selection.deletedPaths shouldBe emptySet()
        selection.files.single().content shouldBe null
    }

    @Test
    fun `non-deletion falls through to contentFor`() {
        val change = modifiedChange("Auth.kt")
        val selection = buildHunkSelection(
            changes = listOf(change),
            root = root,
            overrides = emptyMap(),
            isIncluded = { true },
            isDeletion = { false },
            contentFor = { _, included -> if (included) "after\n" else null }
        )
        selection.files.single().content shouldBe "after\n"
    }

    @Test
    fun `relPath is repo-relative`() {
        val change = modifiedChange("src/Auth.kt")
        val selection = buildHunkSelection(
            changes = listOf(change),
            root = root,
            overrides = emptyMap(),
            isIncluded = { false },
            isDeletion = { false },
            contentFor = { _, _ -> null }
        )
        selection.files.single().relPath shouldBe "src/Auth.kt"
    }
}
