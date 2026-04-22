package `in`.kkkev.jjidea.jj

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.committed.CommittedChangesTreeBrowser

/**
 * Service for accessing changes associated with a log entry.
 */
object ChangeService {
    private val log = Logger.getInstance(ChangeService::class.java)

    fun loadChanges(entries: List<LogEntry>): List<Change> = if (entries.size == 1) {
        loadChanges(entries.single())
    } else {
        // Collect all changes in chronological order (oldest first = reversed selection order)
        val allChanges = entries.reversed().flatMap { loadChanges(it) }
        CommittedChangesTreeBrowser.zipChanges(allChanges)
    }

    fun loadChanges(entry: LogEntry): List<Change> {
        val repo = entry.repo

        return repo.logService.getFileChanges(entry).getOrElse { error ->
            // This can happen when a commit is removed during loading (e.g., abandon, empty commit auto-removed).
            // Log at info level since this is an expected scenario, not a programming error.
            log.info("Error loading changes for ${entry.id}: ${error.message}")
            emptyList()
        }.map { fileChange ->
            val beforeContentRevision = fileChange.before?.let { before ->
                (fileChange.after?.contentLocator as? ChangeId)?.let { afterChangeId ->
                    repo.createContentRevision(before.filePath, repo.getLogEntry(afterChangeId).parentContentLocator)
                } ?: repo.createContentRevision(before)
            }
            val afterContentRevision =
                fileChange.after?.let { after -> repo.createContentRevision(after) }
            Change(beforeContentRevision, afterContentRevision)
        }
    }
}
