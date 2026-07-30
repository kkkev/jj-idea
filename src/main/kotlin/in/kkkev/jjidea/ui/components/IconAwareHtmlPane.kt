package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.io.URLUtil
import com.intellij.util.ui.ExtendableHTMLViewFactory
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.common.ScaledIcon
import `in`.kkkev.jjidea.ui.common.accented
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import java.awt.Component
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.Shape
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.net.URLDecoder
import java.util.regex.Pattern
import javax.swing.Icon
import javax.swing.event.HyperlinkEvent
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.Element
import javax.swing.text.Position
import javax.swing.text.View
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.reflect.KClass

private val CHANGE_ID_URL_PARSER = Pattern.compile("^jjc://([^?]+)\\?(.+)$")
private val REF_URL_PARSER = Pattern.compile("^jjref://([^?]+)\\?([^&]+)&kind=([^&]+)&name=(.+)$")

/** Marker prefix on an `<icon>` element's `src`, recognized by [ChipIconExtension], identifying a [TextCanvas.appendChip]. */
internal const val CHIP_ICON_PREFIX = "chip:"

/**
 * An HTML pane that can resolve icons from a set of icon libraries, including IDEA's icons
 * [com.intellij.icons.AllIcons].
 *
 * Navigates on `jjc://` (change ID) and `jjref://` (bookmark/tag) hyperlinks.
 */
class IconAwareHtmlPane(private val project: Project) : JBHtmlPane(
    JBHtmlPaneStyleConfiguration(),
    JBHtmlPaneConfiguration {
        iconResolver = { IconResolver.resolveIcon(it)?.let(::HtmlIcon) }
        extensions(ChipIconExtension)
    }
) {
    /**
     * The chip `<img>` [Element] currently under the pointer, if it's inside a link (jj-idea-iesq)
     * - read by [ChipView.paint] to underline only that one chip while hovered, matching the
     * "colored always, underlined on hover" convention already applied to real (non-chip) links
     * here via native `<a>` hover rendering, and to the log table via `JujutsuLogTable
     * .hoveredLinkRow`/`hoveredLinkCol`.
     */
    internal var hoveredChipElement: Element? = null
        private set

    init {
        isOpaque = false
        addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val newHovered = linkedChipElementAt(e.point)
                    if (newHovered !== hoveredChipElement) {
                        hoveredChipElement = newHovered
                        // Chips are small and this pane's content is short (commit metadata), so a
                        // full repaint on hover change is cheap - no need to compute exact chip
                        // bounds (ChipView.modelToView reports a zero-width point, not its real
                        // painted extent, so precise invalidation isn't straightforward here).
                        repaint()
                    }
                }
            }
        )
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val url = e.description
                if (url != null) {
                    with(CHANGE_ID_URL_PARSER.matcher(url)) {
                        if (matches()) {
                            val path = URLUtil.unescapePercentSequences(group(1))
                            val repo = project.jujutsuRepositoryFor(VcsUtil.getFilePath(path, true))
                            project.stateModel.changeSelection.notify(ChangeKey(repo, ChangeId(group(2))))
                            return@addHyperlinkListener
                        }
                    }
                    with(REF_URL_PARSER.matcher(url)) {
                        if (matches()) {
                            val path = URLUtil.unescapePercentSequences(group(1))
                            val repo = project.jujutsuRepositoryFor(VcsUtil.getFilePath(path, true))
                            project.stateModel.changeSelection.notify(ChangeKey(repo, ChangeId(group(2))))
                            return@addHyperlinkListener
                        }
                    }
                }
                // Not one of our internal jjc:/jjref: schemes (e.g. an issue-tracker link, jj-idea-10fo, or a
                // mailto: author link) — hand off to the platform's standard browser/mail handler.
                BrowserHyperlinkListener.INSTANCE.hyperlinkUpdate(e)
            }
        }
    }

    /** Parse a `jjref://` href from the HTML element under [point], or null if not a ref link. */
    fun refUriAt(point: java.awt.Point): java.net.URI? {
        val offset = viewToModel2D(point).toInt()
        val doc = document as? HTMLDocument ?: return null
        var elem: Element? = doc.getCharacterElement(offset)
        while (elem != null) {
            val href = hrefOf(elem)
            if (href != null && REF_URL_PARSER.matcher(href).matches()) {
                return runCatching { java.net.URI(href) }.getOrNull()
            }
            elem = elem.parentElement
        }
        return null
    }

    /** The chip `<img>` element at [point], if any, and only if it's inside a link (jj-idea-iesq). */
    private fun linkedChipElementAt(point: java.awt.Point): Element? {
        val offset = viewToModel2D(point).toInt()
        val doc = document as? HTMLDocument ?: return null
        val elem = doc.getCharacterElement(offset)
        if (elem.name != "img") return null
        val src = elem.attributes.getAttribute(HTML.Attribute.SRC) as? String ?: return null
        if (!src.startsWith(CHIP_ICON_PREFIX)) return null
        return if (hasHrefAncestor(elem)) elem else null
    }
}

/**
 * The `href` on [element] itself, if any (jj-idea-iesq).
 *
 * Character-level tags like `<a>` are flattened by Swing's HTML parser onto the wrapped leaf's own
 * `AttributeSet` as a *nested* `AttributeSet` keyed by [HTML.Tag.A] - unlike block-level tags,
 * they're never represented as separate ancestor `Element`s to find `HTML.Attribute.HREF` on
 * directly. This checks both the (rare) direct case and the actual nested-under-`<a>` case.
 */
private fun hrefOf(element: Element): String? {
    val attrs = element.attributes
    (attrs.getAttribute(HTML.Attribute.HREF) as? String)?.let { return it }
    val anchorAttrs = attrs.getAttribute(HTML.Tag.A) as? AttributeSet
    return anchorAttrs?.getAttribute(HTML.Attribute.HREF) as? String
}

/** True if [element] or any of its ancestors carries an `href` attribute (jj-idea-iesq). */
private fun hasHrefAncestor(element: Element): Boolean {
    var elem: Element? = element
    while (elem != null) {
        if (hrefOf(elem) != null) return true
        elem = elem.parentElement
    }
    return false
}

object IconResolver {
    val icons = listOf(JujutsuIcons::class, AllIcons::class)
        .flatMap { it.allIcons.toList() }
        .toMap()

    fun resolveIcon(key: String): Icon? {
        val scaleParts = key.split("@", limit = 2)
        val scale = scaleParts.getOrNull(1)?.toFloatOrNull()
        val colorParts = scaleParts[0].split("#", limit = 2)
        val baseIcon = icons[colorParts[0]] ?: return null
        val colored = colorParts.getOrNull(1)?.let { baseIcon.accented(ColorUtil.fromHex(it)) } ?: baseIcon
        return if (scale != null) ScaledIcon(colored, scale) else colored
    }

    private val KClass<*>.allIcons
        get() = java
            .nestMembers
            .flatMap { it.fields.toList() }
            .filter { it.type.isAssignableFrom(Icon::class.java) }
            .associate {
                "${it.declaringClass.name.replace(qualifiedName!!, simpleName!!).replace('$', '.')}.${it.name}" to
                    (it.get(it.declaringClass) as Icon)
            }
}

/**
 * Wraps icons for JBHtmlPane to fix High-DPI "Double Scaling" and baseline alignment.
 */
private class HtmlIcon(private val source: Icon) : Icon {
    // Report 0 height. This is the only way to stop JBIconView from adding whitespace above text lines with icons.
    override fun getIconWidth() = (source.iconWidth / JBUIScale.scale(1f)).roundToInt()
    override fun getIconHeight() = 0

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        source.paintIcon(c, g, x, y - source.iconHeight + g.fontMetrics.descent)
    }
}

/**
 * Corrects High-DPI "Double Scaling" like [HtmlIcon], but reports its real height. Used by [ChipView], which (unlike
 * plain `<icon>` elements rendered by IntelliJ's built-in `JBIconView`) positions icons itself against real font
 * metrics rather than relying on the zero-height/row-alignment trick [HtmlIcon] exists for.
 */
private class ScaleCorrectedIcon(private val source: Icon) : Icon {
    override fun getIconWidth() = (source.iconWidth / JBUIScale.scale(1f)).roundToInt()
    override fun getIconHeight() = (source.iconHeight / JBUIScale.scale(1f)).roundToInt()
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) = source.paintIcon(c, g, x, y)
}

/**
 * Resolves `<img>` elements whose `src` starts with [CHIP_ICON_PREFIX] into a single atomic [ChipView], so that an
 * icon is never separated from its label, nor a label split mid-word, by HTML line wrapping (jj-idea-kds1).
 *
 * `<img>` (rather than `<icon>`) is used for chips specifically because it's a genuine HTML void element: on
 * IntelliJ 2026.2, JBHtmlPane's Jsoup transpiler round-trips a self-closed `<icon .../>` into an explicit
 * `<icon ...></icon>` open/close pair (it marks the custom `<icon>` tag `SelfClose` but not `Void`), which Swing's
 * parser then turns into two sibling Elements instead of one, breaking the atomic-chip invariant this class exists
 * for (jj-idea-vll4, jj-idea-m2wr). `<img>` is already void to both Jsoup and Swing's parser, so it always survives
 * as a single Element; this extension intercepts it (running before any built-in image-loading extension) so no
 * real image is ever fetched or rendered for it. Non-chip elements (real `<img>` or plain `<icon>`) fall through to
 * IntelliJ's built-in rendering by returning `null`.
 */
internal object ChipIconExtension : ExtendableHTMLViewFactory.Extension {
    override fun invoke(element: Element, defaultView: View): View? {
        if (element.name != "img") return null
        val src = element.attributes.getAttribute(HTML.Attribute.SRC) as? String ?: return null
        if (!src.startsWith(CHIP_ICON_PREFIX)) return null
        val spec = ChipSpec.parse(src.removePrefix(CHIP_ICON_PREFIX)) ?: return null
        return ChipView(element, spec)
    }
}

/**
 * Parsed contents of a [TextCanvas.appendChip] (or [TextCanvas.appendUnbreakable]) call, encoded by `HtmlTextCanvas`
 * into a single `src` attribute. [icon] is null for a plain unbreakable text label with no icon.
 */
private class ChipSpec(
    val icon: Icon?,
    val prefixIcon: Icon?,
    val label: String,
    val strikethrough: Boolean,
    val suffix: String?,
    val suffixColor: java.awt.Color?
) {
    companion object {
        fun parse(encoded: String): ChipSpec? {
            val parts = encoded.split(";")
            if (parts.size != 6) return null
            val icon = parts[0].takeIf { it.isNotEmpty() }?.let { resolveChipIcon(it) ?: return null }
            val prefixIcon = parts[1].takeIf { it.isNotEmpty() }?.let(::resolveChipIcon)
            val label = URLDecoder.decode(parts[2], "UTF-8")
            val strikethrough = parts[3] == "1"
            val suffix = parts[4].takeIf { it.isNotEmpty() }?.let { URLDecoder.decode(it, "UTF-8") }
            val suffixColor = parts[5].takeIf { it.isNotEmpty() }?.let { ColorUtil.fromHex(it) }
            return ChipSpec(icon, prefixIcon, label, strikethrough, suffix, suffixColor)
        }

        private fun resolveChipIcon(key: String): Icon? = IconResolver.resolveIcon(key)?.let(::ScaleCorrectedIcon)
    }
}

/**
 * A leaf view painting an optional icon (itself optionally preceded by a second "prefix" icon, e.g. a conflict
 * marker), immediately followed by a text label and an optional colored suffix, as a single unbreakable unit. Unlike
 * a plain `<icon>` followed by separate text elements, there is no view boundary between the icon and the label for
 * the surrounding flow layout to break at (jj-idea-kds1). With no icon at all, this is a plain unbreakable text run
 * (used by [TextCanvas.appendUnbreakable] for short strings, like a date/time, that must never split mid-word).
 *
 * Font and foreground color are resolved from the element's CSS attributes (the same `colored`/`smaller` ancestor
 * spans that would otherwise wrap separate icon/text elements), so chips still inherit ambient styling correctly.
 */
private class ChipView(elem: Element, private val spec: ChipSpec) : View(elem) {
    companion object {
        // Extra fixed-pixel gap painted before every chip's icon/label, on top of whatever
        // ordinary text (e.g. a space escaped to non-collapsing U+00A0 by append()) already
        // precedes it. A bare U+00A0 between two chips measures mathematically exact against
        // fontMetrics (matches how IntelliJ 2026.1 renders the same content pixel-for-pixel), but
        // on 2026.2 specifically it reads as visually tighter than that -- a residual, milder
        // cousin of the same "text rendered adjacent to an <img> element behaves oddly" class of
        // platform bug this whole chip mechanism already works around once (jj-idea-vll4,
        // jj-idea-m2wr: <icon> vs <img> round-tripping through Jsoup). Applied unconditionally
        // (not keyed to any specific chip's content) since the underlying rendering difference is
        // about the *gap*, not about what any particular chip says -- every chip type (bookmark,
        // tag, name+email, date) can appear adjacent to another one this way. Tuning this via
        // additional Unicode space characters in the *surrounding text* (e.g. a second U+00A0, or
        // narrower codepoints like U+2009) doesn't give fine-enough control, since they either
        // match a full space's width or (in fonts without a distinct narrow-space glyph, like
        // Inter here) fall back to it anyway; this gives genuine sub-glyph pixel control instead,
        // tuned empirically against real 2026.2 rendering.
        private const val CHIP_LEADING_GAP = 2
    }

    private val styleSheet get() = (document as HTMLDocument).styleSheet
    private val attr: AttributeSet by lazy { styleSheet.getViewAttributes(this) }
    private val font by lazy { styleSheet.getFont(attr) }
    private val foreground by lazy { styleSheet.getForeground(attr) }
    private val fontMetrics by lazy {
        container?.getFontMetrics(font) ?: Toolkit.getDefaultToolkit().getFontMetrics(font)
    }

    // Underline the label only while hovered (jj-idea-iesq), matching the "colored always,
    // underlined on hover" convention used for real (non-chip) links and the log table. isLinked
    // is fixed for this element's lifetime (its href-ness doesn't change), so only re-derived
    // lazily once; hovered is re-read on every paint since IconAwareHtmlPane repaints on change.
    private val isLinked: Boolean by lazy { hasHrefAncestor(element) }
    private val isHovered: Boolean get() = isLinked && (container as? IconAwareHtmlPane)?.hoveredChipElement === element

    private val iconsWidth get() = (spec.prefixIcon?.iconWidth ?: 0) + (spec.icon?.iconWidth ?: 0)
    private val leadingGap: Int
        get() = (CHIP_LEADING_GAP * JBUIScale.scale(1f)).roundToInt()

    override fun getPreferredSpan(axis: Int): Float {
        val fm = fontMetrics
        return when (axis) {
            X_AXIS -> (leadingGap + iconsWidth + fm.stringWidth(spec.label) + fm.stringWidth(spec.suffix ?: ""))
                .toFloat()
            Y_AXIS -> max(fm.height, max(spec.icon?.iconHeight ?: 0, spec.prefixIcon?.iconHeight ?: 0)).toFloat()
            else -> throw IllegalArgumentException("Invalid axis: $axis")
        }
    }

    override fun getAlignment(axis: Int): Float =
        if (axis == Y_AXIS) fontMetrics.ascent.toFloat() / fontMetrics.height else super.getAlignment(axis)

    override fun paint(g: Graphics, allocation: Shape) {
        val rect = allocation.bounds
        val fm = fontMetrics
        val baseline = rect.y + fm.ascent
        var x = rect.x + leadingGap

        // Bottom-align each icon to the text's descent line (baseline + descent), matching
        // HtmlIcon's "- iconHeight + descent" convention for plain <icon> tags (jj-idea-fmrj) --
        // aligning to the baseline itself (dropping the descent term) makes the icon float
        // visibly above the text's vertical center instead of sitting level with it.
        spec.prefixIcon?.let { icon ->
            icon.paintIcon(null, g, x, baseline - icon.iconHeight + fm.descent)
            x += icon.iconWidth
        }
        spec.icon?.let { icon ->
            icon.paintIcon(null, g, x, baseline - icon.iconHeight + fm.descent)
            x += icon.iconWidth
        }

        g.font = font
        g.color = foreground
        g.drawString(spec.label, x, baseline)
        if (spec.strikethrough) {
            val lineY = baseline - fm.ascent / 3
            g.drawLine(x, lineY, x + fm.stringWidth(spec.label), lineY)
        }
        if (isHovered) {
            // Underline only the label (not any suffix) while hovered, matching a plain <a>'s
            // native hover-underline extent (jj-idea-iesq).
            val underlineY = baseline + (fm.descent / 2).coerceAtLeast(1)
            g.drawLine(x, underlineY, x + fm.stringWidth(spec.label), underlineY)
        }
        x += fm.stringWidth(spec.label)

        spec.suffix?.let { suffix ->
            g.color = spec.suffixColor ?: foreground
            g.drawString(suffix, x, baseline)
        }
    }

    override fun modelToView(pos: Int, a: Shape, b: Position.Bias): Shape {
        if (pos !in startOffset..endOffset) {
            throw BadLocationException("$pos not in range $startOffset,$endOffset", pos)
        }
        val r = a.bounds
        when (pos) {
            // a.bounds.x is the left edge of the full allocation, which includes leadingGap's blank
            // space before any visible content is actually painted -- shift past it so callers (e.g.
            // click hit-testing, or a test measuring the visible gap before this chip) see where the
            // chip's content really starts, matching paint()'s own starting x.
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
