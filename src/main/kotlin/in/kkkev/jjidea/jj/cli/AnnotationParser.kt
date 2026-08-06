package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import kotlinx.datetime.Instant

/**
 * Parses jj file annotate output into structured annotation lines.
 * Uses null byte (\0) as the field separator within a record, and newline as the record
 * separator.
 *
 * The trailing `content` field is arbitrary file text and may itself contain a `\0` byte
 * (jj-idea-3191) — so records are split on newline first, and each record's fields are split
 * with a limit so any embedded `\0` in `content` doesn't shift later fields. Newline is safe as
 * the record separator because a single annotated source line's content can't contain one, and
 * the description is flattened onto one line via `escape_json()`.
 *
 * Expected template format:
 * change_id ++ "\0" ++ change_id.shortest() ++ "\0" ++ commit_id ++ "\0" ++
 * author.name() ++ "\0" ++ author.email() ++ "\0" ++ description.escape_json() ++ "\0" ++ line
 */
object AnnotationParser {
    private const val FIELD_SEPARATOR = "\u0000" // Null byte

    // Splits *after* each "\n" (lookbehind) so the separator stays attached to the end of the
    // preceding record instead of being consumed — see [parse].
    private val RECORD_SEPARATOR_KEEP_DELIMITER = Regex("(?<=\n)")
    private const val PARENT_SEPARATOR = ","
    private const val FIELDS_PER_LINE = 11

    /**
     * Template to use with `jj file annotate -T`
     * Outputs one record per source line, fields separated by null bytes, records separated by
     * the newline that `content` already ends with:
     * 1. full change id
     * 2. short change id
     * 3. divergence offset
     * 4. full commit hash
     * 5. short commit hash
     * 6. author name
     * 7. author email
     * 8. author timestamp
     * 9. description, JSON-escaped onto a single line (decoded by [unescapeJson])
     * 10. comma-separated parent change ids
     * 11. line content (may contain any byte, including \0 — must be split with a limit)
     *
     * Note: In annotate context, use `commit` instead of `commit_id`
     */
    val TEMPLATE =
        """
        commit.change_id() ++ "\0" ++
        commit.change_id().shortest() ++ "\0" ++
        if(commit.divergent(), commit.change_offset(), "") ++ "\0" ++
        commit.commit_id() ++ "\0" ++
        commit.commit_id().shortest() ++ "\0" ++
        commit.author().name() ++ "\0" ++
        commit.author().email() ++ "\0" ++
        commit.author().timestamp().utc().format("%s") ++ "\0" ++
        commit.description().escape_json() ++ "\0" ++
        commit.parents().map(|c| c.change_id()).join(",") ++ "\0" ++
        content
        """.trimIndent().replace("\n", "")

    /**
     * Parse jj file annotate output into annotation lines
     * @param annotateOutput Raw output from `jj file annotate -T <template>`
     * @return List of annotation lines with metadata
     */
    fun parse(annotateOutput: String): List<AnnotationLine> {
        if (annotateOutput.isEmpty()) return emptyList()

        // Split into one record per source line, keeping each record's trailing "\n" attached
        // (the lookbehind splits *after* the separator instead of consuming it) rather than
        // stripping it: `content` is expected downstream (e.g. MergeAnnotationReconciler) to
        // retain the line terminator, matching jj's own `content` keyword convention. The final
        // record separator produces a trailing empty element; drop it. Blank source lines are
        // non-empty records (their content field is empty but the preceding \0-delimited
        // metadata fields are still present).
        val records = annotateOutput.split(RECORD_SEPARATOR_KEEP_DELIMITER).dropLastWhile { it.isEmpty() }
        if (records.isEmpty()) return emptyList()

        return records
            // limit: keeps any \0 embedded in the trailing `content` field inside the last chunk
            // instead of splitting it into extra fields (jj-idea-3191).
            .map { it.split(FIELD_SEPARATOR, limit = FIELDS_PER_LINE) }
            .filter { it.size == FIELDS_PER_LINE } // Only complete lines
            .mapIndexed { index, chunk ->
                parseAnnotationLine(chunk, index + 1)
            }
    }

    /**
     * Parse a single annotation line from chunked fields
     * @param chunk Array of 11 fields: [fullChangeId, shortChangeId, changeOffset, fullCommitId, shortCommitId,
     *   authorName, authorEmail, authorTimestamp, description, parentIds, lineContent]
     * @param lineNumber Line number (1-indexed)
     * @return Parsed annotation line
     */
    private fun parseAnnotationLine(chunk: List<String>, lineNumber: Int): AnnotationLine {
        require(chunk.size == FIELDS_PER_LINE) {
            "Invalid annotation line: expected $FIELDS_PER_LINE fields, got ${chunk.size}"
        }

        val fullChangeId = chunk[0]
        val shortChangeId = chunk[1]
        val changeOffset = chunk[2]
        val fullCommitId = chunk[3]
        val shortCommitId = chunk[4]
        val authorName = chunk[5]
        val authorEmail = chunk[6]
        val authorTimestamp = chunk[7].toLongOrNull()?.let(Instant::fromEpochSeconds)
        val description = Description(unescapeJson(chunk[8]))
        val parentIds = chunk[9].split(PARENT_SEPARATOR).filter { it.isNotEmpty() }.map { ChangeId(it) }
        val lineContent = chunk[10]

        return AnnotationLine(
            id = ChangeId(fullChangeId, shortChangeId, changeOffset),
            commitId = CommitId(fullCommitId, shortCommitId),
            author = VcsUserImpl(authorName, authorEmail),
            authorTimestamp = authorTimestamp,
            description = description,
            parentIds = parentIds,
            lineContent = lineContent,
            lineNumber = lineNumber
        )
    }

    /**
     * Decode a JSON string literal produced by jj's `escape_json()` template method, e.g.
     * `"line1\nline2"` -> `line1` + newline + `line2`. Dependency-free since no JSON library is
     * on the plugin's compile classpath.
     */
    internal fun unescapeJson(jsonString: String): String {
        val inner = jsonString.removeSurrounding("\"")
        val result = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c != '\\' || i == inner.length - 1) {
                result.append(c)
                i++
                continue
            }
            when (val next = inner[i + 1]) {
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val hex = inner.substring(i + 2, minOf(i + 6, inner.length))
                    hex.toIntOrNull(16)?.let { result.append(it.toChar()) }
                    i += 4
                }
                else -> result.append(next)
            }
            i += 2
        }
        return result.toString()
    }
}
