package `in`.kkkev.jjidea.ui.components

import com.intellij.ide.ui.UISettings
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ExtendableHTMLViewFactory
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.ui.common.ScaledIcon
import java.awt.*
import javax.swing.Icon
import javax.swing.text.*
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument
import kotlin.math.roundToInt

/**
 * Resolves `<img>` elements whose `src` starts with [UNBREAKABLE_PREFIX] into a single atomic
 * [AtomicHtmlView], so a caller's content (icons, links, colored text, in any combination) is never
 * split across lines by HTML wrapping (jj-idea-kds1).
 *
 * `<img>` (rather than a custom tag) is used because it's a genuine HTML void element - JBHtmlPane's
 * Jsoup transpiler round-trips a self-closed custom tag into an open/close pair, which Swing's parser
 * then splits into two sibling Elements instead of one (jj-idea-vll4/jj-idea-m2wr).
 */
internal object AtomicHtmlExtension : ExtendableHTMLViewFactory.Extension {
    override fun invoke(element: Element, defaultView: View): View? {
        if (element.name != "img") return null
        val src = element.attributes.getAttribute(HTML.Attribute.SRC) as? String ?: return null
        if (!src.startsWith(UNBREAKABLE_PREFIX)) return null
        return AtomicHtmlView(element, UnbreakableContent.decode(src.removePrefix(UNBREAKABLE_PREFIX)))
    }
}

/**
 * A leaf view laying out and painting [content]'s runs itself, in a single unbreakable horizontal row,
 * the same way the log table does (jj-idea-3as8): fonts are derived from the surrounding text's own font
 * with each run's extra style (`smaller` is [SMALLER_SCALE]), icons are resolved via [IconResolver] at
 * their real (zoom-scaled) size and centred on the line, and text runs share a baseline. The outer
 * document sees exactly one atomic Element/View (this one).
 *
 * Its metrics are cached lazily, like [IconLeafView]'s: they need `container` and the parent view,
 * which are only wired up once the outer ViewFactory calls `setParent`.
 */
internal class AtomicHtmlView(elem: Element, private val content: ChipContent) : View(elem) {
    companion object {
        // IntelliJ 2026.2 renders text adjacent to an <img> element tighter than font-metrics math
        // predicts (jj-idea-vll4/jj-idea-m2wr); this is an empirically-tuned pixel correction for it.
        private const val LEADING_GAP = 2
    }

    private class Piece(val run: ChipRun, val width: Int, val font: Font?, val icon: Icon?)

    private val styleSheet get() = (document as? HTMLDocument)?.styleSheet

    // The same attributes the surrounding text's own InlineViews resolve their font and color from, so
    // the unit's text matches its neighbours at any IDE zoom (jj-idea-3as8).
    private val viewAttributes: AttributeSet by lazy { styleSheet?.getViewAttributes(this) ?: element.attributes }

    private val baseFont: Font by lazy { styleSheet?.getFont(viewAttributes) ?: UIUtil.getLabelFont() }

    // StyleSheet.getForeground silently falls back to Color.BLACK rather than returning null when no
    // color applies - treated as "no color" (no color in this app is ever intentionally pure black).
    private val ambientForeground: Color by lazy {
        styleSheet?.getForeground(viewAttributes)?.takeUnless { it == Color.BLACK } ?: UIUtil.getLabelForeground()
    }

    private fun metrics(font: Font): FontMetrics =
        container?.getFontMetrics(font) ?: FALLBACK_FONT_METRICS_COMPONENT.getFontMetrics(font)

    /** [baseFont] plus the font style [style] adds on top of [ChipContent.outerStyle], as [FragmentLayout] does. */
    private fun fontFor(style: Int): Font {
        val added = style and content.outerStyle.inv()
        val font = baseFont.deriveFont(baseFont.style or (added and (Font.BOLD or Font.ITALIC)))
        val smaller = (added and SimpleTextAttributes.STYLE_SMALLER) != 0
        return if (smaller) font.deriveFont(font.size2D * SMALLER_SCALE) else font
    }

    private val pieces: List<Piece> by lazy {
        content.runs.map { run ->
            when (run) {
                is ChipRun.Text -> fontFor(run.style).let { Piece(run, metrics(it).stringWidth(run.text), it, null) }
                is ChipRun.Icon -> {
                    val smaller = (run.style and SimpleTextAttributes.STYLE_SMALLER) != 0
                    val icon = IconResolver.resolveIcon(run.key)
                        ?.let { if (smaller) ScaledIcon(it, SMALLER_SCALE) else it }
                    Piece(run, icon?.iconWidth ?: 0, null, icon)
                }
            }
        }
    }

    private val textMetrics: List<FontMetrics> by lazy {
        pieces.mapNotNull { it.font?.let(::metrics) } + metrics(baseFont)
    }

    /** Distance from the top of the text line to its shared baseline. */
    private val textAscent: Int by lazy { textMetrics.maxOf { it.ascent } }
    private val textHeight: Int by lazy { textMetrics.maxOf { it.height } }

    /** The unit's height: its text line, or its tallest icon if that's taller (icons are centred on the line). */
    private val height: Int by lazy { maxOf(textHeight, pieces.maxOfOrNull { it.icon?.iconHeight ?: 0 } ?: 0) }
    private val baseline: Int by lazy { (height - textHeight) / 2 + textAscent }

    private val leadingGap: Int
        get() = (LEADING_GAP * JBUIScale.scale(1f)).roundToInt()

    /** Whether [this] carries a hover cue at all - a real link (underline) or a `jjref://` ref (background). */
    private val ancestorHref: String? by lazy { hrefAncestorOf(element) }
    private val isRealLink: Boolean by lazy { ancestorHref?.startsWith("jjref://") == false }
    private val isRefOnly: Boolean by lazy { ancestorHref?.startsWith("jjref://") == true }
    private val isHovered: Boolean
        get() = (isRealLink || isRefOnly) && (container as? IconAwareHtmlPane)?.hoveredChipElement === element

    override fun getPreferredSpan(axis: Int): Float = when (axis) {
        X_AXIS -> (leadingGap + pieces.sumOf { it.width }).toFloat()
        Y_AXIS -> height.toFloat()
        else -> throw IllegalArgumentException("Invalid axis: $axis")
    }

    /** Aligns the unit's own text baseline with the surrounding text's. */
    override fun getAlignment(axis: Int): Float =
        if (axis == Y_AXIS) baseline.toFloat() / height else super.getAlignment(axis)

    /** [pieces]' own bounds for a unit whose content starts at ([originX], [originY]), in [pieces] order. */
    private fun pieceBounds(originX: Int, originY: Int): List<Rectangle> {
        var x = originX
        return pieces.map { piece ->
            val bounds = if (piece.font != null) {
                val fm = metrics(piece.font)
                Rectangle(x, originY + baseline - fm.ascent, piece.width, fm.height)
            } else {
                val iconHeight = piece.icon?.iconHeight ?: 0
                Rectangle(x, originY + (height - iconHeight) / 2, piece.width, iconHeight)
            }
            x += piece.width
            bounds
        }
    }

    override fun paint(g: Graphics, allocation: Shape) {
        val rect = allocation.bounds
        val startX = rect.x + leadingGap

        if (isHovered && isRefOnly && hoveredIssueLinkUri() == null) {
            // Hover-highlight background for a right-click-only ref chip (jj-idea-a52h), suppressed
            // while a linkified issue-tracker substring inside is itself hovered (jj-idea-vrmv).
            g.color = UIUtil.getListBackground(true, false)
            g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 4, 4)
        }

        val bounds = pieceBounds(startX, rect.y)
        val g2 = g.create() as Graphics2D
        try {
            UISettings.setupAntialiasing(g2)
            pieces.forEachIndexed { i, piece -> paintPiece(g2, piece, bounds[i], rect.y + baseline) }
        } finally {
            g2.dispose()
        }
        val endX = bounds.lastOrNull()?.let { it.x + it.width } ?: startX

        if (isHovered && isRealLink) {
            // Underline the whole unit while hovered - this leaf isn't a live <a>, so it gets no
            // native hover-underline of its own (jj-idea-iesq).
            val underlineY = rect.y + baseline + 1
            g.color = SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES.fgColor
            g.drawLine(startX, underlineY, endX, underlineY)
        }

        underlineHoveredIssueLink(g, bounds, rect.y + baseline + 1)
    }

    private fun paintPiece(g: Graphics2D, piece: Piece, bounds: Rectangle, baselineY: Int) {
        piece.icon?.paintIcon(container, g, bounds.x, bounds.y)
        val run = piece.run as? ChipRun.Text ?: return
        val font = piece.font ?: return
        g.font = font
        g.color = run.color ?: ambientForeground
        g.drawString(run.text, bounds.x, baselineY)
        val right = bounds.x + bounds.width
        if ((run.style and SimpleTextAttributes.STYLE_STRIKEOUT) != 0) {
            val y = baselineY - metrics(font).ascent * 3 / 10
            g.drawLine(bounds.x, y, right, y)
        }
        if ((run.style and SimpleTextAttributes.STYLE_UNDERLINE) != 0) {
            g.drawLine(bounds.x, baselineY + 1, right, baselineY + 1)
        }
    }

    /** Underline just the runs linking to [IconAwareHtmlPane.hoveredIssueLinkUri], if any (jj-idea-vrmv follow-up). */
    private fun underlineHoveredIssueLink(g: Graphics, bounds: List<Rectangle>, underlineY: Int) {
        val hovered = hoveredIssueLinkUri()?.toString() ?: return
        g.color = SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES.fgColor
        pieces.forEachIndexed { i, piece ->
            val r = bounds[i]
            if (piece.run.link == hovered) g.drawLine(r.x, underlineY, r.x + r.width, underlineY)
        }
    }

    private fun hoveredIssueLinkUri() = (container as? IconAwareHtmlPane)?.hoveredIssueLinkUri

    /**
     * The link opened inside this unit (e.g. an issue reference in a bookmark name) at content-relative
     * pixel [contentX], if any - never the unit's own surrounding link.
     */
    @Suppress("UNUSED_PARAMETER")
    fun hrefAtContentX(contentX: Int, contentY: Int): String? {
        val bounds = pieceBounds(0, 0)
        val index = bounds.indexOfFirst { contentX in it.x until (it.x + it.width) }
        return pieces.getOrNull(index)?.run?.link
    }

    override fun modelToView(pos: Int, a: Shape, b: Position.Bias): Shape {
        if (pos !in startOffset..endOffset) {
            throw BadLocationException("$pos not in range $startOffset,$endOffset", pos)
        }
        val r = a.bounds
        // Shift startOffset past leadingGap so hit-testing sees where content really starts.
        when (pos) {
            startOffset -> r.x += leadingGap
            endOffset -> r.x += r.width
        }
        r.width = 0
        return r
    }

    override fun viewToModel(x: Float, y: Float, a: Shape, bias: Array<Position.Bias>): Int {
        val alloc = a as Rectangle
        if (x < alloc.x + alloc.width / 2f) {
            bias[0] = Position.Bias.Forward
            return startOffset
        }
        bias[0] = Position.Bias.Backward
        return endOffset
    }
}

/** Font-metrics source for a leaf view not yet attached to a [Component] - `Toolkit.getFontMetrics` is deprecated. */
private val FALLBACK_FONT_METRICS_COMPONENT = Canvas()

/** Marker prefix on an `<img>` element's `src`, resolved to a real icon via [IconResolver] - see [iconViewOrNull]. */
internal const val ICON_IMG_PREFIX = "icon:"

/**
 * Resolves an `<img src='icon:...'/>` element in the outer [IconAwareHtmlPane] document to an
 * [IconLeafView], or null if [elem] isn't one.
 */
private fun iconViewOrNull(elem: Element): View? {
    if (elem.name != "img") return null
    val src = elem.attributes.getAttribute(HTML.Attribute.SRC) as? String ?: return null
    if (!src.startsWith(ICON_IMG_PREFIX)) return null
    val icon = IconResolver.resolveIcon(src.removePrefix(ICON_IMG_PREFIX)) ?: return null
    return IconLeafView(elem, icon)
}

/** [iconViewOrNull] wired into the *outer* [IconAwareHtmlPane] document's extension list. */
internal object IconImgExtension : ExtendableHTMLViewFactory.Extension {
    override fun invoke(element: Element, defaultView: View): View? = iconViewOrNull(element)
}

/**
 * A leaf view painting a single [Icon] at its real (zoom-scaled) size, centred on the surrounding
 * text's line the way the log table centres its icon labels (jj-idea-3as8). The line grows to fit an
 * icon taller than the text, split evenly above and below so the text baseline stays put.
 */
internal class IconLeafView(elem: Element, private val icon: Icon) : View(elem) {
    private val styleSheet get() = (document as HTMLDocument).styleSheet
    private val attr by lazy { styleSheet.getViewAttributes(this) }
    private val font by lazy { styleSheet.getFont(attr) }
    private val fontMetrics by lazy {
        container?.getFontMetrics(font) ?: FALLBACK_FONT_METRICS_COMPONENT.getFontMetrics(font)
    }
    private val height by lazy { maxOf(fontMetrics.height, icon.iconHeight) }

    override fun getPreferredSpan(axis: Int): Float = when (axis) {
        X_AXIS -> icon.iconWidth.toFloat()
        Y_AXIS -> height.toFloat()
        else -> throw IllegalArgumentException("Invalid axis: $axis")
    }

    override fun getAlignment(axis: Int): Float =
        if (axis == Y_AXIS) {
            ((height - fontMetrics.height) / 2 + fontMetrics.ascent).toFloat() / height
        } else {
            super.getAlignment(axis)
        }

    override fun paint(g: Graphics, allocation: Shape) {
        val rect = allocation.bounds
        icon.paintIcon(container, g, rect.x, rect.y + (height - icon.iconHeight) / 2)
    }

    override fun modelToView(pos: Int, a: Shape, b: Position.Bias): Shape {
        val r = a.bounds
        if (pos == endOffset) r.x += r.width
        r.width = 0
        return r
    }

    override fun viewToModel(x: Float, y: Float, a: Shape, bias: Array<Position.Bias>): Int {
        val alloc = a as Rectangle
        if (x < alloc.x + alloc.width / 2f) {
            bias[0] = Position.Bias.Forward
            return startOffset
        }
        bias[0] = Position.Bias.Backward
        return endOffset
    }
}
