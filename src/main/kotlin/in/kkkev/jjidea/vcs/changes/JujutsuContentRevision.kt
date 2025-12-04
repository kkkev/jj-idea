package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.jj.ChangeId

/**
 * Simple revision number implementation for jujutsu
 *
 * TODO: Does this need specific sequencing? Could it hold refs such as @ or bookmark names?
 */
class JujutsuRevisionNumber(private val revision: String) : VcsRevisionNumber {
    constructor(changeId: ChangeId) : this(changeId.full)

    override fun asString(): String = revision

    override fun compareTo(other: VcsRevisionNumber?) = if (other !is JujutsuRevisionNumber)
        0
    else
        revision.compareTo(other.revision)
}
