package `in`.kkkev.jjidea.ui.common

import com.intellij.ui.JBColor
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.Color
import kotlin.math.pow

/**
 * jj-idea-mn1a: several [JujutsuColors] entries are painted as foreground text (bookmark/tag
 * chips, the `@` working-copy marker, conflict/divergent markers - see `LogEntryText.kt`,
 * `JujutsuLogTableRenderers.kt`, `JujutsuFileAnnotation.kt`), so they need WCAG AA text contrast
 * (>=4.5:1) against the panel background they're drawn on. Reported by a user on GitHub #51: the
 * bookmark gold was ~1.6:1 on a light background - essentially unreadable. This test pins every
 * text-role palette entry to a minimum contrast ratio, in both light and dark theme, so the
 * palette can't silently regress back to an unreadable value.
 *
 * [JujutsuColors.SOURCE_HIGHLIGHT] / [JujutsuColors.DESTINATION_HIGHLIGHT] are excluded: they're
 * used as row-highlight backgrounds and small legend swatches, not text, so the (lower) 3:1
 * non-text bar would apply instead - and they aren't implicated in the reported bug.
 */
class JujutsuColorsContrastTest {
    // Representative light/dark panel backgrounds the log/annotation views render on top of.
    // Not the exact IDE theme value (which varies by IDE/theme version) - close enough that a
    // color passing here has real headroom, and one failing here is a genuine problem.
    private val lightBackground = Color(0xF2, 0xF2, 0xF2)
    private val darkBackground = Color(0x2B, 0x2D, 0x30)

    private val minTextContrast = 4.5

    private val textRoleColors: Map<String, JBColor> = mapOf(
        "WORKING_COPY" to JujutsuColors.WORKING_COPY,
        "BOOKMARK" to JujutsuColors.BOOKMARK,
        "TAG" to JujutsuColors.TAG,
        "CONFLICT" to JujutsuColors.CONFLICT,
        "DIVERGENT" to JujutsuColors.DIVERGENT
    )

    @Test
    fun `text-role palette entries meet WCAG AA contrast on light background`() {
        // A JBColor instance's own RGB (as a Color) is its light-theme value; see JBColor's
        // `Color(Color regular, Color dark)` constructor, which passes `regular` straight to
        // `super()`.
        textRoleColors.forEach { (name, color) ->
            val ratio = contrastRatio(color, lightBackground)
            (ratio >= minTextContrast) shouldBe true
        }
    }

    @Test
    fun `text-role palette entries meet WCAG AA contrast on dark background`() {
        textRoleColors.forEach { (name, color) ->
            val ratio = contrastRatio(color.darkVariant, darkBackground)
            (ratio >= minTextContrast) shouldBe true
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
        val darker = minOf(relativeLuminance(a), relativeLuminance(b))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
