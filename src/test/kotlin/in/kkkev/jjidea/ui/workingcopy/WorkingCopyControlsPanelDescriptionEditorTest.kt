package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Guards jj-idea-n3w1 (GitHub #46): the Working Copy description field became a real platform
 * commit-message editor (`in.kkkev.jjidea.ui.components.DescriptionEditor`, wrapping
 * `com.intellij.openapi.vcs.ui.CommitMessage`) instead of a plain [javax.swing.JBTextArea].
 *
 * This replaces the older `WorkingCopyControlsPanelEnterKeyTest` (jj-idea-qa8i / GitHub #57): that
 * test asserted a bespoke `WHEN_FOCUSED` `InputMap`/`ActionMap` workaround binding `VK_ENTER` to
 * insert a newline, needed only because `JBTextArea` stopped getting Enter by default on platform
 * 2026.2. A real multi-line platform editor has no such gap - Enter/newline handling belongs
 * entirely to the platform's own `EditorImpl`, not to any plugin-owned key binding - so there is
 * nothing plugin-specific left to assert there; that behavior is covered by the manual smoke
 * steps instead (see `docs/manual-tests.md` MT-WORKINGCOPY), not automation.
 *
 * What *is* worth asserting here, and what actually could regress in this swap, is the wiring: the
 * panel exposes a real [EditorTextField] (not the old [javax.swing.JBTextArea]) and text set
 * through [DescriptionEditor.text][in.kkkev.jjidea.ui.components.DescriptionEditor.text] reaches
 * it correctly. Deliberately does *not* force the embedded [com.intellij.openapi.editor.Editor]
 * into existence (e.g. via `EditorTextField.getEditor(true)`/`setDisposedWith`) outside a real
 * dialog: doing so runs `CommitMessage`'s `InspectionCustomization`, which registers a
 * `CommitMessageInspectionProfile` listener on the project's message bus - fine when a real
 * `DialogWrapper` tears its component tree down normally, but a `ProjectImpl` leak in this
 * lightweight fixture (no window, no real close lifecycle) when forced directly.
 *
 * Platform-tagged because constructing [WorkingCopyControlsPanel] needs IJPGP's full platform
 * classpath (see project memory on IJPGP test infrastructure).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class WorkingCopyControlsPanelDescriptionEditorTest {
    private val project = projectFixture()

    @Test
    fun `description field is a real commit-message editor and round-trips text`() {
        val panel = WorkingCopyControlsPanel(project.get()).also { Disposer.register(project.get(), it) }
        val editorField = UIUtil.findComponentOfType(panel, EditorTextField::class.java)
            ?: error("description EditorTextField not found in WorkingCopyControlsPanel")

        editorField.text = "first line\nsecond line"

        editorField.text shouldBe "first line\nsecond line"
    }
}
