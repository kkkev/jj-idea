package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.scale.JBUIScale
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.swing.text.Element
import kotlin.math.roundToInt

/**
 * Regression tests for jj-idea-c6f5: exercises the *real* rendering pipeline (production HTML generation, then
 * [IconAwareHtmlPane]/[com.intellij.ui.components.JBHtmlPane]'s Jsoup-based input transpilation, then Swing's
 * HTMLEditorKit layout) rather than just asserting on the pre-transpile HTML string, since transpilation and Swing
 * layout are exactly where earlier attempts at this fix silently broke (a `white-space: nowrap` span was ignored by
 * Swing's glyph-breaking, and a plain space between two atomic chip elements needed empirical verification that it
 * survives as a real break point).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class IconAwareHtmlPaneWrapTest {
    private val project = projectFixture()

    @Test
    fun `long committer line wraps between the name-email chip and the date chip, never inside either`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("committed by ")
                appendWithEmail(
                    VcsUserImpl("GitHub", "49699333+dependabot[bot]@users.noreply.github.com")
                )
                append(" ")
                appendUnbreakable("· 12/07/2026, 04:07")
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(180, 1000)
        pane.doLayout()

        val icons = mutableListOf<Element>()
        fun collect(e: Element) {
            if (e.name == "img") icons.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(pane.document.defaultRootElement)

        // One atomic chip for "GitHub <49699333+...>", one for the mid-dot-prefixed date.
        icons shouldHaveSize 2

        val nameEmailY = pane.modelToView2D(icons[0].startOffset).bounds.y
        val dateStart = pane.modelToView2D(icons[1].startOffset).bounds
        val dateEnd = pane.modelToView2D(icons[1].endOffset).bounds

        // The two chips must land on different rows (proving a real break point exists between them, and that
        // neither chip itself got split -- a split chip would still report a single startOffset row per element).
        dateStart.y shouldBeGreaterThan nameEmailY
        // The wrapped-onto-its-own-line date chip must still render its actual content, not silently
        // collapse to zero width - the user-visible symptom of AtomicHtmlView's inner root view being
        // queried for its preferred span before ever being sized (see the height-consistency test below).
        (dateEnd.x - dateStart.x) shouldBeGreaterThan 0
    }

    @Test
    fun `same committer line leaves a visible gap between the name-email chip and the date chip when both fit`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("committed by ")
                appendWithEmail(VcsUserImpl("GitHub", "noreply@github.com"))
                append(" ")
                appendUnbreakable("· 12/07/2026, 04:07")
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val icons = mutableListOf<Element>()
        fun collect(e: Element) {
            if (e.name == "img") icons.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(pane.document.defaultRootElement)
        icons shouldHaveSize 2

        // modelToView at a leaf's endOffset reports its right edge (see IconAwareHtmlPane's custom modelToView:
        // pos == p1 shifts x by the allocation's full width before zeroing it), so compare chip0's right edge
        // against chip1's left edge to measure the actual rendered gap between them.
        val nameEmailEnd = pane.modelToView2D(icons[0].endOffset).bounds
        val dateStart = pane.modelToView2D(icons[1].startOffset).bounds

        // The gap consists of a single U+00A0 (matching the space embedded in the date chip's own label after the
        // mid-dot -- both measured via the identical fontMetrics.stringWidth() call pattern, matching by
        // construction) plus AtomicHtmlView's extra LEADING_GAP fixed-pixel padding. This padding is applied
        // unconditionally to every chip, including chip0 (the name/email chip): chip0's own leading gap widens its
        // total allocation by the same amount it shifts chip0's visible right edge, so it cancels out of this
        // formula, leaving only chip1's leading gap to account for here. The "2" below must be kept in sync with
        // AtomicHtmlView.LEADING_GAP.
        val expectedGap = pane.getFontMetrics(pane.font).stringWidth(" ") + (2 * JBUIScale.scale(1f)).roundToInt()
        nameEmailEnd.y shouldBe dateStart.y
        (dateStart.x - nameEmailEnd.x) shouldBe expectedGap
    }

    /**
     * Regression test for the chip-text-renders-in-the-wrong-font bug: [AtomicHtmlView]'s inner
     * document has no ambient CSS of its own, so without seeding a font/color rule from the outer
     * pane it silently falls back to Swing's built-in HTML stylesheet default (a serif font) - a
     * mismatch from the rest of the pane's text, not merely "looks wrong" in isolation. Measures the
     * *actual rendered pixel width* of an unbreakable chip's text against `pane`'s own font metrics
     * for the identical string, rather than introspecting [AtomicHtmlView]'s private state - if the
     * chip's font family or size ever drifts from the pane's, the two widths diverge.
     */
    @Test
    fun `unbreakable chip text renders using the pane's own font, not a mismatched fallback`() {
        val text = "12/07/2026, 04:07"
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") { appendUnbreakable(text) }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val icons = mutableListOf<Element>()
        fun collect(e: Element) {
            if (e.name == "img") icons.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(pane.document.defaultRootElement)
        icons shouldHaveSize 1

        val start = pane.modelToView2D(icons[0].startOffset).bounds
        val end = pane.modelToView2D(icons[0].endOffset).bounds
        val renderedWidth = end.x - start.x

        // No icon/leadingGap ambiguity to account for here (unlike the gap test above): comparing
        // against fontMetrics.stringWidth directly isolates font-family/size drift specifically -
        // any mismatch (e.g. the default serif fallback instead of the pane's real font) would move
        // this measurement by more than rounding error, since the two typefaces have different glyph
        // widths for the same string.
        val expectedWidth = pane.getFontMetrics(pane.font).stringWidth(text)
        renderedWidth shouldBe expectedWidth
    }

    /**
     * Regression test for a large blank gap rendering above a chip. The earlier design asked the
     * parsed fragment's `<p>`-wrapped root for its own `getPreferredSpan(Y_AXIS)`, which - being a
     * `ParagraphView`/`FlowView` - both carried a spurious 15px `<p>` margin (Swing's `default.css`)
     * *and* depended on incidental layout-pass ordering: an otherwise-identical chip reported a
     * taller row specifically when it was the row immediately before a forced `<br>` break, even
     * with matching container/font (see git history for the empirical trace). [AtomicHtmlView] no
     * longer builds a `ParagraphView`/`FlowView` at all (see its class doc) - every row height here
     * must be *identical*, regardless of position: first row, immediately before a forced break, and
     * the very last row (nothing forcing a break after it) all report the pane's own single-line
     * font height, not something larger for some positions and not others.
     */
    @Test
    fun `chip row height is identical regardless of position - first, before a break, and last`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                appendUnbreakable("first row")
                control("<br>")
                appendUnbreakable("middle row, before a break")
                control("<br>")
                appendUnbreakable("last row")
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val icons = mutableListOf<Element>()
        fun collect(e: Element) {
            if (e.name == "img") icons.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(pane.document.defaultRootElement)
        icons shouldHaveSize 3

        val expectedHeight = pane.getFontMetrics(pane.font).height
        icons.forEach { icon ->
            pane.modelToView2D(icon.startOffset).bounds.height shouldBe expectedHeight
        }
    }
}
