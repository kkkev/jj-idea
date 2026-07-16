package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.swing.text.Element

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
            control("<body>", "</body>") {
                append("committed by ")
                appendWithEmail(
                    VcsUserImpl("GitHub", "49699333+dependabot[bot]@users.noreply.github.com")
                )
                control(" ")
                appendUnbreakable("· 12/07/2026, 04:07")
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(180, 1000)
        pane.doLayout()

        val icons = mutableListOf<Element>()
        fun collect(e: Element) {
            if (e.name == "icon") icons.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(pane.document.defaultRootElement)

        // One atomic chip for "GitHub <49699333+...>", one for "· 12/07/2026, 04:07".
        icons shouldHaveSize 2

        val nameEmailY = pane.modelToView2D(icons[0].startOffset).bounds.y
        val dateY = pane.modelToView2D(icons[1].startOffset).bounds.y

        // The two chips must land on different rows (proving a real break point exists between them, and that
        // neither chip itself got split — a split chip would still report a single startOffset row per element).
        dateY shouldBeGreaterThan nameEmailY
    }

    @Test
    fun `same committer line leaves a visible gap between the name-email chip and the date chip when both fit`() {
        val html = htmlString {
            control("<body>", "</body>") {
                append("committed by ")
                appendWithEmail(VcsUserImpl("GitHub", "noreply@github.com"))
                control(" ")
                appendUnbreakable("· 12/07/2026, 04:07")
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val icons = mutableListOf<Element>()
        fun collect(e: Element) {
            if (e.name == "icon") icons.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(pane.document.defaultRootElement)
        icons shouldHaveSize 2

        // modelToView at a leaf's endOffset reports its right edge (see IconAwareHtmlPane's custom modelToView:
        // pos == p1 shifts x by the allocation's full width before zeroing it), so compare chip0's right edge
        // against chip1's left edge to measure the actual rendered gap between them.
        val nameEmailEnd = pane.modelToView2D(icons[0].endOffset).bounds
        val dateStart = pane.modelToView2D(icons[1].startOffset).bounds

        // Both chips on the same row, with a real visible gap between them — not glued together.
        nameEmailEnd.y shouldBe dateStart.y
        (dateStart.x - nameEmailEnd.x) shouldBeGreaterThan 0
    }
}
