package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import java.awt.Font
import java.net.URI

/**
 * Create a full HTML document including wrapping `<html>` tag. [linkifier] linkifies any
 * [appendLinkified]-based content (e.g. a description) for the whole document - injected once here
 * rather than threaded through every append call (jj-idea-91qf).
 */
fun htmlString(linkifier: Linkifier = Linkifier.None, builder: (TextCanvas.() -> Unit)) =
    htmlText(linkifier) { control("<html>", "</html>", builder) }

/**
 * Create some inline HTML text, to be used inside an existing HTML document.
 */
private fun htmlText(linkifier: Linkifier = Linkifier.None, builder: (TextCanvas.() -> Unit)) =
    HtmlTextCanvas(StringBuilder(), linkifier).apply(builder).sb.toString()

private class HtmlTextCanvas(
    val sb: StringBuilder,
    override val linkifier: Linkifier = Linkifier.None
) : StyledTextCanvas() {
    override fun control(open: String, close: String, builder: TextCanvas.() -> Unit) {
        sb.append(open)
        builder()
        sb.append(close)
    }

    override fun append(text: String) {
        sb.append(Formatters.escapeHtml(text))
    }

    // A plain space next to a chip/icon's <img> element collapses to near-zero in Swing's HTML
    // renderer, so a deliberate gap needs a non-breaking space instead - but NOT folded into an
    // appendUnbreakable() atomic unit, which would remove the only wrap point between two chips
    // (tried and reverted for exactly that reason - see bb180a0a7). A plain, breakable &nbsp; is
    // both non-collapsing and still a valid line-wrap boundary against the adjacent <img>.
    override fun space() = control("&nbsp;")

    override fun append(icon: IconSpec) {
        val src = applyCurrentColor(icon).qualified
        control("<img src='$ICON_IMG_PREFIX${if (style.isSmaller) "$src@$SMALLER_SCALE" else src}'/>")
    }

    /**
     * Build [html] with [builder] (inheriting the current style/linkifier context, since it runs
     * on `this` canvas exactly as any other nested call would) and re-emit whatever it wrote as a
     * single atomic unit, resolved by `AtomicHtmlExtension` into an [AtomicHtmlView]. A plain
     * sequence of elements would let the surrounding HTML layout split them apart when the row
     * needs to wrap (jj-idea-kds1) — folding everything into one leaf view makes that impossible.
     * [builder] can write *any* combination of ordinary `TextCanvas` calls, including icons
     * (see [append]) - nothing here is chip-specific, and nothing needs rewriting: [append] already
     * emits the same `<img src='icon:...'/>` form [AtomicHtmlView]'s inner document resolves
     * directly, so the captured [html] is used as-is.
     */
    override fun appendUnbreakable(builder: TextCanvas.() -> Unit) {
        val start = sb.length
        this.builder()
        val html = sb.substring(start)
        sb.setLength(start)
        // A genuinely void/self-closing element (unlike <icon>, jj-idea-vll4/jj-idea-m2wr): JBHtmlPane's Jsoup
        // transpiler marks the custom <icon> tag SelfClose but not Void, so on IntelliJ 2026.2 it round-trips
        // through Jsoup's serializer as an explicit <icon ...></icon> pair, which Swing's parser then splits into
        // two sibling Elements instead of one. <img> is a real HTML void element, so it survives as a single
        // Element; `AtomicHtmlExtension` intercepts it before any real image loading.
        control("<img src='$UNBREAKABLE_PREFIX${UnbreakableContent.encode(html)}'/>")
    }

    // TODO Optimise nested styles so that they collapse into one if they start and end at the same point
    override fun styled(style: Int, builder: TextCanvas.() -> Unit) {
        val bold = (style and Font.BOLD) != 0
        val italic = (style and Font.ITALIC) != 0
        val smaller = (style and SimpleTextAttributes.STYLE_SMALLER) != 0
        val strikeout = (style and SimpleTextAttributes.STYLE_STRIKEOUT) != 0

        if (bold) sb.append("<b>")
        if (italic) sb.append("<i>")
        if (smaller) sb.append("<span style='font-size: 85%'>")
        if (strikeout) sb.append("<s>")
        super.styled(style, builder)
        if (strikeout) sb.append("</s>")
        if (smaller) sb.append("</span>")
        if (italic) sb.append("</i>")
        if (bold) sb.append("</b>")
    }

    override fun colored(color: Color, builder: TextCanvas.() -> Unit) {
        val needsSpan = color != style.fgColor
        if (needsSpan) sb.append("<span style='color: ${color.rgbString}'>")
        super.colored(color, builder)
        if (needsSpan) sb.append("</span>")
    }

    override fun foreground(color: Color, builder: TextCanvas.() -> Unit) {
        val needsSpan = color != style.fgColor
        if (needsSpan) sb.append("<span style='color: ${color.rgbString}'>")
        super.foreground(color, builder)
        if (needsSpan) sb.append("</span>")
    }

    override fun linked(target: URI, builder: TextCanvas.() -> Unit) {
        // Link color, mirroring the Swing backend's StyledTextCanvas.linked() (which merges
        // SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) - the HTML backend never consumes `style`
        // for actual rendering (see colored()/foreground()), so it needs its own explicit color
        // span here. A nested colored()/foreground() call (e.g. a bookmark chip's own accent
        // color) still wins, since its span nests inside this one (jj-idea-iesq).
        val linkColor = SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES.fgColor
        val needsSpan = linkColor != style.fgColor
        sb.append("<a href='$target'>")
        if (needsSpan) sb.append("<span style='color: ${linkColor.rgbString}'>")
        super.linked(target, builder)
        if (needsSpan) sb.append("</span>")
        sb.append("</a>")
    }

    private val Color.rgbString get() = "rgb($red,$green,$blue);"
}
