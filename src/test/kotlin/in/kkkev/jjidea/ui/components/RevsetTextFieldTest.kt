package `in`.kkkev.jjidea.ui.components

import com.intellij.ide.ui.laf.darcula.ui.DarculaEditorTextFieldBorder
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EmptyEditorHighlighter
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult.Success.Irreversible.Reason.READ_ONLY
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.event.FocusEvent
import java.awt.event.MouseEvent

/** jj-idea-ehwm.7: the shared revset input - highlighting, error squiggle and history. */
@Tag("platform")
@TestApplication
@RunInEdt(writeIntent = true)
class RevsetTextFieldTest {
    private val project = projectFixture()

    private val created = mutableListOf<RevsetTextField>()

    @AfterEach
    fun releaseEditors() {
        created.forEach { it.editorField.removeNotify() }
        created.clear()
    }

    private fun field(historyKey: String? = null, text: String = "", multiline: Boolean = false) =
        RevsetTextField(project.get(), { emptyList() }, historyKey, multiline = multiline, initialText = text).also {
            created += it
            // Creates the editor, as being shown in a window would.
            it.editorField.addNotify()
            it.editorField.getEditor(true)
        }

    private fun RevsetTextField.editor() = editorField.getEditor(true) as EditorEx

    @Test
    fun `revset highlighter is installed, and plain text when highlighting is off`() {
        val field = field()

        field.editor().highlighter.shouldBeInstanceOf<LexerEditorHighlighter>()

        field.highlightingEnabled = false
        field.editor().highlighter.shouldBeInstanceOf<EmptyEditorHighlighter>()

        field.highlightingEnabled = true
        field.editor().highlighter.shouldBeInstanceOf<LexerEditorHighlighter>()
    }

    @Test
    fun `an error with a column adds one squiggle that editing clears`() {
        val field = field(text = "heads(")

        field.setError("bad revset", 1 to 6)
        field.squiggleCount shouldBe 1

        field.text = "heads(@)"
        field.squiggleCount shouldBe 0
    }

    @Test
    fun `an error outlines the editor, which is what the border paints`() {
        val field = field(text = "foo")

        field.setError("Revision `foo` doesn't exist")

        field.editorField.getClientProperty("JComponent.outline") shouldBe "error"
        field.toolTipText shouldBe "Revision `foo` doesn't exist"
    }

    @Test
    fun `a multiline field has the border that paints the error outline too`() {
        field().editor().scrollPane.border.shouldBeInstanceOf<DarculaEditorTextFieldBorder>()
        field(multiline = true).editor().scrollPane.border.shouldBeInstanceOf<DarculaEditorTextFieldBorder>()
    }

    @Test
    fun `an error can leave the outline to the caller but still squiggles`() {
        val field = field(text = "heads(")

        field.setError("bad revset", 1 to 6, showOutline = false)

        field.editorField.getClientProperty("JComponent.outline") shouldBe null
        field.squiggleCount shouldBe 1
    }

    @Test
    fun `an error without a column adds no squiggle`() {
        val field = field(text = "x")

        field.setError("no such revision")

        field.squiggleCount shouldBe 0
    }

    @Test
    fun `clearing the error removes the outline and squiggle`() {
        val field = field(text = "heads(")
        field.setError("bad revset", 1 to 6)

        field.setError(null)

        field.squiggleCount shouldBe 0
        field.editorField.getClientProperty("JComponent.outline") shouldBe null
    }

    @Test
    fun `a column past the end of the text adds no squiggle`() {
        val field = field(text = "heads(")

        field.setError("bad revset", 1 to 99)

        field.squiggleCount shouldBe 0
    }

    @Test
    fun `history round-trips in the SearchTextField format, most recent first, capped`() {
        val key = "test.revset.history"
        PropertiesComponent.getInstance().setValue(key, "b\na")
        val field = field(key, "c")

        field.addCurrentTextToHistory()

        readRevsetHistory(key) shouldContainExactly listOf("c", "b", "a")
        PropertiesComponent.getInstance().getValue(key) shouldBe "c\nb\na"

        listOf("d", "e", "f", "a").forEach {
            field.text = it
            field.addCurrentTextToHistory()
        }
        readRevsetHistory(key) shouldContainExactly listOf("a", "f", "e", "d", "c")
    }

    @Test
    fun `text change listeners fire`() {
        val field = field()
        var changes = 0
        field.onTextChanged { changes++ }

        field.text = "mine()"

        (changes > 0) shouldBe true
    }

    @Test
    fun `focusing runs one alias query per repo and typing runs none`() {
        val executor = mockk<CommandExecutor>()
        every { executor.configList("revset-aliases") } returns
            CommandExecutor.CommandResult.Success.Irreversible("revset-aliases.mywork = \"mine()\"", "", READ_ONLY)
        val repo = mockk<JujutsuRepository> { every { commandExecutor } returns executor }
        val field = RevsetTextField(project.get(), { listOf(repo, repo) }).also { created += it }
        field.editorField.addNotify()
        field.editorField.getEditor(true)

        // EditorTextField forwards its content component's focus events to its own listeners.
        field.editorField.focusGained(FocusEvent(field.editorField, FocusEvent.FOCUS_GAINED))
        verify(exactly = 2, timeout = 5000) { executor.configList("revset-aliases") }

        "heads(ancestors(mine()))".forEachIndexed { i, _ -> field.text = "heads(ancestors(mine()))".take(i + 1) }

        verify(exactly = 2) { executor.configList(any(), any()) }
    }

    @Test
    fun `the clear button empties the field and hides itself`() {
        val field = field(text = "heads(@)")
        field.clearButton.isVisible shouldBe true

        field.clearButton.mouseListeners.forEach {
            it.mousePressed(MouseEvent(field.clearButton, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false))
        }

        field.text shouldBe ""
        field.clearButton.isVisible shouldBe false
    }
}
