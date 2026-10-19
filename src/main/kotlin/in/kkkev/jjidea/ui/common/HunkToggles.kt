package `in`.kkkev.jjidea.ui.common

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.EmptyProgressIndicator

/**
 * One contiguous changed block between a file's `before` and `after` text, in line indices
 * (`start` inclusive, `end` exclusive). A pure insertion has an empty before-range; a pure
 * deletion has an empty after-range.
 */
data class Hunk(
    val index: Int,
    val beforeStart: Int,
    val beforeEnd: Int,
    val afterStart: Int,
    val afterEnd: Int
)

/** Line-diff seam: production uses the platform's [ComparisonManager]; tests may count calls. */
fun interface LineDiffer {
    /** Changed blocks of [a] → [b] as (aStart, aEnd, bStart, bEnd) line ranges, in order. */
    fun diff(a: String, b: String): List<IntArray>
}

internal val platformLineDiffer = LineDiffer { a, b ->
    ComparisonManager.getInstance()
        .compareLines(a, b, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
        .map { intArrayOf(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
}

private fun String.lineList(): List<String> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    var start = 0
    while (start < length) {
        val nl = indexOf('\n', start)
        val end = if (nl < 0) length else nl + 1
        result += substring(start, end)
        start = end
    }
    return result
}

/**
 * Whole-hunk model of one file's change: the hunks between [before] and [after] and conversion
 * between a set of *applied* hunk indices and the resulting content. Pure; one diff per
 * construction, O(file lines) per call. Used by the inline gutter checkboxes in Split/Squash
 * (jj-idea-s5yx), which toggle whole hunks over the existing per-file override content model.
 */
class HunkToggles private constructor(
    private val before: String,
    private val after: String,
    val hunks: List<Hunk>,
    private val differ: LineDiffer
) {
    private val beforeLines = before.lineList()
    private val afterLines = after.lineList()

    /**
     * Contents this model composed, with the applied set that produced them. [appliedIn] consults
     * it first: re-diffing `before` against a composed subset can align differently from the
     * original hunks (repeated lines), which would wrongly read our own toggle as a custom edit.
     */
    private val composed = HashMap<String, Set<Int>>()

    /** Content with exactly the [applied] hunks taken from `after` and the rest left as `before`. */
    fun compose(applied: Set<Int>): String {
        val text = build(applied)
        if (composed.size < MAX_REMEMBERED) composed.putIfAbsent(text, applied.toSet())
        return text
    }

    private fun build(applied: Set<Int>): String {
        val sb = StringBuilder()
        var cursor = 0
        for (h in hunks) {
            for (i in cursor until h.beforeStart) sb.append(beforeLines[i])
            if (h.index in applied) {
                for (i in h.afterStart until h.afterEnd) sb.append(afterLines[i])
            } else {
                for (i in h.beforeStart until h.beforeEnd) sb.append(beforeLines[i])
            }
            cursor = h.beforeEnd
        }
        for (i in cursor until beforeLines.size) sb.append(beforeLines[i])
        return sb.toString()
    }

    /**
     * Which hunks [content] has applied, or null when [content] is not a whole-hunk subset of
     * this change (e.g. a line-level edit from the Pick Hunks… dialog).
     */
    fun appliedIn(content: String): Set<Int>? {
        if (content == before) return emptySet()
        if (content == after) return hunks.mapTo(LinkedHashSet()) { it.index }
        composed[content]?.let { return it }

        val contentLines = content.lineList()
        val candidate = LinkedHashSet<Int>()
        for ((aStart, aEnd, cStart, cEnd) in differ.diff(before, content)) {
            val hunk = hunks.firstOrNull { it.beforeStart == aStart && it.beforeEnd == aEnd } ?: continue
            val sameLength = hunk.afterEnd - hunk.afterStart == cEnd - cStart
            if (sameLength &&
                (0 until cEnd - cStart).all {
                    contentLines.getOrNull(cStart + it) == afterLines[hunk.afterStart + it]
                }
            ) {
                candidate += hunk.index
            }
        }
        return candidate.takeIf { build(it) == content }
    }

    companion object {
        private const val MAX_REMEMBERED = 256

        /**
         * Build the hunk model for [before]→[after], or null if there is nothing to toggle or the
         * model can't faithfully reproduce both endpoints (checkboxes are then simply not offered).
         */
        fun of(before: String, after: String, differ: LineDiffer = platformLineDiffer): HunkToggles? {
            if (before == after) return null
            val bl = before.lineList().size
            val al = after.lineList().size
            // The platform counts empty text as one empty line; clamp to our own line lists. The
            // endpoint checks below catch any other disagreement.
            val hunks = differ.diff(before, after)
                .map { r -> intArrayOf(minOf(r[0], bl), minOf(r[1], bl), minOf(r[2], al), minOf(r[3], al)) }
                .filter { r -> r[1] > r[0] || r[3] > r[2] }
                .mapIndexed { i, r -> Hunk(i, r[0], r[1], r[2], r[3]) }
            if (hunks.isEmpty()) return null
            val toggles = HunkToggles(before, after, hunks, differ)
            val all = hunks.mapTo(LinkedHashSet()) { it.index }
            if (toggles.build(emptySet()) != before || toggles.build(all) != after) return null
            return toggles
        }
    }
}
