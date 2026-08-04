package `in`.kkkev.jjidea.ui.components

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.awt.Font

/**
 * Regression tests for the "chip text renders in Swing's default serif font" bug: [AtomicHtmlView]'s
 * inner document has no idea about the outer pane's font unless [displayPropertiesToCss] seeds a
 * `body` rule for it (mirroring `javax.swing.plaf.basic.BasicHTML`'s own private-document setup).
 */
class AtomicHtmlViewFontTest {
    // java.awt.Font silently substitutes an unavailable family with a platform fallback at
    // construction time, so font.family may not echo back the name passed to the constructor - the
    // real bug this class exists to prevent is a chip's family drifting from the *pane's*, so the
    // assertion below reads back font.family (whatever the host actually resolved) rather than
    // asserting a hardcoded literal, which would only prove the round-trip for names immune to
    // substitution and say nothing about family-name fidelity in general.
    @Test
    fun `plain font produces a family and size rule with no weight or style overrides`() {
        val font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val css = displayPropertiesToCss(font)

        css shouldContain "font-family: ${font.family}"
        css shouldContain "font-size: 12pt"
        css shouldNotContain "font-weight"
        css shouldNotContain "font-style"
    }

    @Test
    fun `bold italic font adds weight and style rules`() {
        val css = displayPropertiesToCss(Font(Font.SANS_SERIF, Font.BOLD or Font.ITALIC, 12))

        css shouldContain "font-weight: 700"
        css shouldContain "font-style: italic"
    }

    @Test
    fun `null font produces an empty body rule`() {
        val css = displayPropertiesToCss(null)

        css shouldNotContain "font-family"
    }
}
