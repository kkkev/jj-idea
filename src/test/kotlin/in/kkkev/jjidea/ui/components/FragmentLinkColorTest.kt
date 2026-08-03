package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.SimpleTextAttributes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Regression test: [FragmentRecordingCanvas.linked] used to only track `currentLinkTarget` for
 * hit-testing, without calling `super.linked` - silently skipping [StyledTextCanvas.linked]'s
 * link-color styling. A link with no enclosing [TextCanvas.colored] wrapper (e.g. a description's
 * issue-tracker reference, which has none) rendered in the surrounding plain-text color instead of
 * the platform's link color. `@TestApplication` is required here: `SimpleTextAttributes
 * .LINK_PLAIN_ATTRIBUTES`'s foreground color resolves to null outside a fully initialized platform,
 * which would make this pass for the wrong reason in a bare unit test.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class FragmentLinkColorTest {
    @Test
    fun `a linked fragment gets the platform link color`() {
        val canvas = FragmentRecordingCanvas()
        canvas.linked(URI("https://example.com")) { canvas.append("click me") }

        val fragment = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>().single()
        fragment.style.fgColor shouldBe SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES.fgColor
        fragment.style.fgColor shouldNotBe null
    }

    @Test
    fun `plain text outside any linked block keeps the ambient color`() {
        val canvas = FragmentRecordingCanvas()
        canvas.foreground(java.awt.Color.BLACK) {
            canvas.append("plain ")
            canvas.linked(URI("https://example.com")) { canvas.append("link") }
        }

        val fragments = canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
        fragments.single { it.text == "plain " }.style.fgColor shouldBe java.awt.Color.BLACK
        fragments.single { it.text == "link" }.style.fgColor shouldBe SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES.fgColor
    }
}
