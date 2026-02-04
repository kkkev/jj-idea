package `in`.kkkev.jjidea.jj

import com.intellij.vcs.log.VcsUser
import com.intellij.vcs.log.impl.VcsUserImpl
import `in`.kkkev.jjidea.ui.append
import `in`.kkkev.jjidea.ui.buildText
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
    /**
     * Get a tooltip-friendly display of this annotation
     */
    fun getTooltip(): String = buildText {
        append("Change: ")
        append(id.full)
        append("\n")

        append("Commit: ")
        append(commitId.full)
        append("\n")

        append("Author: ${author.name}")
        if (author.email.isNotEmpty()) {
            append(" <${author.email}>")
        }
        append("\n")
        append(description)
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
