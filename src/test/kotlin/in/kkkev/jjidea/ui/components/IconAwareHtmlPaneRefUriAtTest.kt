package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import javax.swing.text.Element

/**
 * Regression test for [IconAwareHtmlPane.refUriAt] (jj-idea-iesq): right-clicking a bookmark/tag
 * chip in the commit details pane resolves its `jjref://` href to build the ref context menu.
 * This was silently broken for *every* chip - Swing's HTML parser flattens a character-level
 * `<a href>` onto the wrapped leaf's own `AttributeSet` as a nested set keyed by `HTML.Tag.A`,
 * not as a directly-queryable `HTML.Attribute.HREF` the way `refUriAt`'s original
 * `elem.attributes.getAttribute(HTML.Attribute.HREF)` assumed - discovered while adding chip
 * hover-underline tracking, which hit the identical lookup bug (see `hasHrefAncestor`/`hrefOf`).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class IconAwareHtmlPaneRefUriAtTest {
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

    @Test
    fun `refUriAt resolves the jjref href of a bookmark chip under the pointer`() {
        val refUri = URI("jjref://repo?abc123&kind=bookmark&name=main")
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                linked(refUri) { appendChip(icon(JujutsuIcons::Bookmark), "main") }
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val chip = collectImgElements(pane.document.defaultRootElement).single()
        val point = pane.modelToView2D(chip.startOffset).bounds.let { java.awt.Point(it.x + 1, it.centerY.toInt()) }

        pane.refUriAt(point) shouldBe refUri
    }

    @Test
    fun `refUriAt returns null for a point not over any ref chip`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("plain text, no chip")
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        pane.refUriAt(java.awt.Point(1, 5)).shouldBeNull()
    }

    @Test
    fun `hasHrefAncestor-driven chip hover also resolves for a bookmark chip`() {
        // Same underlying lookup as refUriAt, exercised via the hover path added alongside it.
        val refUri = URI("jjref://repo?abc123&kind=bookmark&name=main")
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                linked(refUri) { appendChip(icon(JujutsuIcons::Bookmark), "main") }
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val chip = collectImgElements(pane.document.defaultRootElement).single()
        val point = pane.modelToView2D(chip.startOffset).bounds.let { java.awt.Point(it.x + 1, it.centerY.toInt()) }
        pane.dispatchEvent(
            java.awt.event.MouseEvent(
                pane,
                java.awt.event.MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                point.x,
                point.y,
                0,
                false
            )
        )

        pane.hoveredChipElement.shouldNotBeNull()
    }
}
