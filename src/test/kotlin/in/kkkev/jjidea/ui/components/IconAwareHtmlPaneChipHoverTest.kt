package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import javax.swing.text.Element

/**
 * Tests for the commit details pane's mailto-chip hover tracking (jj-idea-iesq): dispatches real
 * mouse-move events at the actual rendered pixel position of a `<img>` chip, exercising
 * [IconAwareHtmlPane]'s real hit-testing (`linkedChipElementAt`/`hasHrefAncestor`) against the
 * real JBHtmlPane/Jsoup-transpiled layout, rather than asserting on internal state directly - this
 * is exactly where jj-idea-iesq's first attempt at this feature (a `white-space: nowrap` span)
 * silently failed, so the real pipeline is what actually needs verifying.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class IconAwareHtmlPaneChipHoverTest {
    private val project = projectFixture()

    private fun collectImgElements(root: Element): List<Element> {
        val result = mutableListOf<Element>()
        fun collect(e: Element) {
            if (e.name == "img") result.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(root)
        return result
    }

    private fun moveMouseTo(pane: IconAwareHtmlPane, offset: Int) {
        val point = pane.modelToView2D(offset).bounds.let { java.awt.Point(it.x + 1, it.centerY.toInt()) }
        pane.dispatchEvent(
            MouseEvent(pane, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false)
        )
    }

    @Test
    fun `hovering the name-email chip resolves it as the hovered link element`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("committed by ")
                appendWithEmail(VcsUserImpl("GitHub", "noreply@github.com"))
                append(" ")
                appendUnbreakable("· 12/07/2026, 04:07")
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val chips = collectImgElements(pane.document.defaultRootElement)
        val nameEmailChip = chips[0]

        pane.hoveredChipElement.shouldBeNull()
        moveMouseTo(pane, nameEmailChip.startOffset)
        pane.hoveredChipElement.shouldNotBeNull()
    }

    @Test
    fun `hovering the non-linked date chip does not resolve a hovered link element`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("committed by ")
                appendWithEmail(VcsUserImpl("GitHub", "noreply@github.com"))
                append(" ")
                appendUnbreakable("· 12/07/2026, 04:07")
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val chips = collectImgElements(pane.document.defaultRootElement)
        val dateChip = chips[1]

        moveMouseTo(pane, dateChip.startOffset)
        pane.hoveredChipElement.shouldBeNull()
    }

    @Test
    fun `moving off the chip clears the hovered link element`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("committed by ")
                appendWithEmail(VcsUserImpl("GitHub", "noreply@github.com"))
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val chip = collectImgElements(pane.document.defaultRootElement).single()
        moveMouseTo(pane, chip.startOffset)
        pane.hoveredChipElement.shouldNotBeNull()

        // "committed by " precedes the chip - offset 0 is plain text, not a chip.
        moveMouseTo(pane, 0)
        pane.hoveredChipElement.shouldBeNull()
    }
}
