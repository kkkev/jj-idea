package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber

/**
 * A [ContentRevision] that additionally provides access to a [LogEntry], allowing lookup of extra log information about
 * historical revisions.
 */
interface ContentLogEntry : ContentRevision {
    val filePath: FilePath
    val logEntry: LogEntry

    override fun getFile() = filePath

    override fun getRevisionNumber() = JujutsuRevisionNumber(logEntry.id)
}
