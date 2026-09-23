package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [shouldRaisePreview], the pure decision behind
 * [JujutsuEditorTabDiffPreview.handleSingleClick]. See that function's doc comment for the
 * GitHub #67 bug this guards against.
 */
class JujutsuEditorTabDiffPreviewTest {
    @Test
    fun `preview closed - never raised regardless of other flags`() {
        shouldRaisePreview(previewOpen = false, modelUpdateInProgress = false, editorActive = false) shouldBe false
        shouldRaisePreview(previewOpen = false, modelUpdateInProgress = true, editorActive = true) shouldBe false
    }

    @Test
    fun `GitHub 67 - preview open during a tree model rebuild (save-triggered refresh) is not raised`() {
        shouldRaisePreview(previewOpen = true, modelUpdateInProgress = true, editorActive = false) shouldBe false
    }

    @Test
    fun `preview open while the editor has focus is not raised`() {
        shouldRaisePreview(previewOpen = true, modelUpdateInProgress = false, editorActive = true) shouldBe false
    }

    @Test
    fun `preview open, real click in the tree, editor not focused - raised`() {
        shouldRaisePreview(previewOpen = true, modelUpdateInProgress = false, editorActive = false) shouldBe true
    }
}

/**
 * Unit tests for [conflictForDoubleClick], the GitHub #66 routing decision behind
 * [JujutsuEditorTabDiffPreview.handleDoubleClick]: does this double-click resolve one file, or
 * fall through to the ordinary diff preview?
 */
class ConflictForDoubleClickTest {
    private fun change(path: String, status: FileStatus): Change {
        val filePath = LocalFilePath(path, false)
        return Change(SimpleContentRevision("", filePath, "0"), SimpleContentRevision("", filePath, "1"), status)
    }

    @Test
    fun `feature disabled - never resolves, even for a single conflicted selection`() {
        val conflicted = change("a.txt", FileStatus.MERGED_WITH_CONFLICTS)

        conflictForDoubleClick(resolveConflictsOnDoubleClick = false, selectedChanges = listOf(conflicted)) shouldBe
            null
    }

    @Test
    fun `enabled, single conflicted row selected - resolves that file`() {
        val conflicted = change("a.txt", FileStatus.MERGED_WITH_CONFLICTS)

        conflictForDoubleClick(resolveConflictsOnDoubleClick = true, selectedChanges = listOf(conflicted)) shouldBe
            conflicted
    }

    @Test
    fun `enabled, single non-conflicted row selected - falls through to diff preview`() {
        val clean = change("a.txt", FileStatus.MODIFIED)

        conflictForDoubleClick(resolveConflictsOnDoubleClick = true, selectedChanges = listOf(clean)) shouldBe null
    }

    @Test
    fun `enabled, no selection - falls through to diff preview`() {
        conflictForDoubleClick(resolveConflictsOnDoubleClick = true, selectedChanges = emptyList()) shouldBe null
    }

    @Test
    fun `enabled, multiple conflicted rows selected - falls through rather than picking one`() {
        val a = change("a.txt", FileStatus.MERGED_WITH_CONFLICTS)
        val b = change("b.txt", FileStatus.MERGED_WITH_CONFLICTS)

        conflictForDoubleClick(resolveConflictsOnDoubleClick = true, selectedChanges = listOf(a, b)) shouldBe null
    }
}
