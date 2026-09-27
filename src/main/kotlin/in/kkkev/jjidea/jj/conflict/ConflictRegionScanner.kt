package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.diagnostic.Logger

/**
 * Line cap on how far [ConflictRegionScanner.applyEdit] will search past a `<<<<<<<` for its
 * closing `>>>>>>>` while resyncing, before giving up on staying incremental and paying for one
 * bounded [ConflictRegionScanner.fullScan] instead of scanning unboundedly toward a closer that
 * may not exist anywhere below it in a huge file. See [ConflictRegionScanner.degraded].
 */
const val MAX_OPEN_BLOCK_LINES = 50_000

private val markerPrefixes =
    listOf("<<<<<<<", "|||||||", "=======", ">>>>>>>", "+++++++", "-------", "%%%%%%%", "\\\\\\")

/**
 * Tells a caller exactly which of its own highlighters/regions to drop and which to create for
 * one document edit, rather than forcing a full highlighter-set rebuild every keystroke.
 *
 * @param removedBlocks Blocks from the scanner's *previous* [ConflictRegionScanner.blocks],
 *   invalidated by this edit - drop their highlighters.
 * @param addedBlocks Blocks now covering that region - create highlighters for these. May be
 *   fewer/more/differently-shaped than [removedBlocks] (blocks can merge, split, appear, vanish).
 * @param offsetDelta Every block after the edit that appears in neither list only had its offsets
 *   shifted by this amount (and its line numbers by the edit's line delta) - a
 *   `RangeHighlighter`/`RangeMarker`-based caller doesn't need to do anything for these beyond
 *   what the platform's own document-edit machinery already does for free.
 */
data class Rescan(
    val removedBlocks: List<ConflictBlock>,
    val addedBlocks: List<ConflictBlock>,
    val offsetDelta: Int
)

/**
 * Incremental, platform-free scanner over jj conflict marker blocks (jj-idea-82fo, stage 2/4),
 * backing the in-editor gutter region model. Pure `CharSequence` + edit-descriptor API - no
 * `Document` dependency, so it is plain-`test`-task testable; stage 3 wires it to a real
 * `Document` via a debounced listener (reusing `debouncedDocumentScan`).
 *
 * [fullScan] is one call to [JjConflictBlockParser.parseAll] - O(document length) - used for the
 * first scan of a newly opened editor. [applyEdit] is the incremental path a debounced
 * `documentChanged` callback drives thereafter.
 *
 * ### Why the incremental bound is correct
 * [JjConflictBlockParser]'s only cross-line state is a single open/closed bit (blocks never
 * nest - see that class's doc), so once [applyEdit] finds a point after the edit where it is
 * *not* inside a block and that point's offset (mapped back through the edit) exactly matches an
 * existing, not-yet-invalidated block's own start, everything from there on is provably unchanged
 * (just shifted) - re-parsing it would only reproduce what [blocks] already has. [applyEdit]
 * only extends its rescan past that point when a reparsed block's *own* extent swallows it (e.g.
 * deleting a `>>>>>>>` merges two blocks into one), and then only as far as needed.
 *
 * Cost per edit: for the overwhelmingly common case - an edit that overlaps no existing block and
 * inserts no marker-shaped line - O(edit size), with no scan beyond the edited lines themselves
 * (checked via [containsMarkerLine]). Otherwise O(edit size + the extent of every block the edit
 * invalidates or newly creates, up to the first resync point) - see
 * `ConflictRegionScannerScaleTest` for the operation-count proof. The one case that could
 * otherwise be unbounded - a hand-typed `<<<<<<<` with no `>>>>>>>` anywhere below it in a huge
 * file - is capped at [MAX_OPEN_BLOCK_LINES] lines of search, past which [applyEdit] gives up on
 * staying incremental and pays for one bounded [fullScan] instead, setting [degraded] and logging
 * one `perf:` WARN.
 */
class ConflictRegionScanner {
    var blocks: List<ConflictBlock> = emptyList()
        private set

    /**
     * Work-unit counter (characters scanned) for the most recent [fullScan] or [applyEdit] call
     * only - reset at the start of each. See `ConflictRegionScannerScaleTest`.
     */
    var operationCount: Long = 0
        private set

    /**
     * Set by the most recent [applyEdit] if it hit [MAX_OPEN_BLOCK_LINES] while resyncing and
     * fell back to a full [fullScan] rather than searching unboundedly. Cleared by every
     * [fullScan] and by any [applyEdit] that doesn't hit the cap.
     */
    var degraded: Boolean = false
        private set

    /** Full parse of [text] - O(text.length). Replaces [blocks] outright. */
    fun fullScan(text: CharSequence): List<ConflictBlock> {
        degraded = false
        operationCount = text.length.toLong()
        blocks = JjConflictBlockParser.parseAll(text)
        return blocks
    }

    /**
     * Incrementally updates [blocks] for one document edit already applied to [text] (the new,
     * full document text). `[changeStart, changeEndNew)` in [text] is the range the edit
     * inserted (possibly empty, for a pure deletion); [offsetDelta] is `newLength - oldLength` for
     * the edit and [lineDelta] the corresponding change in line count - both trivial for a real
     * `DocumentEvent`-driven caller to supply (`event.newFragment`/`oldFragment` newline counts,
     * or `document.getLineNumber` before/after), so this stays a pure function of its arguments
     * rather than needing the previous document text.
     */
    fun applyEdit(text: CharSequence, changeStart: Int, changeEndNew: Int, offsetDelta: Int, lineDelta: Int): Rescan {
        degraded = false
        var ops = 0L
        val oldChangeEnd = changeEndNew - offsetDelta

        var firstAffected = blocks.indexOfFirst { it.endOffset > changeStart }
        if (firstAffected < 0) firstAffected = blocks.size
        val overlapsEdit = firstAffected < blocks.size && blocks[firstAffected].startOffset < oldChangeEnd

        if (!overlapsEdit) {
            val touchedStart = lineStartAtOrBefore(text, changeStart)
            val touchedEnd = lineEndAtOrAfter(text, changeEndNew)
            ops += (touchedEnd - touchedStart).toLong()
            if (!containsMarkerLine(text, touchedStart, touchedEnd)) {
                operationCount = ops
                blocks = blocks.map { if (it.startOffset >= oldChangeEnd) it.shifted(offsetDelta, lineDelta) else it }
                return Rescan(emptyList(), emptyList(), offsetDelta)
            }
        }

        // Slow path: rescan forward from either the invalidated block's own start, or this
        // edit's own line - resyncing against an untouched later block as soon as possible.
        val rescanStart = if (overlapsEdit) {
            blocks[firstAffected].startOffset
        } else {
            lineStartAtOrBefore(
                text,
                changeStart
            )
        }
        val added = mutableListOf<ConflictBlock>()
        var consumed = firstAffected
        var pos = rescanStart
        while (true) {
            // No old block left to resync onto, and we're past the edit itself: everything from
            // here on is untouched text the original full scan already proved marker-free (that
            // scan would have found another block here otherwise) - stop without scanning it.
            if (consumed >= blocks.size && pos >= changeEndNew) break

            val openAt = findNextOpenMarker(text, pos)
            ops += (openAt ?: text.length) - pos
            if (openAt == null) {
                consumed = blocks.size // nothing left to resync onto - everything remaining is consumed
                break
            }

            if (openAt >= changeEndNew) {
                val oldEquivalent = openAt - offsetDelta
                val next = blocks.getOrNull(consumed)
                if (next != null && next.startOffset == oldEquivalent) break // resynced
            }

            when (val closer = findCloserWithinCap(text, openAt)) {
                is CloserSearch.CapExceeded -> {
                    val previousBlocks = blocks
                    val fresh = fullScan(text)
                    degraded = true
                    LOG.warn(
                        "perf: conflict-region-rescan degraded (unterminated marker at offset $openAt exceeded $MAX_OPEN_BLOCK_LINES lines) - fell back to full scan"
                    )
                    return Rescan(previousBlocks, fresh, offsetDelta)
                }
                is CloserSearch.NotFound -> {
                    // Trailing unterminated block, omitted - matches JjConflictBlockParser.parseAll.
                    consumed = blocks.size
                    break
                }
                is CloserSearch.Found -> {
                    val block = requireNotNull(JjConflictBlockParser.parseBlockAt(text, openAt)) {
                        "parseBlockAt found no block at $openAt though a closer was found at ${closer.offset}"
                    }
                    added += block
                    pos = block.endOffset
                    while (consumed < blocks.size && blocks[consumed].startOffset + offsetDelta < pos) consumed++
                }
            }
        }

        operationCount = ops
        val removed = blocks.subList(firstAffected, consumed).toList()
        val tail = blocks.subList(consumed, blocks.size).map { it.shifted(offsetDelta, lineDelta) }
        blocks = blocks.subList(0, firstAffected) + added + tail
        return Rescan(removed, added.toList(), offsetDelta)
    }

    private companion object {
        val LOG = Logger.getInstance(ConflictRegionScanner::class.java)
    }
}

private sealed class CloserSearch {
    data class Found(val offset: Int) : CloserSearch()
    object NotFound : CloserSearch()
    object CapExceeded : CloserSearch()
}

/** Searches forward from just after [openAt]'s own line for a `>>>>>>>` line, capped at [MAX_OPEN_BLOCK_LINES]. */
private fun findCloserWithinCap(text: CharSequence, openAt: Int): CloserSearch {
    var pos = lineEndAtOrAfter(text, openAt)
    if (pos < text.length) pos++ // past the '\n'
    var lines = 0
    while (pos < text.length) {
        if (regionStartsWith(text, pos, ">>>>>>>")) return CloserSearch.Found(pos)
        if (lines >= MAX_OPEN_BLOCK_LINES) return CloserSearch.CapExceeded
        lines++
        pos = lineEndAtOrAfter(text, pos)
        if (pos < text.length) pos++
    }
    return CloserSearch.NotFound
}

private fun findNextOpenMarker(text: CharSequence, from: Int): Int? {
    var pos = from
    while (pos < text.length) {
        if (regionStartsWith(text, pos, "<<<<<<<")) return pos
        pos = lineEndAtOrAfter(text, pos)
        if (pos < text.length) pos++ else break
    }
    return null
}

private fun containsMarkerLine(text: CharSequence, from: Int, to: Int): Boolean {
    var pos = from
    while (pos < to) {
        if (markerPrefixes.any { regionStartsWith(text, pos, it) }) return true
        pos = lineEndAtOrAfter(text, pos) + 1
    }
    return false
}

private fun regionStartsWith(text: CharSequence, offset: Int, prefix: String): Boolean {
    if (offset < 0 || offset + prefix.length > text.length) return false
    for (k in prefix.indices) {
        if (text[offset + k] != prefix[k]) return false
    }
    return true
}

/** Offset of the start of the line containing (or starting at) [offset]. */
private fun lineStartAtOrBefore(text: CharSequence, offset: Int): Int {
    var i = offset
    while (i > 0 && text[i - 1] != '\n') i--
    return i
}

/** Offset of the `\n` ending the line containing (or starting at) [offset], or `text.length` if there is none. */
private fun lineEndAtOrAfter(text: CharSequence, offset: Int): Int {
    var i = offset
    while (i < text.length && text[i] != '\n') i++
    return i
}

private fun ConflictBlock.shifted(offsetDelta: Int, lineDelta: Int): ConflictBlock = copy(
    startOffset = startOffset + offsetDelta,
    endOffset = endOffset + offsetDelta,
    startLine = startLine + lineDelta,
    endLine = endLine + lineDelta,
    side1 = side1.shifted(offsetDelta),
    side2 = side2.shifted(offsetDelta),
    base = base?.shifted(offsetDelta)
)

private fun ConflictSide.shifted(offsetDelta: Int): ConflictSide = copy(
    contentStartOffset = contentStartOffset?.plus(offsetDelta),
    contentEndOffset = contentEndOffset?.plus(offsetDelta)
)
