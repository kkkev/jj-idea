package `in`.kkkev.jjidea.jj

import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.ui.components.DateTimeFormatter
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.appendSummary
import `in`.kkkev.jjidea.ui.components.htmlString
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import kotlinx.datetime.Instant

/**
 * Represents a single line of annotation (blame) information from jj file annotate
 */
data class AnnotationLine(
    val id: ChangeId,
    val commitId: CommitId,
    val author: VcsUser,
    val authorTimestamp: Instant?,
    val description: Description,
    val lineContent: String,
    val lineNumber: Int
) {
    fun getHtmlTooltip(): String = htmlString {
        append(id)
        append(" (")
        append(commitId)
        append(")\n")
        append(author)
        authorTimestamp?.let { ts ->
            append(" \u00b7 ")
            append(DateTimeFormatter.formatAbsolute(ts))
        }
        control("<pre style='white-space: pre-wrap;'>") { appendSummary(description) }
    }

    companion object {
        /**
         * Create an empty/null annotation line for lines with no annotation data
         */
        fun empty(lineNumber: Int, lineContent: String) = AnnotationLine(
            id = ChangeId.EMPTY,
            commitId = CommitId(""),
            author = VcsUserImpl("", ""),
            authorTimestamp = null,
            description = Description.EMPTY,
            lineContent = lineContent,
            lineNumber = lineNumber
        )
    }
}
