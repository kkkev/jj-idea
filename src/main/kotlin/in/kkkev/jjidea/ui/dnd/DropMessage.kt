package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.ui.components.TextCanvas
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.appendBookmarkChip
import `in`.kkkev.jjidea.ui.components.appendTagChip
import `in`.kkkev.jjidea.ui.components.htmlText

/**
 * What a drop would do, or why it can't, as structured text: literal strings, [ChangeId]s,
 * [Bookmark]s and [Tag]s.
 * Built once by [DropOperation] and [DragContext.rejectionMessage], then rendered two ways -
 * [plain] (an id is its short form, e.g. "Rebase kk onto mz") for the platform's own drop text,
 * `DropOperation.label` and tests, and [render]/[html] (an id, bookmark or tag styled exactly as the
 * log shows it: bold unique prefix and grey remainder for an id, icon chips for a ref) for [DropHint].
 *
 * Keeping these as typed parts, rather than formatting them into a string up front, is what lets
 * the hint style them - there is nothing to parse back out of a finished string.
 */
class DropMessage private constructor(private val parts: List<Any>) {
    /** The unstyled text - identical to what these messages were before they had structure. */
    val plain: String = parts.joinToString("") {
        when (it) {
            is ChangeId -> it.short
            is Bookmark -> it.name.toString()
            is Tag -> it.name
            else -> it as String
        }
    }

    /** A blank message means "say nothing" - the deliberate silent self-drop reject. */
    val isBlank: Boolean get() = plain.isBlank()

    /**
     * Write this message to [canvas], styling each typed part via the log's own `append` for it.
     * [fit] decides how bookmark/tag names are shown: a chip is one unbreakable unit, so one wider
     * than the hint can't wrap and just overflows it, and Swing's row breaker handles a wide
     * unbreakable unit badly (it drags the preceding word onto the chip's row and clips the tail).
     */
    fun render(canvas: TextCanvas, fit: RefFit = RefFit.DEFAULT) {
        var skipSpaces = false // the previous chip ended its line, so the next text starts a fresh one
        parts.forEachIndexed { i, part ->
            when (part) {
                is ChangeId -> canvas.append(part)
                is Bookmark -> canvas.chip(fit, part.name.name, i) { canvas.appendBookmarkChip(part, it) }
                is Tag -> canvas.chip(fit, part.name, i) { canvas.appendTagChip(part, it) }
                else -> canvas.appendText(part as String, afterChip = parts.getOrNull(i - 1).isChip, skipSpaces)
            }
            skipSpaces = (part is Bookmark && fit.ownLine(fit.shorten(part.name.name))) ||
                (part is Tag && fit.ownLine(fit.shorten(part.name)))
        }
    }

    /** One chip, on a line of its own (a break before and after) when [RefFit.ownLine] says it's wide. */
    private fun TextCanvas.chip(fit: RefFit, name: String, index: Int, draw: (String) -> Unit) {
        val shown = fit.shorten(name)
        val own = fit.ownLine(shown)
        if (own && index > 0) control("<br>")
        draw(shown)
        if (own && index < parts.lastIndex) control("<br>")
    }

    /**
     * Text following a chip: its leading spaces must be [TextCanvas.space]s, because in HTML a plain
     * space right after a chip's `<img>` collapses to nothing (see `HtmlTextCanvas.space`) and the
     * next word would butt against the chip's last letter. A non-breaking space glues the following
     * word to the chip though, so when the row is full there is nowhere legal to wrap and the text is
     * split mid-word - hence the zero-width space after it, which is invisible but a legal break.
     * Elsewhere plain spaces are kept so lines can wrap between words. [skipSpaces]: the chip was
     * followed by a line break, so leading spaces would just indent the new line.
     */
    private fun TextCanvas.appendText(text: String, afterChip: Boolean, skipSpaces: Boolean) {
        val leading = if (afterChip) text.takeWhile { it == ' ' }.length else 0
        if (!skipSpaces) {
            repeat(leading) {
                space()
                append(ZERO_WIDTH_SPACE)
            }
        }
        text.drop(leading).takeIf { it.isNotEmpty() }?.let { append(it) }
    }

    private val Any?.isChip get() = this is Bookmark || this is Tag

    /** An HTML fragment (no enclosing `<html>`) for embedding in a larger document. */
    val html: String get() = html(RefFit.DEFAULT)

    /** As [html], with bookmark/tag names fitted by [fit]. */
    fun html(fit: RefFit): String = htmlText { render(this, fit) }

    override fun equals(other: Any?) = other is DropMessage && parts == other.parts

    override fun hashCode() = parts.hashCode()

    override fun toString() = plain

    companion object {
        private const val ZERO_WIDTH_SPACE = "\u200B"

        /** Longest bookmark/tag name shown in full by [RefFit.DEFAULT]. */
        internal const val MAX_REF_CHARS = 60

        internal fun ellipsize(name: String, max: Int = MAX_REF_CHARS): String {
            if (name.length <= max) return name
            // Keep both ends - the start says which family of refs, the end usually says which one.
            val keep = max - 1
            val head = (keep + 1) / 2
            return name.take(head) + "…" + name.takeLast(keep - head)
        }

        /**
         * [name], middle-ellipsized only as far as needed for [metrics] to measure it within [maxPx].
         * (The chip's icon and padding are the caller's to subtract from [maxPx].)
         */
        internal fun ellipsizeToWidth(name: String, metrics: java.awt.FontMetrics, maxPx: Int): String {
            if (metrics.stringWidth(name) <= maxPx) return name
            var max = name.length - 1
            while (max > MIN_REF_CHARS && metrics.stringWidth(ellipsize(name, max)) > maxPx) max--
            return ellipsize(name, max)
        }

        private const val MIN_REF_CHARS = 5

        /** Reject without saying why - see [isBlank]. */
        val EMPTY = DropMessage(emptyList())

        /**
         * Build from [parts]: each a [String], [ChangeId], [Bookmark], [Tag], or another [DropMessage] (spliced in),
         * in order. Adjacent strings are kept separate; only [plain] concatenates them.
         */
        fun of(vararg parts: Any) = DropMessage(
            parts.flatMap { part ->
                when (part) {
                    is String, is ChangeId, is Bookmark, is Tag -> listOf(part)
                    is DropMessage -> part.parts
                    else -> throw IllegalArgumentException("Unsupported DropMessage part: ${part::class}")
                }
            }
        )
    }
}

/**
 * How [DropMessage.render] shows a bookmark/tag name: [shorten] it to fit, and [ownLine] says
 * whether the (shortened) chip is wide enough to deserve a line to itself rather than flowing
 * inline. [DropHint] builds one from measured font metrics so both follow the actual UI scale.
 */
class RefFit(val shorten: (String) -> String, val ownLine: (String) -> Boolean) {
    companion object {
        /** Character-count based, for callers with no font to measure: cap long names, never force a line. */
        val DEFAULT = RefFit({ DropMessage.ellipsize(it) }, { false })
    }
}
