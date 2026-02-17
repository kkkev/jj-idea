package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleTextAttributes
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

class FragmentLayoutTest {
    private val font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val frc = FontRenderContext(AffineTransform(), true, true)

    @Nested
    inner class `measureWidth` {
        @Test
        fun `empty list has zero width`() {
            FragmentLayout.measureWidth(emptyList(), font, frc) shouldBe 0.0
        }

        @Test
        fun `single text fragment has positive width`() {
            val fragments = listOf(Fragment.Text("hello", SimpleTextAttributes.REGULAR_ATTRIBUTES, false))
            val width = FragmentLayout.measureWidth(fragments, font, frc)
            assert(width > 0) { "Expected positive width, got $width" }
        }

        @Test
        fun `multiple fragments sum widths`() {
            val frag1 = Fragment.Text("hello", SimpleTextAttributes.REGULAR_ATTRIBUTES, false)
            val frag2 = Fragment.Text(" world", SimpleTextAttributes.REGULAR_ATTRIBUTES, false)
            val combined = Fragment.Text("hello world", SimpleTextAttributes.REGULAR_ATTRIBUTES, false)

            val sumWidth = FragmentLayout.measureWidth(listOf(frag1, frag2), font, frc)
            val combinedWidth = FragmentLayout.measureWidth(listOf(combined), font, frc)

            // Sum of parts should equal the combined (monospaced font, no kerning difference)
            assert(kotlin.math.abs(sumWidth - combinedWidth) < 1.0) {
                "Expected sumWidth ($sumWidth) ≈ combinedWidth ($combinedWidth)"
            }
        }
    }

    @Nested
    inner class `truncateToFit` {
        @Test
        fun `no truncate range returns fragments unchanged`() {
            val fragments = listOf(Fragment.Text("hello", SimpleTextAttributes.REGULAR_ATTRIBUTES, false))
            val result = FragmentLayout.truncateToFit(fragments, null, 1000.0, font, frc)
            result shouldBe fragments
        }

        @Test
        fun `everything fits returns fragments unchanged`() {
            val fragments = listOf(Fragment.Text("short", SimpleTextAttributes.REGULAR_ATTRIBUTES, true))
            val result = FragmentLayout.truncateToFit(fragments, 0..0, 1000.0, font, frc)
            result shouldBe fragments
        }

        @Test
        fun `truncates long text with ellipsis`() {
            val longText = "A".repeat(100)
            val fragments = listOf(Fragment.Text(longText, SimpleTextAttributes.REGULAR_ATTRIBUTES, true))
            val result = FragmentLayout.truncateToFit(fragments, 0..0, 80.0, font, frc)

            result shouldHaveSize 1
            val text = result[0].shouldBeInstanceOf<Fragment.Text>()
            text.text shouldEndWith "..."
            text.text.length shouldBe (text.text.length) // just asserting it's shorter
            assert(text.text.length < longText.length) { "Expected truncation" }
        }

        @Test
        fun `preserves non-truncatable fragments`() {
            val prefix = Fragment.Text("ID: ", SimpleTextAttributes.REGULAR_ATTRIBUTES, false)
            val longText = Fragment.Text("A".repeat(100), SimpleTextAttributes.REGULAR_ATTRIBUTES, true)
            val suffix = Fragment.Text(" [x]", SimpleTextAttributes.REGULAR_ATTRIBUTES, false)

            val result = FragmentLayout.truncateToFit(listOf(prefix, longText, suffix), 1..1, 120.0, font, frc)

            // prefix and suffix should still be there
            result.first().shouldBeInstanceOf<Fragment.Text>().text shouldBe "ID: "
            result.last().shouldBeInstanceOf<Fragment.Text>().text shouldBe " [x]"

            // middle should be truncated
            val middle = result[1].shouldBeInstanceOf<Fragment.Text>()
            middle.text shouldEndWith "..."
        }

        @Test
        fun `zero available width drops all truncatable`() {
            val prefix = Fragment.Text("ID: ", SimpleTextAttributes.REGULAR_ATTRIBUTES, false)
            val truncatable = Fragment.Text("description", SimpleTextAttributes.REGULAR_ATTRIBUTES, true)

            // Use a width that only fits the non-truncatable prefix
            val prefixWidth = FragmentLayout.measureWidth(listOf(prefix), font, frc)
            val result = FragmentLayout.truncateToFit(listOf(prefix, truncatable), 1..1, prefixWidth, font, frc)

            result shouldHaveSize 1
            result[0].shouldBeInstanceOf<Fragment.Text>().text shouldBe "ID: "
        }

        @Test
        fun `preserves style on truncated fragment`() {
            val boldStyle = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null)
            val fragments = listOf(Fragment.Text("A".repeat(100), boldStyle, true))
            val result = FragmentLayout.truncateToFit(fragments, 0..0, 80.0, font, frc)

            result shouldHaveSize 1
            result[0].shouldBeInstanceOf<Fragment.Text>().style shouldBe boldStyle
        }

        @Test
        fun `multiple truncatable fragments drops overflow`() {
            val frag1 = Fragment.Text("First part ", SimpleTextAttributes.REGULAR_ATTRIBUTES, true)
            val frag2 = Fragment.Text("Second part", SimpleTextAttributes.REGULAR_ATTRIBUTES, true)

            // Enough for first fragment only
            val frag1Width = FragmentLayout.measureWidth(listOf(frag1), font, frc)
            val result = FragmentLayout.truncateToFit(listOf(frag1, frag2), 0..1, frag1Width + 5, font, frc)

            // Second fragment should be dropped, first might be slightly truncated or fit
            assert(result.size <= 2) { "Expected at most 2 fragments" }
            assert(
                result.none { it is Fragment.Text && it.text == "Second part" }
            ) { "Second fragment should be dropped or truncated" }
        }
    }
}
