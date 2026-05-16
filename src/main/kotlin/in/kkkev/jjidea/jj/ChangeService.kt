package `in`.kkkev.jjidea.jj

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.committed.CommittedChangesTreeBrowser
import `in`.kkkev.jjidea.vcs.getChildPath

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
        val fileChanges = repo.logService.getFileChanges(entry).getOrElse { error ->
            // This can happen when a commit is removed during loading (e.g., abandon, empty commit auto-removed).
            // Log at info level since this is an expected scenario, not a programming error.
            log.info("Error loading changes for ${entry.id}: ${error.message}")
            emptyList()
        }
        // entry.hasConflict is populated from the jj log template and is more accurate than
        // checking fileChanges.isNotEmpty(): jj diff --summary silently omits conflicted files.
        val conflictedPaths = if (entry.hasConflict) conflictedPathsFor(entry) else emptySet()
        val fileChangePaths = fileChanges.map { it.filePath.path.removePrefix(repo.directory.path + "/") }.toSet()
        val regularChanges = fileChanges.map { fileChange ->
            val beforeContentRevision = fileChange.before?.let { before ->
                fileChange.after?.contentLocator?.let(repo::getLogEntry)?.parentContentLocator
                    ?.let { parentContentLocator -> repo.createContentRevision(before.filePath, parentContentLocator) }
                    ?: repo.createContentRevision(before)
            }
            val afterContentRevision = fileChange.after?.let { after -> repo.createContentRevision(after) }
            val relativePath = fileChange.filePath.path.removePrefix(repo.directory.path + "/")
            if (relativePath in conflictedPaths) {
                Change(beforeContentRevision, afterContentRevision, FileStatus.MERGED_WITH_CONFLICTS)
            } else {
                Change(beforeContentRevision, afterContentRevision)
            }
        }
        // jj diff --summary omits conflicted files; synthesise Change objects for them explicitly.
        val missingConflictChanges = (conflictedPaths - fileChangePaths).map { relativePath ->
            val filePath = repo.directory.getChildPath(relativePath)
            Change(
                repo.createContentRevision(filePath, entry.parentContentLocator),
                repo.createContentRevision(filePath, entry.id),
                FileStatus.MERGED_WITH_CONFLICTS
            )
        }
        return regularChanges + missingConflictChanges
    }

    private fun conflictedPathsFor(entry: LogEntry): Set<String> {
        val result = entry.repo.commandExecutor.resolveList(entry.id)
        if (!result.isSuccess || result.stdout.isBlank()) return emptySet()
        return result.stdout.lines()
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { p -> p.isNotEmpty() } }
            .toSet()
    }
}
