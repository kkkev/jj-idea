package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Guards jj-idea-qa8i (GitHub #57): on platform 2026.2 (build 262), pressing Enter in the
 * Working Copy description text area stopped inserting a newline, because something higher up
 * the focus/action chain now consumes VK_ENTER before it reaches the plain [JBTextArea] that
 * used to handle it via default Swing behavior. The fix binds VK_ENTER explicitly on the text
 * area's own `WHEN_FOCUSED` input map so it is resilient to whatever changed platform-side.
 *
 * This exercises the actual production `InputMap`/`ActionMap` wiring (the same [KeyStroke] ->
 * action-name -> `Action` objects the component uses at runtime) rather than dispatching a raw
 * [KeyEvent], since headless focus/dispatch is unreliable in tests — see
 * [in.kkkev.jjidea.ui.log.JujutsuLogTableEnterActionTest] for the sibling pattern that guards the
 * global-keymap side of this same platform-compat class of bug.
 *
 * Platform-tagged because constructing [WorkingCopyControlsPanel] needs IJPGP's full platform
 * classpath (see project memory on IJPGP test infrastructure).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class WorkingCopyControlsPanelEnterKeyTest {
    private val project = projectFixture()

    @Test
    fun `Enter is explicitly bound to insert a newline in the description text area`() {
        val panel = WorkingCopyControlsPanel(project.get())
        val descriptionArea = UIUtil.findComponentOfType(panel, JBTextArea::class.java)
            ?: error("descriptionArea not found in WorkingCopyControlsPanel")

        descriptionArea.text = "first line"
        descriptionArea.caretPosition = descriptionArea.text.length

        val enterKeystroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val actionName = descriptionArea.getInputMap(JComponent.WHEN_FOCUSED).get(enterKeystroke)
        actionName shouldBe "jjidea-insert-newline"

        val action = descriptionArea.actionMap.get(actionName)
            ?: error("no action registered for '$actionName'")
        action.actionPerformed(ActionEvent(descriptionArea, ActionEvent.ACTION_PERFORMED, actionName as String))

        descriptionArea.text shouldBe "first line\n"
    }
}
