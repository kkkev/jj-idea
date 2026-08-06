package `in`.kkkev.jjidea.ui.common

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
