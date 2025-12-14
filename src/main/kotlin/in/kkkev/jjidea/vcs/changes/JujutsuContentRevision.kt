package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Revision

/**
 * Revision number implementation for Jujutsu that supports both full and short display formats
 */
class JujutsuRevisionNumber(
    private val fullRevision: String,
    private val shortRevision: String? = null
) : ShortVcsRevisionNumber {

    constructor(changeId: ChangeId) : this(changeId.full, changeId.short)
    constructor(revision: Revision) : this(revision.toString(), null)

    override fun asString(): String = fullRevision

    override fun toShortString(): String = shortRevision ?: fullRevision

    override fun equals(other: Any?) = when {
        other !is JujutsuRevisionNumber -> false
        this.fullRevision == other.fullRevision -> true
        else -> false
    }

    override fun hashCode() = fullRevision.hashCode()

    override fun compareTo(other: VcsRevisionNumber?) = if (other !is JujutsuRevisionNumber)
        0
    else
        fullRevision.compareTo(other.fullRevision)
}
