package `in`.kkkev.jjidea.jj

import com.intellij.vcs.log.VcsUser
import kotlinx.datetime.Instant

/**
 * Common fields shared between LogEntry and AnnotationLine.
 * Used for tooltip rendering and lightweight revision representation.
 */
interface ChangeDetail {
    val id: ChangeId
    val commitId: CommitId
    val author: VcsUser?
    val authorTimestamp: Instant?
    val description: Description
}
