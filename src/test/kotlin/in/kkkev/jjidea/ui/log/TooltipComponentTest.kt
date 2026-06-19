package `in`.kkkev.jjidea.ui.log

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.components.JBScrollPane
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JPanel

/**
 * Tests for [tooltipComponent] — bounding the row-hover tooltip so commits with many bookmarks
 * reflow/scroll instead of being clipped by the screen (jj-idea-szn8). Real HTML reflow needs a
 * font environment and is verified manually; these tests cover the bounding/scroll-wrapping
 * decision using a component with a fixed preferred size.
 *
 * Platform-tagged because constructing [JBScrollPane] needs IJPGP's full platform classpath
 * (its scroll bar UI delegate touches platform-native registration that crashes under the
 * stripped unit-test classpath).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class TooltipComponentTest {
    private fun pane(width: Int, height: Int) = JPanel().apply { preferredSize = Dimension(width, height) }

    @Test
    fun `content within bounds is returned unwrapped`() {
        val component = pane(300, 100)

        val result = tooltipComponent(component, maxWidth = 500, maxHeight = 400)

        result shouldBe component
        result.preferredSize shouldBe Dimension(300, 100)
    }

    @Test
    fun `content wider than maxWidth is pinned to maxWidth`() {
        val component = pane(900, 100)

        tooltipComponent(component, maxWidth = 500, maxHeight = 400)

        component.preferredSize.width shouldBe 500
    }

    @Test
    fun `content taller than maxHeight is wrapped in a bounded scroll pane`() {
        val component = pane(300, 900)

        val result = tooltipComponent(component, maxWidth = 500, maxHeight = 400)

        result.shouldBeInstanceOf<JBScrollPane>()
        result.preferredSize shouldBe Dimension(300, 400)
    }

    @Test
    fun `scroll pane width is bounded to maxWidth too`() {
        val component = pane(900, 900)

        val result = tooltipComponent(component, maxWidth = 500, maxHeight = 400)

        result.shouldBeInstanceOf<JBScrollPane>()
        result.preferredSize shouldBe Dimension(500, 400)
    }
}
