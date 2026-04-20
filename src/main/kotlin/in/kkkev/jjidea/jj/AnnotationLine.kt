package `in`.kkkev.jjidea.jj

import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.ui.components.appendChangeTooltip
import `in`.kkkev.jjidea.ui.components.htmlString
import kotlinx.datetime.Instant

/**
 * Represents a single line of annotation (blame) information from jj file annotate
 */
data class AnnotationLine(
    override val id: ChangeId,
    override val commitId: CommitId,
    override val author: VcsUser,
    override val authorTimestamp: Instant?,
    override val description: Description,
    val lineContent: String,
    val lineNumber: Int
) : ChangeDetail {
    fun getHtmlTooltip(): String = htmlString { appendChangeTooltip(this@AnnotationLine) }
}
