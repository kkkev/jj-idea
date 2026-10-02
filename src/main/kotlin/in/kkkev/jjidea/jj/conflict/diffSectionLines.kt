package `in`.kkkev.jjidea.jj.conflict

enum class DiffLineKind { ADDED, REMOVED, CONTEXT }

/**
 * One line of a `%%%%%%%` diff section's raw span: [prefixOffset] is where its `-`/`+` prefix
 * character sits (for a [DiffLineKind.CONTEXT] line, where its first character sits - it has no
 * prefix to dim), [lineEnd] the offset of its terminating `\n` (or the span end).
 */
data class DiffSectionLine(val prefixOffset: Int, val lineEnd: Int, val kind: DiffLineKind)

/**
 * Classifies each line of [text]`[start, end)` - a [ConflictSide.isDiffSection] side's raw span -
 * by its leading `-`/`+`, matching [JjConflictBlockParser]'s own materialization rule. Display
 * only (jj-idea-8u0g); O(end - start).
 */
fun diffSectionLines(text: CharSequence, start: Int, end: Int): List<DiffSectionLine> {
    val lines = mutableListOf<DiffSectionLine>()
    var pos = start
    while (pos < end) {
        var lineEnd = pos
        while (lineEnd < end && text[lineEnd] != '\n') lineEnd++
        val kind = when {
            lineEnd == pos -> DiffLineKind.CONTEXT
            text[pos] == '+' -> DiffLineKind.ADDED
            text[pos] == '-' -> DiffLineKind.REMOVED
            else -> DiffLineKind.CONTEXT
        }
        lines += DiffSectionLine(pos, lineEnd, kind)
        pos = lineEnd + 1
    }
    return lines
}
