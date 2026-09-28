package `in`.kkkev.jjidea.jj.conflict

/**
 * Index into [blocks] of the block containing [offset] (`startOffset <= offset < endOffset`), or
 * `-1` if none does - for [ConflictSideHover][in.kkkev.jjidea.ui.editor.conflict.ConflictSideHover]'s
 * per-mouse-move hit test. [blocks] must be sorted and non-overlapping (true of
 * [ConflictRegionScanner.blocks] by construction - [JjConflictBlockParser] emits blocks in
 * document order and they never nest, see that parser's own class doc).
 *
 * Binary search, O(log n) in the number of blocks in the file - not O(document length) - and
 * allocation-free, so a caller can run this on every `mouseMoved` event without a debounce. See
 * `ConflictHitTestScaleTest` for the operation-count proof.
 */
fun conflictBlockIndexAt(blocks: List<ConflictBlock>, offset: Int): Int {
    var lo = 0
    var hi = blocks.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val block = blocks[mid]
        when {
            offset < block.startOffset -> hi = mid - 1
            offset >= block.endOffset -> lo = mid + 1
            else -> return mid
        }
    }
    return -1
}

/**
 * Which side of this block [offset] falls within (its raw `[contentStartOffset,
 * contentEndOffset)` span), or `null` for a marker line, an empty side/base, or a side with no
 * offsets at all ([ConflictMarkerStyle.DIFF]'s derived base, or the [ConflictMarkerStyle
 * .UNRECOGNISED] fallback shape) - the same sides
 * [in.kkkev.jjidea.ui.editor.conflict.JujutsuConflictGutterInstaller] gives no background tint,
 * so there is nothing for a hover to strengthen.
 */
fun ConflictBlock.sideAt(offset: Int): AcceptChoice? {
    if (side1.contains(offset)) return AcceptChoice.SIDE1
    if (side2.contains(offset)) return AcceptChoice.SIDE2
    if (base?.contains(offset) == true) return AcceptChoice.BASE
    return null
}

private fun ConflictSide.contains(offset: Int): Boolean {
    val start = contentStartOffset ?: return false
    val end = contentEndOffset ?: return false
    return offset in start until end
}
