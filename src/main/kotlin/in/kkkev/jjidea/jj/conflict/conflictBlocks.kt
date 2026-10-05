package `in`.kkkev.jjidea.jj.conflict

/**
 * Cheap live count of complete jj conflict blocks in editor text, for
 * [in.kkkev.jjidea.ui.editor.JujutsuConflictEditorNotificationProvider]'s per-keystroke-class
 * debounced rescan. A single line-wise pass, O(document length), with no line-list allocation -
 * unlike [JjMarkerConflictExtractor.extract] this only counts blocks rather than materialising
 * side content, so it's cheap enough to run on every debounced edit rather than once per banner.
 *
 * A block only counts once its closing `>>>>>>>` line has been seen - a block whose closing
 * marker has been hand-edited away (or not yet reached) doesn't count, matching
 * [JjMarkerConflictExtractor]'s own "unterminated block" handling.
 */
fun countConflictBlocks(text: CharSequence): Int {
    var count = 0
    forEachConflictBlock(text) {
        count++
        true
    }
    return count
}

/**
 * Offset of the `<<<<<<<` line opening the first *complete* conflict block in [text], or `null`
 * if there is none. Same single-pass walk as [countConflictBlocks], exiting at the first block's
 * closing marker - used to put the caret on the first conflict when opening a conflicted file.
 */
fun firstConflictBlockOffset(text: CharSequence): Int? {
    var offset: Int? = null
    forEachConflictBlock(text) { start ->
        offset = start
        false
    }
    return offset
}

/** Calls [onBlock] with each complete block's opening offset until it returns `false`. */
private inline fun forEachConflictBlock(text: CharSequence, onBlock: (Int) -> Boolean) {
    var openStart = -1
    var lineStart = 0
    val length = text.length
    var i = 0
    while (i <= length) {
        if (i == length || text[i] == '\n') {
            if (i - lineStart >= 7) {
                if (openStart < 0 && text.startsWith("<<<<<<<", lineStart)) {
                    openStart = lineStart
                } else if (openStart >= 0 && text.startsWith(">>>>>>>", lineStart)) {
                    val start = openStart
                    openStart = -1
                    if (!onBlock(start)) return
                }
            }
            lineStart = i + 1
        }
        i++
    }
}
