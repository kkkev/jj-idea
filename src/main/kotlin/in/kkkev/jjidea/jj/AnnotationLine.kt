package `in`.kkkev.jjidea.jj

import com.intellij.vcs.log.VcsUser
import com.intellij.vcs.log.impl.VcsUserImpl
import `in`.kkkev.jjidea.ui.DescriptionRenderer
import kotlinx.datetime.Instant

/**
 * Represents a single line of annotation (blame) information from jj file annotate
 */
data class AnnotationLine(
    val changeId: ChangeId,
    val commitId: String,
    val author: VcsUser,
    val authorTimestamp: Instant?,
    val descriptionFirstLine: String,
    val lineContent: String,
    val lineNumber: Int
) {
    /**
     * Get a tooltip-friendly display of this annotation
     */
    fun getTooltip(): String = buildString {
        append("Change: ${changeId.display}\n")
        append("Commit: ${commitId.take(12)}\n")
        append("Author: ${author.name}")
        if (author.email.isNotEmpty()) {
            append(" <${author.email}>")
        }
        append("\n")
        val displayText = DescriptionRenderer.toDisplayText(descriptionFirstLine)
        if (descriptionFirstLine.isNotEmpty()) {
            append("Description: $displayText")
        } else {
            append(displayText)
        }
    }

    companion object {
        /**
         * Create an empty/null annotation line for lines with no annotation data
         */
        fun empty(lineNumber: Int, lineContent: String) = AnnotationLine(
            changeId = ChangeId(""),
            commitId = "",
            author = VcsUserImpl("", ""),
            authorTimestamp = null,
            descriptionFirstLine = "",
            lineContent = lineContent,
            lineNumber = lineNumber
        )
    }
}
