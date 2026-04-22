package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.MergeParentOf
import `in`.kkkev.jjidea.jj.Revision

/**
 * Revision number for the auto-merged parent tree of a merge commit.
 * Carries the child revision so [JujutsuDiffProvider] can reconstruct parent content
 * via reverse-apply when IntelliJ calls back with this revision number.
 */
data class JujutsuMergeParentRevisionNumber(val childRevision: Revision) : VcsRevisionNumber {
    val contentLocator = MergeParentOf(childRevision)

    override fun asString() = contentLocator.title
    override fun compareTo(other: VcsRevisionNumber?) = 0
}

/**
 * Revision number implementation for Jujutsu that supports both full and short display formats
 */
data class JujutsuRevisionNumber(val changeId: ChangeId) : ShortVcsRevisionNumber {
    /**
     * Returns string form of the revision, typically for displaying in an editor tab when viewing a historical version
     * of a fle.
     */
    override fun asString() = changeId.toString()

    override fun toShortString() = changeId.short

    /**
     * Compares revision numbers by comparing the underlying revisions. Used when building changelists. Ordering doesn't
     * matter - just equality.
     */
    override fun compareTo(other: VcsRevisionNumber?) = if (other !is JujutsuRevisionNumber) {
        0
    } else {
        changeId.toString().compareTo(other.changeId.toString())
    }
}
