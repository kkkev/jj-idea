package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression test for jj-idea-4d7p: [DescriptionEditor.setEnabled] must actually disable the
 * embedded editor. Setting `isEnabled` on [DescriptionEditor.component] (the `CommitMessage`
 * `JPanel`) is not enough - Swing doesn't propagate enablement to children, so the embedded
 * `EditorTextField` stayed editable even though [DescriptionEditor.component] itself was
 * `isEnabled == false`. The plain `JBTextArea` this replaced (jj-idea-n3w1) disabled correctly on
 * its own; this is what regressed.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class DescriptionEditorEnabledTest {
    private val project = projectFixture()

    @Test
    fun `setEnabled(false) disables the embedded editor field`() {
        val editor = DescriptionEditor(project.get()).also { Disposer.register(project.get(), it) }

        editor.setEnabled(false)

        editor.commitMessage.editorField.isEnabled shouldBe false
    }

    @Test
    fun `setEnabled(true) re-enables the embedded editor field`() {
        val editor = DescriptionEditor(project.get()).also { Disposer.register(project.get(), it) }

        editor.setEnabled(false)
        editor.setEnabled(true)

        editor.commitMessage.editorField.isEnabled shouldBe true
    }
}
