package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ExtendableHTMLViewFactory
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.io.StringReader
import javax.swing.Icon
import javax.swing.text.*
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit
import kotlin.math.roundToInt

/**
 * Resolves `<img>` elements whose `src` starts with [UNBREAKABLE_PREFIX] into a single atomic
 * [AtomicHtmlView], so a caller's content (icons, links, colored spans, in any combination) is never
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
        val html = UnbreakableContent.decode(src.removePrefix(UNBREAKABLE_PREFIX))
        return AtomicHtmlView(element, html)
    }
}

/**
 * A leaf view hosting its own small, private HTML document for [html] - genuine, arbitrary nested
 * HTML built by the caller's own [TextCanvas] calls, parsed by a standalone, vanilla [HTMLEditorKit]
 * (no JBHtmlPane, no Jsoup) the way [javax.swing.plaf.basic.BasicHTML] parses `<html>` text for
 * `JLabel`/`JButton`. The outer document sees exactly one atomic Element/View (this one).
 *
 * Unlike `BasicHTML`, this does **not** delegate to a full `ParagraphView`/`FlowView` tree -
 * `FlowView` builds its row children lazily during `layout()`, and its `getPreferredSpan(Y_AXIS)`
 * before/around that point depends on incidental layout-pass ordering it can't reliably control from
 * inside someone else's document. Instead: `HTMLDocument.HTMLReader` never creates a branch Element
 * for an inline tag (it flattens `<a>`/`<span>`/etc. onto the leaf's own `AttributeSet` - see
 * [hrefOf]'s doc), so every child of the fragment's implicit `<p>` is already a flat leaf Element (a
 * text run or an `<img>`) - [childViews] builds one ordinary child [View] per leaf directly, and this
 * class lays them out itself in a single unbreakable horizontal row.
 */
internal class AtomicHtmlView(elem: Element, html: String) : View(elem) {
    companion object {
        // IntelliJ 2026.2 renders text adjacent to an <img> element tighter than font-metrics math
        // predicts (jj-idea-vll4/jj-idea-m2wr); this is an empirically-tuned pixel correction for it.
        private const val LEADING_GAP = 2

        private val LOG = Logger.getInstance(AtomicHtmlView::class.java)
    }

    // Seeds the inner document's stylesheet with the ambient font (it otherwise defaults to Swing's
    // built-in serif), mirroring javax.swing.plaf.basic.BasicHTML.BasicDocument.setFontAndColor.
    // Color is applied separately, as an inline `<span style='color: #...'>` around [html] rather
    // than a stylesheet rule: Swing's built-in default.css sets `body { color: black }` on the same
    // selector a stylesheet rule would use, and only an inline style's higher specificity reliably
    // wins that cascade (matches HtmlTextCanvas.colored()/linked(), the rest of this codebase's proven
    // color-application pattern).
    //
    // Deliberately `by lazy`: `container` (needed by [fontMetrics]/[ambientForeground]'s fallback) is
    // only wired up after construction, when the outer ViewFactory calls `setParent` on this view.
    private val innerDoc by lazy {
        val resolvedForeground = ambientForeground ?: UIUtil.getLabelForeground()
        LOG.debug("AtomicHtmlView color: ambientForeground=$ambientForeground, resolved=$resolvedForeground")
        val wrappedHtml = "<span style='color: ${resolvedForeground.toCssHex()}'>$html</span>"
        (AtomicEditorKit.createDefaultDocument() as HTMLDocument).apply {
            styleSheet.addRule(displayPropertiesToCss(ambientFont))
            styleSheet.addRule("p { margin-top: 0; margin-bottom: 0 }") // implicit <p>'s margin can't leak into layout
            runCatching { AtomicEditorKit.read(StringReader(wrappedHtml), this, 0) }
        }
    }

    /** Every leaf [Element] parsed from [html], in document order - guaranteed flat (see class doc). */
    private val leaves: List<Element> by lazy { leafElements(innerDoc.defaultRootElement) }

    private fun leafElements(elem: Element): List<Element> =
        if (elem.isLeaf) listOf(elem) else (0 until elem.elementCount).flatMap { leafElements(elem.getElement(it)) }

    /** One ordinary child [View] per [leaves] element, laid out by this class itself - see class doc. */
    private val childViews: List<View> by lazy {
        leaves.map { AtomicEditorKit.viewFactory.create(it).also { v -> v.setParent(this) } }
    }

    private val ambientFont: Font? get() = (element.document as? HTMLDocument)?.styleSheet?.getFont(element.attributes)

    // Null when the outer <img>'s position has no explicit CSS color (e.g. a plain date chip, not
    // wrapped in colored{}/linked{}) - StyleSheet.getForeground silently falls back to Color.BLACK
    // rather than returning null, so that's treated as "no color" here (no chip color in this app is
    // ever intentionally pure black). The caller falls back to UIUtil.getLabelForeground() instead.
    private val ambientForeground: Color?
        get() = (element.document as? HTMLDocument)?.styleSheet?.getForeground(element.attributes)
            ?.takeUnless { it == Color.BLACK }

    private val fontMetrics
        get() = run {
            val font = ambientFont ?: UIUtil.getLabelFont()
            container?.getFontMetrics(font) ?: FALLBACK_FONT_METRICS_COMPONENT.getFontMetrics(font)
        }

    private val leadingGap: Int
        get() = (LEADING_GAP * JBUIScale.scale(1f)).roundToInt()

    /** Whether [this] carries a hover cue at all - a real link (underline) or a `jjref://` ref (background). */
    private val ancestorHref: String? by lazy { hrefAncestorOf(element) }
    private val isRealLink: Boolean by lazy { ancestorHref?.startsWith("jjref://") == false }
    private val isRefOnly: Boolean by lazy { ancestorHref?.startsWith("jjref://") == true }
    private val isHovered: Boolean
        get() = (isRealLink || isRefOnly) && (container as? IconAwareHtmlPane)?.hoveredChipElement === element

    // Without this, childViews' own children can't be created: the default View.getViewFactory()
    // walks up getParent() to the *outer* document's factory, not AtomicEditorKit's.
    override fun getViewFactory(): ViewFactory = AtomicEditorKit.viewFactory

    /** X_AXIS: sum of every child's width. Y_AXIS: tallest child's height (or one ambient-font line if empty). */
    override fun getPreferredSpan(axis: Int): Float = when (axis) {
        X_AXIS -> leadingGap + childViews.sumOf { it.getPreferredSpan(X_AXIS).toDouble() }.toFloat()
        Y_AXIS -> childViews.maxOfOrNull { it.getPreferredSpan(Y_AXIS) } ?: fontMetrics.height.toFloat()
        else -> throw IllegalArgumentException("Invalid axis: $axis")
    }

    override fun getAlignment(axis: Int): Float =
        if (axis == Y_AXIS) fontMetrics.ascent.toFloat() / fontMetrics.height else super.getAlignment(axis)

    /** [childViews]' own allocations for a row starting at ([originX], [originY]), in [leaves] order. */
    private fun childAllocations(originX: Int, originY: Int): List<Rectangle> {
        val height = getPreferredSpan(Y_AXIS).toInt()
        var x = originX
        return childViews.map { child ->
            val w = child.getPreferredSpan(X_AXIS).toInt()
            Rectangle(x, originY, w, height).also { x += w }
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

        val allocations = childAllocations(startX, rect.y)
        childViews.forEachIndexed { i, child ->
            val childRect = allocations[i]
            child.setSize(childRect.width.toFloat(), childRect.height.toFloat())
            child.paint(g, childRect)
        }
        val endX = allocations.lastOrNull()?.let { it.x + it.width } ?: startX

        if (isHovered && isRealLink) {
            // Underline the whole unit while hovered - this leaf isn't a live <a>, so it gets no
            // native hover-underline of its own (jj-idea-iesq).
            val underlineY = rect.y + getPreferredSpan(Y_AXIS).toInt() - 2
            g.color = SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES.fgColor
            g.drawLine(startX, underlineY, endX, underlineY)
        }

        underlineHoveredIssueLink(g, allocations)
    }

    /** Underline just the inner run matching [IconAwareHtmlPane.hoveredIssueLinkUri], if any (jj-idea-vrmv follow-up). */
    private fun underlineHoveredIssueLink(g: Graphics, allocations: List<Rectangle>) {
        val hovered = hoveredIssueLinkUri() ?: return
        val index = leaves.indexOfFirst { hrefOf(it) == hovered.toString() }
        if (index < 0) return
        val r = allocations[index]
        val underlineY = r.y + r.height - 2
        g.color = SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES.fgColor
        g.drawLine(r.x, underlineY, r.x + r.width, underlineY)
    }

    private fun hoveredIssueLinkUri() = (container as? IconAwareHtmlPane)?.hoveredIssueLinkUri

    /** The href of whichever inner element sits at content-relative pixel [contentX]/[contentY], if any. */
    fun hrefAtContentX(contentX: Int, contentY: Int): String? {
        val allocations = childAllocations(0, 0)
        val index = allocations.indexOfFirst { contentX in it.x until (it.x + it.width) }
        if (index < 0) return null
        return hrefAncestorOf(leaves[index])
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

/**
 * A `body { font-family: ...; font-size: ...pt; ... }` CSS rule for [font], replicating
 * `sun.swing.SwingUtilities2.displayPropertiesToCSS` (an internal JDK API not usable from a plugin)
 * - the same rule [javax.swing.plaf.basic.BasicHTML.BasicDocument] seeds its own private document
 * with, so a standalone [HTMLEditorKit] document (which otherwise defaults to Swing's built-in HTML
 * stylesheet, a serif font) renders with the ambient font instead. Color isn't part of this rule -
 * see [AtomicHtmlView.innerDoc]'s doc for why that's applied as an inline span instead.
 */
internal fun displayPropertiesToCss(font: Font?) = buildString {
    append("body {")
    if (font != null) {
        append(" font-family: ").append(font.family).append(" ; ")
        append(" font-size: ").append(font.size).append("pt ;")
        if (font.isBold) append(" font-weight: 700 ; ")
        if (font.isItalic) append(" font-style: italic ; ")
    }
    append(" }")
}

/** `#rrggbb`, for embedding in an inline `style='color: ...'` attribute. */
private fun Color.toCssHex() = String.format("#%02x%02x%02x", red, green, blue)

/** Font-metrics source for a leaf view not yet attached to a [Component] - `Toolkit.getFontMetrics` is deprecated. */
private val FALLBACK_FONT_METRICS_COMPONENT = Canvas()

/** Marker prefix on an `<img>` element's `src`, resolved to a real icon via [IconResolver] - see [iconViewOrNull]. */
internal const val ICON_IMG_PREFIX = "icon:"

/**
 * Resolves an `<img src='icon:...'/>` element to an [IconLeafView], or null if [elem] isn't one -
 * shared by the outer document ([IconImgExtension]) and [AtomicHtmlView]'s inner document
 * ([AtomicViewFactory]) so both render icons identically.
 */
private fun iconViewOrNull(elem: Element): View? {
    if (elem.name != "img") return null
    val src = elem.attributes.getAttribute(HTML.Attribute.SRC) as? String ?: return null
    if (!src.startsWith(ICON_IMG_PREFIX)) return null
    val icon = IconResolver.resolveIcon(src.removePrefix(ICON_IMG_PREFIX)) ?: return null
    return IconLeafView(elem, ScaleCorrectedIcon(icon))
}

/** [iconViewOrNull] wired into the *outer* [IconAwareHtmlPane] document's extension list. */
internal object IconImgExtension : ExtendableHTMLViewFactory.Extension {
    override fun invoke(element: Element, defaultView: View): View? = iconViewOrNull(element)
}

/**
 * The private [HTMLEditorKit] used to render [AtomicHtmlView]'s inner content - a vanilla kit
 * (not JBHtmlPane's), with [iconViewOrNull] wired in for `<img src='icon:...'/>` elements.
 */
private object AtomicEditorKit : HTMLEditorKit() {
    private val factory = AtomicViewFactory()
    override fun getViewFactory(): ViewFactory = factory
}

private class AtomicViewFactory : HTMLEditorKit.HTMLFactory() {
    override fun create(elem: Element): View = iconViewOrNull(elem) ?: super.create(elem)
}

/** A leaf view painting a single [Icon], bottom-aligned to the surrounding text's descent line. */
internal class IconLeafView(elem: Element, private val icon: Icon) : View(elem) {
    private val styleSheet get() = (document as HTMLDocument).styleSheet
    private val attr by lazy { styleSheet.getViewAttributes(this) }
    private val font by lazy { styleSheet.getFont(attr) }
    private val fontMetrics by lazy {
        container?.getFontMetrics(font) ?: FALLBACK_FONT_METRICS_COMPONENT.getFontMetrics(font)
    }

    override fun getPreferredSpan(axis: Int): Float = when (axis) {
        X_AXIS -> icon.iconWidth.toFloat()
        Y_AXIS -> fontMetrics.height.toFloat()
        else -> throw IllegalArgumentException("Invalid axis: $axis")
    }

    override fun getAlignment(axis: Int): Float =
        if (axis == Y_AXIS) fontMetrics.ascent.toFloat() / fontMetrics.height else super.getAlignment(axis)

    override fun paint(g: Graphics, allocation: Shape) {
        val rect = allocation.bounds
        val baseline = rect.y + fontMetrics.ascent
        icon.paintIcon(null as Component?, g, rect.x, baseline - icon.iconHeight + fontMetrics.descent)
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
