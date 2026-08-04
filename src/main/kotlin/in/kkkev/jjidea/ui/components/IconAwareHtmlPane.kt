package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.scale.JBUIScale
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.common.ScaledIcon
import `in`.kkkev.jjidea.ui.common.accented
import `in`.kkkev.jjidea.ui.log.LogClickTarget
import `in`.kkkev.jjidea.ui.log.performDefaultAction
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.net.URI
import java.util.regex.Pattern
import javax.swing.Icon
import javax.swing.event.HyperlinkEvent
import javax.swing.text.AttributeSet
import javax.swing.text.Element
import javax.swing.text.Position
import javax.swing.text.View
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument
import kotlin.math.roundToInt
import kotlin.reflect.KClass

private val REF_URL_PARSER = Pattern.compile("^jjref://([^?]+)\\?([^&]+)&kind=([^&]+)&name=(.+)$")

/**
 * An HTML pane that can resolve icons from a set of icon libraries, including IDEA's icons
 * [com.intellij.icons.AllIcons].
 *
 * Navigates on `jjc://` (change ID) hyperlinks. `jjref://` (bookmark/tag) links are resolvable for
 * the right-click context menu ([refUriAt]) but have no left-click action and no hover cue - a
 * bookmark/tag chip's only interactive affordance is the right-click menu (jj-idea-wkcz).
 */
class IconAwareHtmlPane(private val project: Project) : JBHtmlPane(
    JBHtmlPaneStyleConfiguration(),
    JBHtmlPaneConfiguration {
        extensions(AtomicHtmlExtension, IconImgExtension)
    }
) {
    /**
     * The atomic-content `<img>` [Element] currently under the pointer, if it's inside a link
     * (jj-idea-iesq) - read by [AtomicHtmlView.paint] to underline/highlight only that one unit
     * while hovered, matching the "colored always, underlined on hover" convention already applied
     * to real (non-chip) links here via native `<a>` hover rendering, and to the log table via
     * `JujutsuLogTable.hoveredLinkRow`/`hoveredLinkCol`.
     */
    internal var hoveredChipElement: Element? = null
        private set

    /**
     * The URI of the linkified issue-tracker reference inside a bookmark/tag chip's own name
     * currently under the pointer, if any (jj-idea-vrmv follow-up) - read by
     * [AtomicHtmlView.paint] to underline just that inner run, and to gate the whole-chip
     * ref-only background highlight off while it's non-null (the two cues are mutually exclusive).
     */
    internal var hoveredIssueLinkUri: URI? = null
        private set

    init {
        isOpaque = false
        addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val newHovered = linkedChipElementAt(e.point)
                    val newIssueLinkUri = issueLinkUriAt(e.point)
                    if (newHovered !== hoveredChipElement || newIssueLinkUri != hoveredIssueLinkUri) {
                        hoveredChipElement = newHovered
                        hoveredIssueLinkUri = newIssueLinkUri
                        // Chips are small and this pane's content is short (commit metadata), so a
                        // full repaint on hover change is cheap - no need to compute exact chip
                        // bounds (AtomicHtmlView.modelToView reports a zero-width point, not its real
                        // painted extent, so precise invalidation isn't straightforward here).
                        repaint()
                    }
                    // Bookmark/tag chips have no left-click action (jj-idea-wkcz), so suppress the
                    // hand cursor Swing's built-in HTMLEditorKit.LinkController shows for any <a>
                    // element (it registers its own mouseMoved during editor-kit setup, before this
                    // listener is added in this class's init block, so overriding the cursor here -
                    // after it runs for the same event - wins) - unless hovering a linkified
                    // issue-tracker substring within the chip, which does have a left-click action;
                    // LinkController's hand cursor already applies there (still inside the chip's
                    // own <a> anchor), so there's nothing further to override.
                    if (refUriAt(e.point) != null && newIssueLinkUri == null) cursor = Cursor.getDefaultCursor()
                }
            }
        )
        // Left-click on a linkified issue-tracker substring inside a bookmark/tag chip's own name
        // (jj-idea-vrmv follow-up). This can't be handled by the addHyperlinkListener below:
        // Swing's hyperlink activation always reports the *whole* anchor's href (the chip's own
        // jjref://, a no-op) regardless of which pixel inside it was clicked, so distinguishing
        // "clicked the inner substring" needs our own pixel-level hit-test instead. Both listeners
        // fire for the same click; the jjref:// activation below still resolves to its usual no-op.
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                    val uri = issueLinkUriAt(e.point) ?: return
                    BrowserUtil.browse(uri)
                    e.consume()
                }
            }
        )
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                // This pane has no LogEntry list to look a jjref:// bookmark/tag or a mailto: author
                // up against (it's shared by the details pane and plain tooltips alike) - resolve()
                // is passed an empty list, so only jjc:// (self-sufficient from project + the URI's
                // own path) and issue-tracker http(s) links ever actually resolve here. Right-click
                // resolution against the *right* entry happens separately in
                // JujutsuCommitDetailsPanel, which does have one.
                val uri = e.description?.let { runCatching { URI(it) }.getOrNull() }
                val target = uri?.let { LogClickTarget.resolve(it, project, emptyList()) }
                when {
                    target != null -> target.performDefaultAction(project)
                    // Bookmark/tag chips have no left-click action (jj-idea-wkcz) - the right-click
                    // menu (see refUriAt) is their only interactive affordance - so a jjref:// link
                    // here is a no-op rather than falling through to the browser handler below.
                    uri?.scheme == "jjref" -> Unit
                    // Not one of our internal schemes (e.g. an issue-tracker link, jj-idea-10fo, or a
                    // mailto: author link that couldn't resolve without an entry list) — hand off to
                    // the platform's standard browser/mail handler.
                    else -> BrowserHyperlinkListener.INSTANCE.hyperlinkUpdate(e)
                }
            }
        }
    }

    /**
     * The URI of a linkified issue-tracker reference inside a bookmark/tag chip's own name at
     * [point], if any (jj-idea-vrmv follow-up) - the HTML-backend counterpart to the log table's
     * `LaidOutCell.linkTargetAt`. Needs pixel-level hit-testing within the atomic unit's inner
     * document (see [AtomicHtmlView.hrefAtContentX]), since Swing's hyperlink activation only ever
     * reports the whole outer anchor's href regardless of exactly where inside it was clicked.
     */
    fun issueLinkUriAt(point: Point): URI? {
        val doc = document as? HTMLDocument ?: return null
        val offset = characterOffsetAt(point)
        val elem = doc.getCharacterElement(offset)
        if (elem.name != "img") return null
        val atomicView = leafViewAt(elem.startOffset) as? AtomicHtmlView ?: return null
        val leftEdge = runCatching { modelToView2D(elem.startOffset) }.getOrNull() ?: return null
        val href = atomicView.hrefAtContentX(
            (point.x - leftEdge.x).roundToInt(),
            (point.y - leftEdge.y).roundToInt()
        )
        return href?.let { runCatching { URI(it) }.getOrNull() }
    }

    /**
     * The leaf [View] rendering document position [pos] - standard Swing idiom for resolving the
     * concrete `View` (here, an [AtomicHtmlView]) backing a given offset, since
     * [javax.swing.text.Element] alone doesn't expose which View class was created for it.
     */
    private fun leafViewAt(pos: Int): View? {
        var view: View = getUI().getRootView(this)
        while (view.viewCount > 0) {
            val idx = view.getViewIndex(pos, Position.Bias.Forward)
            if (idx < 0) return null
            view = view.getView(idx)
        }
        return view
    }

    /**
     * The raw `href` (any scheme - `jjref://`, `jjc://`, `mailto:`, an issue-tracker link, ...) of
     * whatever's under [point], or null if it's not a link at all. Used for right-click resolution
     * ([in.kkkev.jjidea.ui.log.JujutsuCommitDetailsPanel] builds a context menu per scheme) -
     * [refUriAt] is the `jjref://`-only convenience wrapper most callers actually want.
     */
    fun hrefAt(point: Point): String? {
        val doc = document as? HTMLDocument ?: return null
        return hrefAncestorOf(doc.getCharacterElement(characterOffsetAt(point)))
    }

    /** Parse a `jjref://` href from the HTML element under [point], or null if not a ref link. */
    fun refUriAt(point: Point): URI? =
        hrefAt(point)?.takeIf { REF_URL_PARSER.matcher(it).matches() }?.let { href ->
            runCatching { URI(href) }.getOrNull()
        }

    /**
     * The atomic-content `<img>` element at [point], if any, and only if it's inside a link
     * (jj-idea-iesq) - a real hyperlink (e.g. mailto) or a `jjref://` (bookmark/tag) ref, either of
     * which [AtomicHtmlView] paints its own hover cue for (underline vs. a hover-highlight
     * background respectively, jj-idea-a52h).
     */
    private fun linkedChipElementAt(point: Point): Element? {
        val doc = document as? HTMLDocument ?: return null
        val elem = doc.getCharacterElement(characterOffsetAt(point))
        if (elem.name != "img") return null
        val src = elem.attributes.getAttribute(HTML.Attribute.SRC) as? String ?: return null
        if (!src.startsWith(UNBREAKABLE_PREFIX)) return null
        return if (hrefAncestorOf(elem) != null) elem else null
    }

    /**
     * The document offset of the character under [point], correctly attributing the *whole* visual
     * width of an atomic one-position-wide unit ([AtomicHtmlView]) to itself (jj-idea-wkcz follow-up).
     *
     * [javax.swing.text.JTextComponent.viewToModel2D] alone isn't enough: `AtomicHtmlView.viewToModel`
     * (needed for caret placement) returns its own `startOffset` for the left half of the chip and
     * `endOffset` for the right half - but a leaf element's range is `[startOffset, endOffset)`,
     * *exclusive* of `endOffset`, so [HTMLDocument.getCharacterElement] at exactly `endOffset`
     * resolves to the *next* sibling instead, not the chip. Without this, hovering/right-clicking
     * the right half of any chip silently missed its `href` - noticeably breaking bookmark/tag
     * chips once they lost their native `<a>` hand-cursor cue as the fallback affordance (a real
     * left-click hyperlink never had this problem, since Swing's own hit-testing for cursor/click
     * activation doesn't go through this offset-based path at all).
     *
     * The fix reads the [Position.Bias] the platform's hit-testing actually computed - `Backward`
     * means "the character *before* this offset", i.e. still inside the chip that ends here - and
     * shifts back by one in that case, rather than guessing from pixel position ourselves.
     */
    private fun characterOffsetAt(point: Point): Int {
        val biasReturn = arrayOf(Position.Bias.Forward)
        val offset = getUI().viewToModel2D(this, point, biasReturn)
        return if (biasReturn[0] == Position.Bias.Backward) (offset - 1).coerceAtLeast(0) else offset
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
internal fun hrefOf(element: Element): String? {
    val attrs = element.attributes
    (attrs.getAttribute(HTML.Attribute.HREF) as? String)?.let { return it }
    val anchorAttrs = attrs.getAttribute(HTML.Tag.A) as? AttributeSet
    return anchorAttrs?.getAttribute(HTML.Attribute.HREF) as? String
}

/**
 * The `href` on [element] or its nearest ancestor carrying one, if any (jj-idea-iesq). Used to
 * decide whether a chip gets a hover cue at all ([IconAwareHtmlPane.linkedChipElementAt]); which
 * *kind* of cue - underline for a real link, background highlight for a `jjref://` ref
 * (jj-idea-wkcz, jj-idea-a52h) - is then decided in [AtomicHtmlView] itself.
 */
internal fun hrefAncestorOf(element: Element): String? {
    var elem: Element? = element
    while (elem != null) {
        hrefOf(elem)?.let { return it }
        elem = elem.parentElement
    }
    return null
}

/**
 * Resolves an [IconSpec.qualified] key (from either icon library) back to a real [Icon] - the single
 * lookup both the outer document and [AtomicHtmlView]'s inner document use to resolve an
 * `<img src='icon:...'/>` element, via the shared `iconViewOrNull` in `AtomicHtmlView.kt`.
 */
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
 * Corrects High-DPI "Double Scaling", reporting the icon's real (unscaled) width/height so
 * [in.kkkev.jjidea.ui.components.IconLeafView] can position it against real font metrics itself -
 * used for every `<img src='icon:...'/>` element, in both the outer document and an
 * [AtomicHtmlView]'s inner document.
 */
internal class ScaleCorrectedIcon(private val source: Icon) : Icon {
    override fun getIconWidth() = (source.iconWidth / JBUIScale.scale(1f)).roundToInt()
    override fun getIconHeight() = (source.iconHeight / JBUIScale.scale(1f)).roundToInt()
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) = source.paintIcon(c, g, x, y)
}
