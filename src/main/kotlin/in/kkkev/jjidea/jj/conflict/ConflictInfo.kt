package `in`.kkkev.jjidea.jj.conflict

/**
 * Structured form of one line of `jj resolve --list` output: a conflicted path plus the
 * shape of its conflict (how many sides, how many of them are deletions).
 */
data class ConflictInfo(
    val path: String,
    val sides: Int,
    val deletions: Int,
    val description: String
) {
    val isModifyDelete get() = sides == 2 && deletions == 1
    val isContentOnly get() = sides == 2 && deletions == 0
}

/**
 * Parses `jj resolve --list` output. Each line is "<path>  <description>", separated by a run
 * of 2+ spaces (verified against real `jj resolve --list` output, which uses 4 spaces).
 */
object ConflictInfoParser {
    // jj right-pads the path column to align descriptions, so the separator can be as short as
    // a single space for the longest path in the listing - the shape text itself is the only
    // reliable anchor, not the run of whitespace before it.
    private val shapeRegex = Regex("""(\d+)-sided conflict(?: including (\d+) deletions?)?""")
    private val whitespace = Regex("\\s+")

    fun parseLine(line: String): ConflictInfo? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val match = shapeRegex.find(trimmed)
        if (match != null) {
            val path = trimmed.substring(0, match.range.first).trim()
            if (path.isEmpty()) return null
            val description = trimmed.substring(match.range.first)
            val sides = match.groupValues[1].toIntOrNull() ?: 0
            val deletions = match.groupValues[2].toIntOrNull() ?: 0
            return ConflictInfo(path, sides, deletions, description)
        }
        val parts = trimmed.split(whitespace, limit = 2)
        val path = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
        return ConflictInfo(path, sides = 0, deletions = 0, description = parts.getOrNull(1) ?: "")
    }

    fun parse(stdout: String): Map<String, ConflictInfo> =
        stdout.lines().mapNotNull(::parseLine).associateBy { it.path }
}
