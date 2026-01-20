package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import `in`.kkkev.jjidea.jj.Description
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font

/**
 * Tests for DescriptionRenderingStyle - the shared logic for rendering descriptions.
 *
 * These tests verify that the correct text attributes and font styles are used
 * for different combinations of empty/non-empty descriptions and working copy status.
 */
class DescriptionRenderingStyleTest {
    @Nested
    inner class `getTextAttributes for Description` {
        @Test
        fun `empty description, not working copy - gray italic`() {
            val attrs = DescriptionRenderingStyle.getTextAttributes(Description(""), false)

            attrs shouldBe SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES
        }

        @Test
        fun `empty description, is working copy - bold italic gray`() {
            val attrs = DescriptionRenderingStyle.getTextAttributes(Description(""), true)

            attrs.style shouldBe (SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_ITALIC)
            attrs.fgColor shouldBe SimpleTextAttributes.GRAY_ATTRIBUTES.fgColor
        }

        @Test
        fun `non-empty description, not working copy - regular`() {
            val attrs = DescriptionRenderingStyle.getTextAttributes(Description("Test"), false)

            attrs shouldBe SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        @Test
        fun `non-empty description, is working copy - bold`() {
            val attrs = DescriptionRenderingStyle.getTextAttributes(Description("Test"), true)

            attrs shouldBe SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        }
    }

    @Nested
    inner class `getFontStyle for Description` {
        @Test
        fun `empty description, not working copy - italic`() {
            val style = DescriptionRenderingStyle.getFontStyle(Description(""), false)

            style shouldBe Font.ITALIC
        }

        @Test
        fun `empty description, is working copy - bold italic`() {
            val style = DescriptionRenderingStyle.getFontStyle(Description(""), true)

            style shouldBe (Font.BOLD or Font.ITALIC)
        }

        @Test
        fun `non-empty description, not working copy - plain`() {
            val style = DescriptionRenderingStyle.getFontStyle(Description("Test"), false)

            style shouldBe Font.PLAIN
        }

        @Test
        fun `non-empty description, is working copy - bold`() {
            val style = DescriptionRenderingStyle.getFontStyle(Description("Test"), true)

            style shouldBe Font.BOLD
        }
    }

    @Nested
    inner class `getTextColor for Description` {
        private val selectionForeground = Color.WHITE
        private val defaultForeground = Color.BLACK

        @Test
        fun `empty description, not selected - gray`() {
            val color =
                DescriptionRenderingStyle.getTextColor(
                    Description(""),
                    isSelected = false,
                    selectionForeground,
                    defaultForeground
                )

            color shouldBe JBColor.GRAY
        }

        @Test
        fun `empty description, selected - selection foreground`() {
            val color =
                DescriptionRenderingStyle.getTextColor(
                    Description(""),
                    isSelected = true,
                    selectionForeground,
                    defaultForeground
                )

            color shouldBe selectionForeground
        }

        @Test
        fun `non-empty description, not selected - default foreground`() {
            val color =
                DescriptionRenderingStyle.getTextColor(
                    Description("Test"),
                    isSelected = false,
                    selectionForeground,
                    defaultForeground
                )

            color shouldBe defaultForeground
        }

        @Test
        fun `non-empty description, selected - selection foreground`() {
            val color =
                DescriptionRenderingStyle.getTextColor(
                    Description("Test"),
                    isSelected = true,
                    selectionForeground,
                    defaultForeground
                )

            color shouldBe selectionForeground
        }
    }

    @Nested
    inner class `getEmptyIndicatorFontStyle` {
        @Test
        fun `not working copy - italic only`() {
            val style = DescriptionRenderingStyle.getEmptyIndicatorFontStyle(false)

            style shouldBe Font.ITALIC
        }

        @Test
        fun `is working copy - bold italic`() {
            val style = DescriptionRenderingStyle.getEmptyIndicatorFontStyle(true)

            style shouldBe (Font.BOLD or Font.ITALIC)
        }
    }
}
