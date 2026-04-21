package `in`.kkkev.jjidea.util

/**
 * Reverse-applies a git unified diff to reconstruct "before" content from "after" content.
 *
 * A git diff describes how to go from before → after. Given the after content and the diff,
 * we can reconstruct the before content. This is used for merge commits where jj computes
 * an auto-merged parent tree that can't be fetched directly via `jj file show`, but whose
 * content can be derived from `jj diff --git -r <merge-rev> -- <file>`.
 */
object GitDiffReverseApplier {
    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    private enum class LineType { CONTEXT, ADD, REMOVE }
    private data class HunkLine(val type: LineType, val content: String)
    private data class Hunk(
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
        val beforeLines = mutableListOf<String>()
        var afterIdx = 0

        for (hunk in hunks) {
            val hunkStart = if (hunk.newCount == 0) 0 else hunk.newStart - 1

            while (afterIdx < hunkStart && afterIdx < afterLines.size) {
                beforeLines.add(afterLines[afterIdx++])
            }

            for (line in hunk.lines) {
                when (line.type) {
                    LineType.CONTEXT -> {
                        beforeLines.add(line.content)
                        afterIdx++
                    }
                    LineType.REMOVE -> beforeLines.add(line.content)
                    LineType.ADD -> afterIdx++
                }
            }
        }

        while (afterIdx < afterLines.size) {
            beforeLines.add(afterLines[afterIdx++])
        }

        val trailingNewline = determineBeforeTrailingNewline(gitDiff, afterContent)
        return if (trailingNewline) {
            beforeLines.joinToString("\n") + "\n"
        } else {
            beforeLines.joinToString("\n")
        }
    }

    private fun splitLines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        return if (lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private fun parseHunks(gitDiff: String): List<Hunk> {
        val hunks = mutableListOf<Hunk>()
        var currentLines = mutableListOf<HunkLine>()
        var newStart = 0
        var newCount = 0
        var inHunk = false

        for (line in gitDiff.lines()) {
            val match = HUNK_HEADER.find(line)
            if (match != null) {
                if (inHunk) hunks.add(Hunk(newStart, newCount, currentLines))
                currentLines = mutableListOf()
                newStart = match.groupValues[3].toInt()
                newCount = match.groupValues[4].let { if (it.isEmpty()) 1 else it.toInt() }
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
        if (inHunk) hunks.add(Hunk(newStart, newCount, currentLines))
        return hunks
    }

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
