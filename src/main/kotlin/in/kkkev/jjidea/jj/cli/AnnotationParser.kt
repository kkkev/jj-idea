package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import kotlinx.datetime.Instant

/**
 * Parses jj file annotate output into structured annotation lines
 * Uses null byte (\0) as field separator to handle descriptions with special characters
 *
 * Expected template format:
 * change_id ++ "\0" ++ change_id.shortest() ++ "\0" ++ commit_id ++ "\0" ++
 * author.name() ++ "\0" ++ author.email() ++ "\0" ++ description.first_line() ++ "\0" ++ line
 */
object AnnotationParser {
    private const val FIELD_SEPARATOR = "\u0000" // Null byte
    private const val PARENT_SEPARATOR = ","
    private const val FIELDS_PER_LINE = 11

    /**
     * Template to use with `jj file annotate -T`
     * Outputs fields per line separated by null bytes:
     * 1. full change id
     * 2. short change id
     * 3. divergence offset
     * 4. full commit hash
     * 5. short commit hash
     * 6. author name
     * 7. author email
     * 8. author timestamp
     * 9. description first line
     * 10. comma-separated parent change ids
     * 11. line content
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
        commit.description() ++ "\0" ++
        commit.parents().map(|c| c.change_id()).join(",") ++ "\0" ++
        content ++ "\0"
        """.trimIndent().replace("\n", "")

    /**
     * Parse jj file annotate output into annotation lines
     * @param annotateOutput Raw output from `jj file annotate -T <template>`
     * @return List of annotation lines with metadata
     */
    fun parse(annotateOutput: String): List<AnnotationLine> {
        val trimmed = annotateOutput.trim()
        if (trimmed.isBlank()) return emptyList()

        // Split entire output on null bytes
        val fields = trimmed.split(FIELD_SEPARATOR)

        // Group into chunks of n fields per line
        return fields
            .chunked(FIELDS_PER_LINE)
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
        val description = Description(chunk[8])
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
}
