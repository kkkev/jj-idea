package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.jj.Revision

/**
 * Revision number implementation for Jujutsu that supports both full and short display formats
 */
data class JujutsuRevisionNumber(val revision: Revision) : ShortVcsRevisionNumber {
    /**
     * Returns string form of the revision, typically for displaying in an editor tab when viewing a historical version
     * of a fle.
     */
    override fun asString() = revision.toString()

    override fun toShortString() = revision.short

    /**
     * Compares revision numbers by comparing the underlying revisions. Used when building changelists. Ordering doesn't
     * matter - just equality.
     */
    override fun compareTo(other: VcsRevisionNumber?) = if (other !is JujutsuRevisionNumber) {
        0
    } else {
        revision.toString().compareTo(other.revision.toString())
    }
}
