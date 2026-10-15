package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.ContentLocator
import `in`.kkkev.jjidea.jj.MergeParentOf
import `in`.kkkev.jjidea.jj.Revision

interface JujutsuRevisionNumber : ShortVcsRevisionNumber {
    val contentLocator: ContentLocator
    override fun toShortString() = contentLocator.short
}

val VcsRevisionNumber.contentLocator get() = (this as? JujutsuRevisionNumber)?.contentLocator ?: ContentLocator.Empty

/**
 * Revision number for the auto-merged parent tree of a merge commit.
 * Carries the child revision so [in.kkkev.jjidea.vcs.diff.JujutsuDiffProvider] can reconstruct parent content
 * via reverse-apply when IntelliJ calls back with this revision number.
 */
data class MergeParentRevisionNumber(val childRevision: Revision) : JujutsuRevisionNumber {
    override val contentLocator = MergeParentOf(childRevision)

    override fun asString() = contentLocator.title
    override fun compareTo(other: VcsRevisionNumber?) = 0
}

/** Revision number for a specific commit, e.g. a hidden one that a change id would resolve away from. */
data class CommitIdRevisionNumber(val commitId: CommitId) : JujutsuRevisionNumber {
    override val contentLocator get() = commitId

    override fun asString() = commitId.short

    /** Equality only: ordering is meaningless for commit ids (see [ChangeIdRevisionNumber.compareTo]). */
    override fun compareTo(other: VcsRevisionNumber?) = when {
        other !is CommitIdRevisionNumber -> 0
        commitId == other.commitId -> 0
        else -> -1
    }
}

/**
 * Revision number implementation for Jujutsu change ids that supports both full and short display formats.
 */
data class ChangeIdRevisionNumber(val changeId: ChangeId) : JujutsuRevisionNumber {
    override val contentLocator get() = changeId

    /**
     * Returns string form of the revision, typically for displaying in an editor tab when viewing a historical version
     * of a fle.
     */
    override fun asString() = changeId.toString()

    /**
     * Compares revision numbers by equality only — ordering is meaningless for change IDs.
     * Lexicographic comparison of change ID strings would cause IntelliJ to unpredictably invert
     * diff sides in DiffActionExecutor.
     */
    override fun compareTo(other: VcsRevisionNumber?) = when {
        other !is ChangeIdRevisionNumber -> 0
        changeId == other.changeId -> 0
        else -> -1
    }
}
