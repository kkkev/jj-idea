package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl

/**
 * Reconciles per-parent blame into a single annotation for a merge commit's auto-merged
 * parent tree ([mergeContent]). `jj file annotate` can only annotate a single real revision, so
 * for a merge commit's parent tree — a synthetic reconstruction, not a real revision — blame must
 * be assembled from each real parent's own annotation.
 *
 * Lines in [mergeContent] that are unchanged from a parent's content inherit that parent's blame.
 * Lines unique to the merge tree (jj's own conflict markers) are attributed to [mergeCommit]
 * itself, carrying its full [LogEntry.parentIds] so that "Annotate Previous Revision" on such a
 * line correctly declines (multiple parents, no single correct "previous") rather than guessing.
 */
object MergeAnnotationReconciler {
    fun reconcile(
        mergeContent: String,
        mergeCommit: LogEntry,
        parentAnnotations: List<List<AnnotationLine>>
    ): List<AnnotationLine> {
        val mergeLines = splitPreservingNewlines(mergeContent)
        val attribution = arrayOfNulls<AnnotationLine>(mergeLines.size)

        for (parentLines in parentAnnotations) {
            if (attribution.none { it == null }) break
            val parentContentLines = parentLines.map { it.lineContent }
            for ((mergeIdx, parentIdx) in alignedIndices(mergeLines, parentContentLines)) {
                if (attribution[mergeIdx] == null) {
                    attribution[mergeIdx] = parentLines[parentIdx]
                }
            }
        }

        return mergeLines.mapIndexed { idx, content ->
            val lineNumber = idx + 1
            attribution[idx]?.copy(lineContent = content, lineNumber = lineNumber)
                ?: unattributedLine(mergeCommit, content, lineNumber)
        }
    }

    private fun unattributedLine(mergeCommit: LogEntry, content: String, lineNumber: Int) = AnnotationLine(
        id = mergeCommit.id,
        commitId = mergeCommit.commitId,
        author = mergeCommit.author ?: VcsUserImpl("", ""),
        authorTimestamp = mergeCommit.authorTimestamp,
        description = mergeCommit.description,
        parentIds = mergeCommit.parentIds,
        lineContent = content,
        lineNumber = lineNumber
    )

    /**
     * Pairs of (mergeLineIndex, parentLineIndex) for lines [Diff.buildChanges] considers
     * unchanged between [mergeLines] and [parentLines].
     */
    private fun alignedIndices(mergeLines: List<String>, parentLines: List<String>): List<Pair<Int, Int>> {
        val change = try {
            Diff.buildChanges(mergeLines.toTypedArray(), parentLines.toTypedArray())
        } catch (e: FilesTooBigForDiffException) {
            return emptyList()
        }

        val correspondence = mutableListOf<Pair<Int, Int>>()
        var idx0 = 0
        var idx1 = 0
        var current = change
        while (current != null) {
            while (idx0 < current.line0) {
                correspondence.add(idx0 to idx1)
                idx0++
                idx1++
            }
            idx0 = current.line0 + current.deleted
            idx1 = current.line1 + current.inserted
            current = current.link
        }
        while (idx0 < mergeLines.size && idx1 < parentLines.size) {
            correspondence.add(idx0 to idx1)
            idx0++
            idx1++
        }
        return correspondence
    }

    /** Splits [content] into lines, preserving each line's trailing `\n` (matching the convention
     * of `jj file annotate`'s own `content` field, which already includes the line terminator) —
     * except possibly the last line, if the file has no trailing newline. */
    private fun splitPreservingNewlines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        while (start < content.length) {
            val newlineIdx = content.indexOf('\n', start)
            if (newlineIdx == -1) {
                lines.add(content.substring(start))
                break
            }
            lines.add(content.substring(start, newlineIdx + 1))
            start = newlineIdx + 1
        }
        return lines
    }
}
