package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.common.ScaledIcon
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.TransferHandler
import javax.swing.UIManager
import javax.swing.text.Element
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * jj-idea-3as8: icons and chips in [IconAwareHtmlPane] must reserve exactly the space they paint, sit
 * centred on their text line, and scale their text with the IDE zoom - the same as the log table.
 * Zoom is emulated the way "Zoom IDE" applies it: the user scale factor plus a matching label font.
 * Each render is also written to `build/reports/icon-zoom/` for eyeballing.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class IconZoomRenderingTest {
    private val project = projectFixture()

    private val scales = listOf(0.85f, 1f, 1.5f, 2f)

    private fun forEachZoom(block: (Float) -> Unit) {
        val labelFont = UIManager.getFont("Label.font")
        val originalScale = JBUIScale.scale(1f)
        // Unit-test mode swaps real icons for blank placeholders; render the real ones here.
        IconLoader.activate()
        try {
            scales.forEach { scale ->
                JBUIScale.setUserScaleFactorForTest(scale)
                UIManager.put("Label.font", labelFont.deriveFont(labelFont.size2D * scale))
                withClue("zoom $scale") { block(scale) }
            }
        } finally {
            JBUIScale.setUserScaleFactorForTest(originalScale)
            UIManager.put("Label.font", labelFont)
            IconLoader.deactivate()
        }
    }

    private class Rendered(val pane: IconAwareHtmlPane, val image: BufferedImage) {
        val imgs: List<Element> = buildList {
            fun collect(e: Element) {
                if (e.name == "img") add(e)
                for (i in 0 until e.elementCount) collect(e.getElement(i))
            }
            collect(pane.document.defaultRootElement)
        }

        /** The pixel columns [left, right) the element at [elem] reserves in the layout. */
        fun reservedX(elem: Element) =
            pane.modelToView2D(elem.startOffset).bounds.x until pane.modelToView2D(elem.endOffset).bounds.x

        fun lineOf(offset: Int): Rectangle = pane.modelToView2D(offset).bounds

        /** Bounding box of every painted (non-background) pixel within columns [xs]. */
        fun inkIn(xs: IntRange): Rectangle? {
            var box: Rectangle? = null
            for (x in xs.first.coerceAtLeast(0)..xs.last.coerceAtMost(image.width - 1)) {
                for (y in 0 until image.height) {
                    if (image.getRGB(x, y) and 0xffffff != 0xffffff) {
                        box = box?.apply { add(x, y) } ?: Rectangle(x, y, 0, 0)
                    }
                }
            }
            return box
        }
    }

    private fun render(name: String, scale: Float, builder: TextCanvas.() -> Unit): Rendered {
        val html = htmlString { control("<body style='${Formatters.getBodyStyle()}'>", "</body>", builder) }
        val pane = IconAwareHtmlPane(project.get())
        // A zoom change re-applies the look-and-feel's (scaled) fonts to every component.
        pane.font = UIUtil.getLabelFont()
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()
        val height = pane.preferredSize.height.coerceAtLeast(1)
        val image = BufferedImage(pane.width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, image.width, image.height)
            pane.paint(this)
            dispose()
        }
        val dir = File("build/reports/icon-zoom").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, "$name@$scale.png"))
        return Rendered(pane, image)
    }

    private fun tolerance(scale: Float) = (1.5f * scale).roundToInt().coerceAtLeast(1)

    // A platform icon, not one of ours: our SvgIcon loads through a private class loader that yields a
    // 1x1 placeholder in the headless test environment. Headless icons also paint nothing and keep their
    // size when the test's scale changes, so these tests can't look at an icon's own pixels or watch it
    // grow with the zoom. They check what the bug broke around it instead: reserved width vs. the icon's
    // own width (the old code divided it by the zoom), text never drawn over the icon's space, and the
    // icon's slot centred on text that *does* grow with the zoom. docs/manual-tests.md covers the rest.
    private val probeIcon = icon(AllIcons.General::Add)

    @Test
    fun `a standalone icon reserves its real width, centred on its text line`() = forEachZoom { scale ->
        val r = render("icon", scale) {
            append("Hxg")
            append(probeIcon)
            append("Hxg")
        }
        val img = r.imgs.single()
        val reserved = r.reservedX(img)
        val expectedWidth = IconResolver.resolveIcon(probeIcon.qualified)!!.iconWidth

        (reserved.last + 1 - reserved.first) shouldBe expectedWidth
        // The text after the icon starts where the icon's space ends (a pixel of anti-aliasing slack).
        val after = r.inkIn(reserved.last + 1 until r.image.width)!!
        after.x shouldBeGreaterThanOrEqual reserved.last
        // The icon paints centred in its own slot, so the slot must be centred on the text line.
        val slot = r.lineOf(img.startOffset)
        val line = r.lineOf(img.startOffset - 1)
        abs(slot.centerY - line.centerY).roundToInt() shouldBeLessThanOrEqual 1
    }

    @Test
    fun `a smaller chip draws its label after its icon and within its own space`() = forEachZoom { scale ->
        // Shaped like a bookmark chip (appendBookmarkChip): colored { smaller { appendUnbreakable { icon; label } } }
        val r = render("chip", scale) {
            append("Hxg")
            space()
            colored(JujutsuColors.BOOKMARK) {
                smaller {
                    appendUnbreakable {
                        append(probeIcon)
                        append("main")
                    }
                }
            }
        }
        val chip = r.imgs.single()
        val reserved = r.reservedX(chip)
        val iconWidth = ScaledIcon(IconResolver.resolveIcon(probeIcon.qualified)!!, SMALLER_SCALE).iconWidth
        val label = r.inkIn(reserved.first..(reserved.last + 20))!!

        // A pixel of slack either side for anti-aliasing fringe - the bug this guards against put the
        // label over half the icon, and overran the chip by the same amount.
        label.x shouldBeGreaterThanOrEqual reserved.first + iconWidth - 1
        (label.x + label.width) shouldBeLessThanOrEqual reserved.last + 1
    }

    @Test
    fun `chip text scales with the zoom exactly like the surrounding text`() = forEachZoom { scale ->
        val text = "main feature"
        val r = render("chipText", scale) {
            append(text)
            control("<br>")
            appendUnbreakable(text)
        }
        val plainStart = r.pane.document.getText(0, r.pane.document.length).indexOf(text)
        val plainWidth = r.lineOf(plainStart + text.length).x - r.lineOf(plainStart).x
        val chip = r.imgs.single()

        // Within a pixel: the chip measures with FontMetrics, the surrounding GlyphViews with their own layout.
        val chipWidth = r.reservedX(chip).last + 1 - r.reservedX(chip).first
        abs(chipWidth - plainWidth) shouldBeLessThanOrEqual 1
    }

    /** jj-idea-5zio: copying through the pane's own transfer handler includes each chip's text. */
    @Test
    fun `copying a selection that spans chips includes their text`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("committed by")
                space()
                appendWithEmail(VcsUserImpl("Alice", "alice@example.com"))
                space()
                append(Bookmark("main"))
            }
        }
        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()
        pane.selectAll()

        val clipboard = Clipboard("test")
        pane.transferHandler.exportToClipboard(pane, clipboard, TransferHandler.COPY)
        val copied = clipboard.getData(DataFlavor.stringFlavor) as String

        copied shouldContain "committed by"
        copied shouldContain "Alice <alice@example.com>"
        copied shouldContain "main"
    }
}
