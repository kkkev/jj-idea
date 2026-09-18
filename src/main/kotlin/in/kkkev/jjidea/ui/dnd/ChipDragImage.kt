package `in`.kkkev.jjidea.ui.dnd

import com.intellij.ide.dnd.DnDImage
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas
import `in`.kkkev.jjidea.ui.components.TextCanvasPanel
import `in`.kkkev.jjidea.ui.components.bookmarkIcon
import `in`.kkkev.jjidea.ui.components.icon
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.image.BufferedImage

/**
 * A small "chip" image following the cursor for the duration of a [DragPayload.BookmarkRef] or
 * [DragPayload.TagRef] drag - the same treatment `ChangesTreeDnDSupport` gives a file drag in the
 * Project view, shared across every surface a bookmark/tag chip can be dragged from (the log
 * table, the bookmarks panel, and the commit details panel, batch 4) so the three don't each carry
 * a byte-identical copy. `null` for every other payload kind.
 *
 * [foreground]/[background]/[font] supply the rendering context rather than a component directly -
 * `com.intellij.ui.render.RenderingUtil.getForeground`/`getBackground` only overload for
 * `JList`/`JTable`/`JTree`, and the commit details pane's `IconAwareHtmlPane` (a `JEditorPane`) is
 * none of those - so each call site resolves its own colors (via `RenderingUtil` where its
 * component is one of those three, or the component's plain `foreground`/`background` otherwise)
 * and passes them in, matching how the pre-extraction copies each rendered against their own
 * surface.
 */
fun chipDragImage(foreground: Color, background: Color, font: Font, payload: DragPayload): DnDImage? {
    if (payload !is DragPayload.BookmarkRef && payload !is DragPayload.TagRef) return null

    val canvas = FragmentRecordingCanvas()
    canvas.foreground(foreground) {
        when (payload) {
            is DragPayload.BookmarkRef -> {
                append(icon(bookmarkIcon(payload.bookmark)))
                append(" ")
                append(payload.bookmark.name.name)
            }
            is DragPayload.TagRef -> {
                append(icon(JujutsuIcons::Tag))
                append(" ")
                append(payload.tag.name)
            }
            else -> Unit
        }
    }

    val panel = TextCanvasPanel().apply {
        isOpaque = true
        this.background = background
        this.font = font
        border = JBUI.Borders.empty(2, 4)
    }
    panel.renderFrom(canvas)
    panel.size = panel.preferredSize
    // renderFrom only adds child components - nothing has positioned them within the panel's
    // bounds yet, unlike a real ListCellRenderer (whose containing JList validates it as part of
    // the platform's own rendering pass before painting).
    panel.doLayout()

    val image = UIUtil.createImage(panel, panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
    val g2 = image.graphics as Graphics2D
    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f)
    panel.paint(g2)
    g2.dispose()

    // Places the whole label up-and-left of the cursor (cursor sits at its bottom-right corner),
    // the same offset ChangesTreeDnDSupport.createDragImage uses.
    return DnDImage(image, Point(-image.getWidth(null), -image.getHeight(null)))
}
