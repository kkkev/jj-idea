package `in`.kkkev.jjidea.changes

import com.intellij.openapi.vcs.history.VcsRevisionNumber

/**
 * Simple revision number implementation for jujutsu
 */
class JujutsuRevisionNumber(private val revision: String) : VcsRevisionNumber {
    override fun asString(): String = revision

    override fun compareTo(other: VcsRevisionNumber?) = if (other !is JujutsuRevisionNumber)
        0
    else
        revision.compareTo(other.revision)
}
