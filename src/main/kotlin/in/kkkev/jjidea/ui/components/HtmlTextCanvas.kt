package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.ColorUtil
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import java.awt.Font
import java.net.URI
import java.net.URLEncoder

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

    override fun append(icon: IconSpec) {
        val src = applyCurrentColor(icon).qualified
        control("<icon src='${if (style.isSmaller) "$src@$SMALLER_SCALE" else src}'/>")
    }

    /**
     * Encode the whole chip into a single `<icon>` element's `src` attribute, resolved by [ChipIconExtension] into
     * one atomic [ChipView]. A plain sequence of `<icon>` + text elements would let the surrounding HTML layout
     * split the icon from its label, or the label across lines, when the row needs to wrap (jj-idea-kds1) — folding
     * everything into a single leaf view makes that impossible.
     *
     * [label] is split into runs via [TextCanvas.linkifier] (jj-idea-vrmv follow-up), the same as
     * [appendLinkified] does for plain text - [ChipView] paints each run individually so a linkified
     * substring within an otherwise-atomic chip still gets its own color/hover cue, without breaking
     * the wrap/word-split guarantee this atomic encoding exists for.
     */
    override fun appendChip(
        icon: IconSpec,
        label: String,
        prefixIcon: IconSpec?,
        strikethrough: Boolean,
        suffix: String?,
        suffixColor: Color?
    ) {
        fun key(spec: IconSpec): String {
            val src = applyCurrentColor(spec).qualified
            return if (style.isSmaller) "$src@$SMALLER_SCALE" else src
        }
        appendChipHtml(
            key(icon),
            prefixIcon?.let(::key) ?: "",
            linkifier.linkify(label),
            strikethrough,
            suffix,
            suffixColor
        )
    }

    /** Same atomic-leaf mechanism as [appendChip], but with no icon — just a plain unbreakable text run. */
    override fun appendUnbreakable(text: String) =
        appendChipHtml(
            "",
            "",
            listOf(TextRun.Plain(text)),
            strikethrough = false,
            suffix = null,
            suffixColor = null
        )

    private fun appendChipHtml(
        iconKey: String,
        prefixIconKey: String,
        label: List<TextRun>,
        strikethrough: Boolean,
        suffix: String?,
        suffixColor: Color?
    ) {
        // `~`/`,` are safe run/field delimiters: URLEncoder always escapes both, so neither can
        // appear literally in an encoded run's text or URI.
        val encodedLabel = label.joinToString(",") { run ->
            val encodedUri = run.target?.let { URLEncoder.encode(it.toString(), "UTF-8") } ?: ""
            "${URLEncoder.encode(run.text, "UTF-8")}~$encodedUri"
        }
        val encodedSuffix = suffix?.let { URLEncoder.encode(it, "UTF-8") } ?: ""
        val suffixColorHex = suffixColor?.let { ColorUtil.toHex(it) } ?: ""
        val encoded = listOf(
            iconKey,
            prefixIconKey,
            encodedLabel,
            if (strikethrough) "1" else "0",
            encodedSuffix,
            suffixColorHex
        ).joinToString(";")
        // A genuinely void/self-closing element (unlike <icon>, jj-idea-vll4/jj-idea-m2wr): JBHtmlPane's Jsoup
        // transpiler marks the custom <icon> tag SelfClose but not Void, so on IntelliJ 2026.2 it round-trips
        // through Jsoup's serializer as an explicit <icon ...></icon> pair, which Swing's parser (not knowing
        // <icon> is empty) then splits into two sibling Elements per chip instead of one. <img> is a real HTML
        // void element that both Jsoup and Swing's parser already know never has a closing tag, so it survives
        // the round-trip as a single Element. ChipIconExtension intercepts it before any real image loading.
        control("<img src='$CHIP_ICON_PREFIX$encoded'/>")
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
