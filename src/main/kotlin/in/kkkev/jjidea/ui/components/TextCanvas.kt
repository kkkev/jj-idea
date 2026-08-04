package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import java.awt.Font
import java.net.URI

/**
 * A canvas onto which to paint styled text and icons. Functions provide a DSL that applies styling to embedded content,
 * allowing text to be declared in styled sections, e.g.
 * ```
 * bold {
 *     append("Here is some bold text ")
 *     italic { append("and this is bold and italic ") }
 *     append("back to just bold")
 * }
 * ```
 *
 * This file holds the generic DSL only (the interface, its [StyledTextCanvas] base, and
 * [appendLinkified]); the jj-domain vocabulary built on top of it (`append(Bookmark)`,
 * `appendSummaryAndStatuses`, etc.) lives in `LogEntryText.kt`.
 */
interface TextCanvas {
    /**
     * Append the specified text to the canvas. This is intended specifically for displaying that text; therefore text
     * applied here is escaped before adding to HTML, for example.
     */
    fun append(text: String)

    /**
     * Apply context-specific controls to the canvas. For HTML, this is equivalent to surrounding with an HTML tag.
     * Strings are applied to the stream verbatim; crucially, they are not escaped, so this is the way to provide
     * further control on the HTML output.
     */
    fun control(open: String, close: String = "", builder: TextCanvas.() -> Unit = {}) = builder()

    /**
     * Apply the specified font-styling to content in the provided builder. Font-styling is additive; for example, if
     * the canvas is already italic, and bold is applied, then content is both bold and italic.
     */
    fun styled(style: Int, builder: TextCanvas.() -> Unit)
    fun bold(builder: TextCanvas.() -> Unit) = styled(Font.BOLD, builder)
    fun italic(builder: TextCanvas.() -> Unit) = styled(Font.ITALIC, builder)

    fun smaller(builder: TextCanvas.() -> Unit) = styled(SimpleTextAttributes.STYLE_SMALLER, builder)
    fun strikethrough(builder: TextCanvas.() -> Unit) = styled(SimpleTextAttributes.STYLE_STRIKEOUT, builder)

    /**
     * Underline content, e.g. to show a [linked] fragment as hovered (jj-idea-iesq): links are
     * colored via [linked] but otherwise plain, underlining only while the pointer is actually
     * over them (see [in.kkkev.jjidea.ui.log.UserCellRenderer]) — the HTML details pane gets the
     * same hover-underline behavior for free from the platform's native `<a>` rendering instead.
     */
    fun underlined(builder: TextCanvas.() -> Unit) = styled(SimpleTextAttributes.STYLE_UNDERLINE, builder)

    fun colored(color: Color, builder: TextCanvas.() -> Unit)
    fun grey(builder: TextCanvas.() -> Unit) = colored(JBColor.GRAY, builder)

    fun linked(target: URI, builder: TextCanvas.() -> Unit)

    /**
     * The [Linkifier] used by [appendLinkified] to linkify a description or a bookmark/tag chip's
     * own name (jj-idea-91qf, jj-idea-vrmv), injected once per canvas at construction time instead
     * of threaded through every append call along the way. Defaults to [Linkifier.None].
     */
    val linkifier: Linkifier get() = Linkifier.None

    fun append(icon: IconSpec) = control("<icon src='${icon.qualified}'/>")

    fun truncate(builder: TextCanvas.() -> Unit) = builder()

    /**
     * Append [text] as a single unbreakable unit — the surrounding layout may still wrap before or after it, but
     * never split it mid-word. Use for short strings (e.g. a date/time) that read badly if broken across lines.
     */
    fun appendUnbreakable(text: String) = appendUnbreakable { append(text) }

    /**
     * Append arbitrary content built by [builder] as a single unbreakable unit — the surrounding layout may still
     * wrap before or after it, but never split anything inside it apart (jj-idea-kds1), e.g. so a bookmark/tag
     * chip's icon is never separated from its name. [builder] can write any combination of ordinary `TextCanvas`
     * calls (icons, links, colors, `strikethrough`, in any order) - there's no fixed icon/label/suffix schema to
     * work around; a bookmark chip, for instance, is just `appendUnbreakable { append(icon); appendLinkified
     * (label) }` at its call site, same as any other unbreakable content.
     */
    fun appendUnbreakable(builder: TextCanvas.() -> Unit) = builder()
}

abstract class StyledTextCanvas : TextCanvas {
    var style: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES
        private set

    /** Color that propagates to icons. Set only by [colored], not by [foreground]. */
    private var iconColor: Color? = null

    protected fun surround(builder: TextCanvas.() -> Unit, deriver: SimpleTextAttributes.() -> SimpleTextAttributes) {
        val oldStyle = style
        style = style.deriver()
        this.builder()
        style = oldStyle
    }

    override fun styled(style: Int, builder: TextCanvas.() -> Unit) =
        surround(builder) { derive(this.style or style, null, null, null) }

    override fun colored(color: Color, builder: TextCanvas.() -> Unit) {
        val oldIconColor = iconColor
        iconColor = color
        surround(builder) { derive(this.style, color, null, null) }
        iconColor = oldIconColor
    }

    /**
     * Set the text foreground color without propagating to icons.
     *
     * Use this for layout-level foreground (e.g., table selection state) where
     * icons should keep their own semantic colors. Use [colored] when the color
     * is semantically meaningful and should apply to both text and icons.
     */
    open fun foreground(color: Color, builder: TextCanvas.() -> Unit) =
        surround(builder) { derive(this.style, color, null, null) }

    // Plain (no underline) by default - underline is reserved for hover, applied by wrapping in
    // underlined{} at the actual point of hover (see UserCellRenderer, jj-idea-iesq), matching the
    // idiomatic SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES/LINK_ATTRIBUTES split IntelliJ itself
    // uses elsewhere (e.g. VcsLogGraphTable's empty-state links).
    override fun linked(target: URI, builder: TextCanvas.() -> Unit) =
        surround(builder) { SimpleTextAttributes.merge(this, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) }

    /** Apply the current [iconColor] to an icon that lacks an explicit [IconSpec.fillColor]. */
    protected fun applyCurrentColor(icon: IconSpec) =
        if (icon.fillColor == null && iconColor != null) icon.copy(fillColor = iconColor) else icon

    override fun append(icon: IconSpec) = super.append(applyCurrentColor(icon))
}

/**
 * Append [text], linkifying via [TextCanvas.linkifier] and [TextCanvas.linked] (rather than raw HTML),
 * so links carry over into any backend — including [FragmentRecordingCanvas], where the target becomes
 * a [FragmentRecordingCanvas.Fragment.linkTarget] usable for hit-testing (jj-idea-iesq) — not just the
 * HTML details pane. Hover-underline (jj-idea-91qf) is applied afterwards by [underlining], not here -
 * see its doc for why.
 */
internal fun TextCanvas.appendLinkified(text: String) {
    for (run in linkifier.linkify(text)) {
        when (run) {
            is TextRun.Plain -> append(run.text)
            is TextRun.Link -> linked(run.target) { append(run.text) }
        }
    }
}
