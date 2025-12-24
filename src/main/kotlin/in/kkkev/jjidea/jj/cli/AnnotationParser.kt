package `in`.kkkev.jjidea.jj.cli

import com.intellij.vcs.log.impl.VcsUserImpl
import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Description
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
    private const val FIELDS_PER_LINE = 8

    /**
     * Template to use with `jj file annotate -T`
     * Outputs 7 fields per line separated by null bytes:
     * 1. full commit hash
     * 2. short commit hash
     * 3. commit hash (duplicate for compatibility)
     * 4. author name
     * 5. author email
     * 6. description first line
     * 7. line content
     *
     * Note: In annotate context, use `commit` instead of `commit_id`
     */
    val TEMPLATE = """
        commit.change_id() ++ "\0" ++
        commit.change_id().shortest() ++ "\0" ++
        commit.commit_id() ++ "\0" ++
        commit.author().name() ++ "\0" ++
        commit.author().email() ++ "\0" ++
        commit.author().timestamp().utc().format("%s") ++ "\0" ++
        commit.description() ++ "\0" ++
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
        return fields.chunked(FIELDS_PER_LINE)
            .filter { it.size == FIELDS_PER_LINE }  // Only complete lines
            .mapIndexed { index, chunk ->
                parseAnnotationLine(chunk, index + 1)
            }
    }

    /**
     * Parse a single annotation line from chunked fields
     * @param chunk Array of 7 fields: [fullChangeId, shortChangeId, commitId, authorName, authorEmail, descFirstLine, lineContent]
     * @param lineNumber Line number (1-indexed)
     * @return Parsed annotation line
     */
    private fun parseAnnotationLine(chunk: List<String>, lineNumber: Int): AnnotationLine {
        require(chunk.size == FIELDS_PER_LINE) {
            "Invalid annotation line: expected $FIELDS_PER_LINE fields, got ${chunk.size}"
        }

        val fullChangeId = chunk[0]
        val shortChangeId = chunk[1]
        val commitId = chunk[2]
        val authorName = chunk[3]
        val authorEmail = chunk[4]
        val authorTimestamp = chunk[5].toLongOrNull()?.let(Instant::fromEpochSeconds)
        val description = Description(chunk[6])
        val lineContent = chunk[7]

        return AnnotationLine(
            changeId = ChangeId(fullChangeId, shortChangeId),
            commitId = commitId,
            author = VcsUserImpl(authorName, authorEmail),
            authorTimestamp = authorTimestamp,
            description = description,
            lineContent = lineContent,
            lineNumber = lineNumber,
        )
    }
}
