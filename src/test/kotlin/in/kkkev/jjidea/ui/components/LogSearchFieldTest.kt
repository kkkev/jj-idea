package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.SearchFieldWithExtension
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Dimension

/** jj-idea-ehwm.5: the search field collapses to a chip when squeezed, and shows revset errors as an outline. */
@Tag("platform")
@TestApplication
@RunInEdt
class LogSearchFieldTest {
    private fun field(collapsible: Boolean = true) =
        LogSearchField("Filter", revsetModeAvailable = true, collapsible = collapsible)

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
    fun `an error outlines the field's border host and clearing it removes the outline`() {
        val field = field()
        val host = UIUtil.findComponentOfType(field, SearchFieldWithExtension::class.java)!!

        field.setError("Syntax error")
        host.getClientProperty("JComponent.outline") shouldBe "error"

        field.setError(null)
        host.getClientProperty("JComponent.outline") shouldBe null
    }

    @Test
    fun `the chip shows exactly the typed text`() {
        val field = field()
        field.revsetMode = true
        field.text = "::@"

        val chip = UIUtil.findComponentOfType(field, SimpleColoredComponent::class.java)!!

        chip.getCharSequence(false).toString() shouldBe "::@"
    }
}
