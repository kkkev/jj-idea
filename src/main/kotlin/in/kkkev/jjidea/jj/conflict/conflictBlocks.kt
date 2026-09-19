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
    var open = false
    var lineStart = 0
    val length = text.length
    var i = 0
    while (i <= length) {
        if (i == length || text[i] == '\n') {
            if (i - lineStart >= 7) {
                if (!open && text.startsWith("<<<<<<<", lineStart)) {
                    open = true
                } else if (open && text.startsWith(">>>>>>>", lineStart)) {
                    open = false
                    count++
                }
            }
            lineStart = i + 1
        }
        i++
    }
    return count
}
