package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.codeInsight.hint.TooltipController
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.conflict.ConflictBlock
import `in`.kkkev.jjidea.jj.conflict.conflictBlockIndexAt
import `in`.kkkev.jjidea.jj.conflict.sideAt
import `in`.kkkev.jjidea.jj.conflict.sideFor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

/** How much closer to the scheme's foreground color a hovered side's tint moves - tuned in runIde. */
private const val HOVER_MIX = 0.15

/**
 * Strengthens a conflict block side's own background tint (jj-idea-82fo's
 * `DiffColors.DIFF_DELETED`/`DIFF_INSERTED`/`DIFF_MODIFIED` highlighters) while the mouse sits
 * over that side's text **or** its gutter row/icon - jj-idea-sr42, this codebase's first
 * [EditorMouseMotionListener] use (grepped: none existed anywhere before this). Deliberately a
 * plain highlighter overlay rather than touching the base tint highlighters
 * [JujutsuConflictGutterInstaller] already owns: those are disposed/recreated wholesale on every
 * debounced rescan, so tracking "the currently-hovered one" through that churn would be far more
 * bookkeeping than just adding one extra, independently-lived highlighter above them.
 *
 * [mouseMoved] hit-tests via [conflictBlockIndexAt] (binary search, O(log blocks in file)) and
 * [ConflictBlock.sideAt] - no re-scan of [blocksProvider], no per-event allocation - and only
 * touches the markup model when the hovered side actually *changes* (the fast path below returns
 * immediately for every event while the cursor stays within the same side, including the common
 * case of many small moves while reading). [blocksProvider] reads
 * [in.kkkev.jjidea.jj.conflict.ConflictRegionScanner.blocks] live off the scanner - that list is
 * updated synchronously in `documentChanged` (see that class's own doc), so a hit test is always
 * against current-document offsets even while [JujutsuConflictGutterInstaller]'s own debounced
 * icon/highlighter reconciliation hasn't caught up yet.
 *
 * A [DocumentListener] clears the hover on every edit rather than trying to shift it - edits can
 * change which side (or whether any side) now covers the old offset, and the very next
 * `mouseMoved` (Swing always sends one after a keystroke moves text under a stationary cursor)
 * re-establishes it correctly, so there's nothing this needs to get right incrementally here.
 *
 * Never calls [EditorMouseEvent.consume] and never touches [EditorMouseMotionListener.mouseDragged] -
 * normal text selection/editing is entirely untouched by this class.
 *
 * [mouseExited], [documentChanged], and a [VisibleAreaListener] (scrolling the editor moves the
 * hovered icon out from under a *stationary* mouse cursor, which fires no `mouseMoved` at all -
 * the one genuine gap in the platform's own gutter tooltip dismissal, which otherwise reacts to
 * real mouse movement on its own) all force-cancel any gutter tooltip currently showing via
 * [TooltipController], on top of clearing this class's own highlighters - see [clearAndCancel].
 * Plain [mouseMoved] side-to-side transitions deliberately do *not* call this: the platform's own
 * `mouseMoved`-driven tooltip logic already correctly swaps to the new icon's tooltip on that same
 * event, and force-cancelling here would race it and occasionally eat the new tooltip instead.
 *
 * Alongside the text tint, [mouseMoved] adds a second, zero-length highlighter anchored at the
 * same offset the hovered side's own gutter icon sits at
 * ([in.kkkev.jjidea.ui.editor.conflict.JujutsuConflictGutterInstaller]'s own `offsetFor` uses the
 * identical `side.contentStartOffset`), carrying a [ConflictIconHoverBackground]
 * [LineMarkerRendererEx] - a rounded highlight behind that one icon, painted into the gutter's
 * own icon column via [EditorEx.getGutterComponentEx]'s `iconAreaOffset`/`iconsAreaWidth`. This
 * mirrors how the platform's own diff viewer paints custom gutter backgrounds
 * ([com.intellij.diff.util.DiffLineMarkerRenderer] does the identical `EditorEx` cast +
 * `Position.CUSTOM` dance) - a `RangeHighlighter`'s `TextAttributes` background never reaches the
 * gutter strip at all, so a `LineMarkerRenderer` is the only way to tint behind a gutter icon.
 */
internal class ConflictSideHover(
    private val editor: Editor,
    private val blocksProvider: () -> List<ConflictBlock>
) : EditorMouseMotionListener, EditorMouseListener, DocumentListener, VisibleAreaListener, Disposable {
    private var hoveredStart = -1
    private var hoveredEnd = -1
    private var highlighter: RangeHighlighter? = null
    private var iconHighlighter: RangeHighlighter? = null

    fun install() {
        editor.addEditorMouseMotionListener(this, this)
        editor.addEditorMouseListener(this, this)
        editor.document.addDocumentListener(this, this)
        editor.scrollingModel.addVisibleAreaListener(this, this)
    }

    override fun mouseMoved(event: EditorMouseEvent) {
        val offset = event.offset
        // Fast path: still within the side we're already hovering - the overwhelmingly common case.
        if (highlighter != null && offset >= hoveredStart && offset < hoveredEnd) return

        val blocks = blocksProvider()
        val blockIndex = conflictBlockIndexAt(blocks, offset)
        val block = if (blockIndex >= 0) blocks[blockIndex] else null
        val choice = block?.sideAt(offset)
        if (block == null || choice == null) {
            clear()
            return
        }

        val side = block.sideFor(choice) ?: return
        val start = requireNotNull(side.contentStartOffset) { "sideAt only returns a choice whose side has offsets" }
        val end = requireNotNull(side.contentEndOffset)
        if (start == hoveredStart && end == hoveredEnd) return // same side, reached via a jump (e.g. from the gutter)

        clear()
        val attributes = hoverAttributes(conflictSideKey(choice)) ?: return
        hoveredStart = start
        hoveredEnd = end
        highlighter = editor.markupModel.addRangeHighlighter(
            start,
            end,
            HighlighterLayer.SELECTION - 1,
            attributes,
            HighlighterTargetArea.EXACT_RANGE
        )
        iconHighlighter = editor.markupModel.addRangeHighlighter(
            start,
            start,
            HighlighterLayer.SELECTION - 1,
            null,
            HighlighterTargetArea.EXACT_RANGE
        ).also { it.lineMarkerRenderer = ConflictIconHoverBackground }
    }

    override fun mouseExited(event: EditorMouseEvent) = clearAndCancel()

    override fun documentChanged(event: DocumentEvent) = clearAndCancel()

    override fun visibleAreaChanged(event: VisibleAreaEvent) = clearAndCancel()

    /** [clear] plus an explicit gutter-tooltip cancel - see this class's own KDoc for why only these three call sites need the cancel, and why plain [mouseMoved] transitions must not. */
    private fun clearAndCancel() {
        val wasHovering = highlighter != null
        clear()
        if (wasHovering) TooltipController.getInstance().cancelTooltips()
    }

    private fun clear() {
        highlighter?.dispose()
        highlighter = null
        iconHighlighter?.dispose()
        iconHighlighter = null
        hoveredStart = -1
        hoveredEnd = -1
    }

    /** The base tint's own background, mixed [HOVER_MIX] of the way toward the scheme's foreground - theme-agnostic: darkens on a light scheme, lightens on a dark one. `null` if the scheme gives [key] no background at all (a legitimate scheme choice, not a bug - nothing to strengthen then). */
    private fun hoverAttributes(key: TextAttributesKey): TextAttributes? {
        val scheme = editor.colorsScheme
        val base = scheme.getAttributes(key)?.backgroundColor ?: return null
        val mixed = ColorUtil.mix(base, scheme.defaultForeground, HOVER_MIX)
        return TextAttributes().apply { backgroundColor = mixed }
    }

    override fun dispose() = clear()
}

/**
 * A rounded "button hover" highlight behind one conflict block side's gutter icon - the same
 * [JBUI.CurrentTheme.ActionButton.hoverBackground] color used for hovering an ordinary toolbar
 * button, rather than the side's own tint color, so the icon reads as *the clickable thing*,
 * distinct from [ConflictSideHover]'s own text-tint strengthening. Deliberately a singleton
 * (stateless, reused by every [ConflictSideHover] instance's icon highlighter) - unlike
 * [ConflictBlockGutterIconRenderer], which must vary per block/side, this renderer's output never
 * depends on which side or block it's painting for, only on where the platform tells it to paint.
 *
 * [getPosition] returns [LineMarkerRendererEx.Position.CUSTOM] because that is the *only*
 * position that hands [paint] a rectangle covering the gutter's icon column at all (`LEFT`/
 * `RIGHT` are the free-painters strips flanking it, never the icon column itself) - see
 * [ConflictSideHover]'s own KDoc for why a `RangeHighlighter`'s plain `TextAttributes` background
 * can't do this instead.
 */
internal object ConflictIconHoverBackground : LineMarkerRendererEx {
    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.CUSTOM

    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        val gutter = (editor as? EditorEx)?.gutterComponentEx ?: return
        val width = gutter.iconsAreaWidth
        if (width <= 0) return // no icon column at all right now - nothing to paint behind
        val x = gutter.iconAreaOffset

        val g2 = g as Graphics2D
        val oldHint = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        try {
            g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
            val inset = JBUI.scale(1)
            val arc = JBUI.scale(6)
            g2.fillRoundRect(x + inset, r.y + inset, width - 2 * inset, r.height - 2 * inset, arc, arc)
        } finally {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldHint)
        }
    }
}
