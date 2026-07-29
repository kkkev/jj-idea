package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for [StyledTextCanvas.linked]/[TextCanvas.underlined] (jj-idea-iesq): links must be
 * colored via [SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES] (no underline) by default, with
 * underline added only when explicitly wrapped in [TextCanvas.underlined] - the mechanism
 * `UserCellRenderer` uses to underline an author/committer link only while it's hovered, instead
 * of unconditionally underlining every link the way the old (bare
 * [SimpleTextAttributes.LINK_ATTRIBUTES]) implementation did.
 */
class StyledTextCanvasLinkStyleTest {
    /** Records the [StyledTextCanvas.style] active when each [append] call ran. */
    private class RecordingCanvas : StyledTextCanvas() {
        val styleAt = mutableListOf<SimpleTextAttributes>()

        override fun append(text: String) {
            styleAt.add(style)
        }
    }

    private val uri = URI("mailto", "alice@example.com", null)

    @Test
    fun `linked applies link color without underline by default`() {
        val canvas = RecordingCanvas()

        canvas.linked(uri) { append("Alice") }

        val style = canvas.styleAt.single()
        style.fgColor shouldBe JBUI.CurrentTheme.Link.Foreground.ENABLED
        (style.style and SimpleTextAttributes.STYLE_UNDERLINE) shouldBe 0
    }

    @Test
    fun `underlined inside linked adds the underline bit while keeping the link color`() {
        val canvas = RecordingCanvas()

        canvas.linked(uri) { underlined { append("Alice") } }

        val style = canvas.styleAt.single()
        style.fgColor shouldBe JBUI.CurrentTheme.Link.Foreground.ENABLED
        (style.style and SimpleTextAttributes.STYLE_UNDERLINE) shouldBe SimpleTextAttributes.STYLE_UNDERLINE
    }

    @Test
    fun `underlined outside linked also composes correctly regardless of wrap order`() {
        val canvas = RecordingCanvas()

        canvas.underlined { canvas.linked(uri) { append("Alice") } }

        val style = canvas.styleAt.single()
        style.fgColor shouldBe JBUI.CurrentTheme.Link.Foreground.ENABLED
        (style.style and SimpleTextAttributes.STYLE_UNDERLINE) shouldBe SimpleTextAttributes.STYLE_UNDERLINE
    }

    @Test
    fun `plain text outside any linked block is unaffected`() {
        val canvas = RecordingCanvas()

        canvas.append("plain")

        val style = canvas.styleAt.single()
        style shouldBe SimpleTextAttributes.REGULAR_ATTRIBUTES
    }
}
