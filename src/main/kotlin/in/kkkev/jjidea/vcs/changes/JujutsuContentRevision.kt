package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Revision

/**
 * Simple revision number implementation for jujutsu
 *
 * TODO: Does this represent a revision? change id? Could it contain a hash? How does it fit with revset, revision etc.?
 */
class JujutsuRevisionNumber(private val revision: String) : VcsRevisionNumber {
    constructor(changeId: ChangeId) : this(changeId.full)
    constructor(revision: Revision) : this(revision.toString())

    override fun asString(): String = revision

    override fun compareTo(other: VcsRevisionNumber?) = if (other !is JujutsuRevisionNumber)
        0
    else
        revision.compareTo(other.revision)
}
