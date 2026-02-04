package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.jj.Revision

/**
 * Revision number implementation for Jujutsu that supports both full and short display formats
 */
class JujutsuRevisionNumber(val revision: Revision) : ShortVcsRevisionNumber {
    /**
     * Returns hex format when used with VCS Log (Hash compatibility),
     * otherwise returns JJ change ID format
     */
    // TODO Where is this used? What happens if a QCI is passed?
    override fun asString() = revision.toString()

    override fun toShortString() = revision.short

    override fun equals(other: Any?) = when {
        other !is JujutsuRevisionNumber -> false
        this.revision == other.revision -> true
        else -> false
    }

    override fun hashCode() = revision.hashCode()

    // TODO What is the ordering assumption here?
    override fun compareTo(other: VcsRevisionNumber?) = if (other !is JujutsuRevisionNumber) {
        0
    } else {
        revision.toString().compareTo(other.revision.toString())
    }
}
