package `in`.kkkev.jjidea.util

/**
 * Utilities for parsing git unified diffs and reverse-applying them.
 *
 * Used to reconstruct the "before" file content from the "after" content + a git diff.
 * This is needed to derive the base (parent-revision) content for a file from:
 * - the current after-content (`jj file show -r <rev> <file>`)
 * - the git diff (`jj diff --git -r <rev> -- <file>`)
 */
object GitDiffReverseApplier {
    private val HUNK_HEADER =
        Regex("""^(@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*)$""")

    private enum class LineType { CONTEXT, ADD, REMOVE }

    private data class HunkLine(val type: LineType, val content: String)

    private data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<HunkLine>
    )

    /**
     * Reverse-applies [gitDiff] to [afterContent] to produce the "before" content.
     *
     * Returns null if the file is binary (cannot be reconstructed).
     * Returns empty string if the file was added (before = nothing).
     * Returns [afterContent] unchanged if the diff is empty or contains no hunks.
     */
    fun reverseApply(afterContent: String, gitDiff: String): String? {
        if ("Binary files" in gitDiff) return null
        if ("--- /dev/null" in gitDiff) return ""

        val hunks = parseHunks(gitDiff)
        if (hunks.isEmpty()) return afterContent

        val afterLines = splitLines(afterContent)
        val resultLines = mutableListOf<String>()
        var afterIdx = 0

        for (hunk in hunks) {
            val hunkStart = if (hunk.newCount == 0) 0 else hunk.newStart - 1

            // Copy unchanged after-lines between the previous hunk and this one.
            while (afterIdx < hunkStart && afterIdx < afterLines.size) {
                resultLines.add(afterLines[afterIdx++])
            }

            for (line in hunk.lines) {
                when (line.type) {
                    LineType.CONTEXT -> {
                        resultLines.add(line.content)
                        afterIdx++
                    }
                    LineType.REMOVE -> resultLines.add(line.content)
                    LineType.ADD -> afterIdx++
                }
            }
        }

        // Copy any remaining after-lines after the last hunk.
        while (afterIdx < afterLines.size) {
            resultLines.add(afterLines[afterIdx++])
        }

        val trailingNewline = determineBeforeTrailingNewline(gitDiff, afterContent)
        return joinResult(resultLines, trailingNewline)
    }

    private fun splitLines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        return if (lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private fun joinResult(lines: List<String>, trailingNewline: Boolean): String =
        if (trailingNewline) lines.joinToString("\n") + "\n" else lines.joinToString("\n")

    private fun parseHunks(gitDiff: String): List<Hunk> {
        val hunks = mutableListOf<Hunk>()
        var currentLines = mutableListOf<HunkLine>()
        var oldStart = 0
        var oldCount = 0
        var newStart = 0
        var newCount = 0
        var inHunk = false

        for (line in gitDiff.lines()) {
            val match = HUNK_HEADER.find(line)
            if (match != null) {
                if (inHunk) {
                    hunks.add(Hunk(oldStart, oldCount, newStart, newCount, currentLines))
                }
                currentLines = mutableListOf()
                oldStart = match.groupValues[2].toInt()
                oldCount = match.groupValues[3].let { if (it.isEmpty()) 1 else it.toInt() }
                newStart = match.groupValues[4].toInt()
                newCount = match.groupValues[5].let { if (it.isEmpty()) 1 else it.toInt() }
                inHunk = true
                continue
            }
            if (!inHunk) continue
            when {
                line.startsWith(' ') -> currentLines.add(HunkLine(LineType.CONTEXT, line.substring(1)))
                line.startsWith('-') -> currentLines.add(HunkLine(LineType.REMOVE, line.substring(1)))
                line.startsWith('+') -> currentLines.add(HunkLine(LineType.ADD, line.substring(1)))
            }
        }
        if (inHunk) {
            hunks.add(Hunk(oldStart, oldCount, newStart, newCount, currentLines))
        }
        return hunks
    }

    /**
     * Determine whether the "before" content has a trailing newline, given the diff
     * and the "after" content.
     */
    private fun determineBeforeTrailingNewline(gitDiff: String, afterContent: String): Boolean {
        // Absence of "No newline" marker means both before and after end with \n.
        if ("No newline at end of file" !in gitDiff) return true
        // If a "No newline" marker follows a '-' or context line, before lacks trailing \n.
        val lines = gitDiff.lines()
        for (i in lines.indices) {
            if (!lines[i].startsWith('\\')) continue
            val prev = lines.getOrNull(i - 1) ?: continue
            if (prev.startsWith('-') || prev.startsWith(' ')) return false
        }
        // Marker only follows '+' lines — before has trailing \n, after does not.
        return true
    }
}
