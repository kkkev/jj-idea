package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.SearchFieldWithExtension
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JComponent

/** jj-idea-ehwm.5: the search field collapses to a chip when squeezed, and shows revset errors as an outline. */
@Tag("platform")
@TestApplication
@RunInEdt
class LogSearchFieldTest {
    private val project = projectFixture()

    private fun field(collapsible: Boolean = true) =
        LogSearchField("Filter", revsetModeAvailable = true, collapsible = collapsible, project = project.get())

    private fun resize(field: LogSearchField, width: Int) {
        field.setSize(width, 30)
        field.doLayout()
        // Component listeners fire from the toolkit on a real resize; drive them directly in a headless test.
        field.componentListeners.forEach { it.componentResized(java.awt.event.ComponentEvent(field, 0)) }
    }

    @Test
    fun `stays inline while there is room to type`() {
        val field = field()

        resize(field, 900)

        field.isCollapsed shouldBe false
    }

    @Test
    fun `collapses to a chip when squeezed and expands again when widened`() {
        val field = field()
        resize(field, 900)

        resize(field, 120)
        field.isCollapsed shouldBe true

        resize(field, 900)
        field.isCollapsed shouldBe false
    }

    @Test
    fun `never collapses unless asked to`() {
        val field = field(collapsible = false)

        resize(field, 120)

        field.isCollapsed shouldBe false
    }

    @Test
    fun `the preferred width is the full field's even while collapsed so the layout can offer room to expand`() {
        val field = field()
        val expanded = field.preferredSize

        resize(field, 120)

        field.preferredSize shouldBe Dimension(expanded.width, expanded.height)
    }

    @Test
    fun `an error outlines the revset box's editor once and clearing it removes the outline`() {
        val field = field()
        val box = UIUtil.findComponentOfType(field, RevsetTextField::class.java)!!
        val wrapper = box.parent as JComponent

        field.setError("Syntax error")
        box.toolTipText shouldBe "Syntax error"
        box.editorField.getClientProperty("JComponent.outline") shouldBe "error"
        // The editor's border is the only decoration; a wrapper border around it would double the outline.
        wrapper.border shouldBe null

        field.setError(null)
        box.toolTipText shouldBe null
        box.editorField.getClientProperty("JComponent.outline") shouldBe null
    }

    @Test
    fun `a plain search field outlines its border host`() {
        val field = LogSearchField("Filter")
        val host = UIUtil.findComponentOfType(field, SearchFieldWithExtension::class.java)!!

        field.setError("Syntax error")
        host.getClientProperty("JComponent.outline") shouldBe "error"

        field.setError(null)
        host.getClientProperty("JComponent.outline") shouldBe null
    }

    @Test
    fun `highlighting follows revset mode`() {
        val field = field()
        val box = UIUtil.findComponentOfType(field, RevsetTextField::class.java)!!
        box.highlightingEnabled shouldBe false

        field.revsetMode = true
        box.highlightingEnabled shouldBe true

        field.revsetMode = false
        box.highlightingEnabled shouldBe false
    }

    @Test
    fun `the chip shows exactly the typed text`() {
        val field = field()
        field.revsetMode = true
        field.text = "::@"

        val chip = UIUtil.findComponentOfType(field, SimpleColoredComponent::class.java)!!

        chip.getCharSequence(false).toString() shouldBe "::@"
    }

    @Test
    fun `a submitted search is stored with its switches and restoring them sets the toggles`() {
        val key = "test.log.search.modes"
        com.intellij.ide.util.PropertiesComponent.getInstance().unsetValue(key)
        val field = LogSearchField("Filter", historyKey = key, revsetModeAvailable = true, project = project.get())
        field.useRegex = true
        field.matchWholeWords = true
        field.text = "fo+"
        field.submit()
        field.revsetMode = true
        field.text = "mine()"
        field.submit()

        readRevsetHistoryEntries(key) shouldBe listOf(HistoryEntry("mine()", "v"), HistoryEntry("fo+", "rw"))

        field.restoreModes("rw")
        field.revsetMode shouldBe false
        field.useRegex shouldBe true
        field.matchCase shouldBe false
        field.matchWholeWords shouldBe true
        field.encodeModes() shouldBe "rw"
        field.restoreModes("v")
        field.revsetMode shouldBe true
        field.useRegex shouldBe false
    }
}
