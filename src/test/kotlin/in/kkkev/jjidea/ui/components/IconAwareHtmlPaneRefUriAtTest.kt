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
 * hover tracking, which hit the identical lookup bug (see `hrefAncestorOf`/`hrefOf`). Also covers
 * that hovering a bookmark chip *does* set a hovered link element (jj-idea-a52h) - it just paints
 * a background highlight instead of an underline (AtomicHtmlView.isRefOnly vs isRealLink), since the chip
 * has no left-click action but does have a right-click menu.
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
                linked(refUri) {
                    appendUnbreakable {
                        append(icon(JujutsuIcons::Bookmark))
                        append("main")
                    }
                }
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
    fun `refUriAt resolves the jjref href from a point over the right half of the chip`() {
        // Regression for a real bug report: AtomicHtmlView.viewToModel (needed for caret placement)
        // returns the chip's own endOffset for its right half, but a leaf's range is
        // [startOffset, endOffset) - exclusive of endOffset - so getCharacterElement(endOffset)
        // used to resolve the *next* sibling instead of the chip, silently breaking hover/
        // right-click over roughly the right half of every chip (jj-idea-wkcz follow-up).
        val refUri = URI("jjref://repo?abc123&kind=bookmark&name=main")
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                linked(refUri) {
                    appendUnbreakable {
                        append(icon(JujutsuIcons::Bookmark))
                        append("main")
                    }
                }
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val chip = collectImgElements(pane.document.defaultRootElement).single()
        // AtomicHtmlView.modelToView reports the chip's real right edge for its own endOffset (unlike a
        // regular text run, where end-of-run x wouldn't necessarily coincide with this element).
        val rightEdge = pane.modelToView2D(chip.endOffset).bounds
        val point = java.awt.Point(rightEdge.x - 2, rightEdge.centerY.toInt())

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
    fun `hrefAt resolves a mailto href, unlike refUriAt which is jjref-only (jj-idea-a52h)`() {
        // The commit details pane's right-click handler used to only ever call refUriAt, so an
        // author/committer mailto: link had no way to resolve to a PersonClick at all - hrefAt is
        // the general entry point that makes that possible.
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                appendWithEmail(`in`.kkkev.jjidea.vcs.VcsUserImpl("Alice", "alice@example.com"))
            }
        }

        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        val chip = collectImgElements(pane.document.defaultRootElement).single()
        val point = pane.modelToView2D(chip.startOffset).bounds.let { java.awt.Point(it.x + 1, it.centerY.toInt()) }

        pane.hrefAt(point) shouldBe "mailto:alice@example.com"
        pane.refUriAt(point).shouldBeNull()
    }

    @Test
    fun `hovering a bookmark chip sets a hovered link element (jj-idea-a52h)`() {
        // Bookmark/tag chips have no left-click action, but they do get a hover cue - a background
        // highlight rather than an underline (AtomicHtmlView paints based on isRefOnly vs isRealLink).
        val refUri = URI("jjref://repo?abc123&kind=bookmark&name=main")
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                linked(refUri) {
                    appendUnbreakable {
                        append(icon(JujutsuIcons::Bookmark))
                        append("main")
                    }
                }
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
