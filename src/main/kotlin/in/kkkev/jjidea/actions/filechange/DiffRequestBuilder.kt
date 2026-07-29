package `in`.kkkev.jjidea.actions.filechange

import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.diffRequest
import `in`.kkkev.jjidea.jj.fileAt
import `in`.kkkev.jjidea.vcs.fileAtVersion
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import `in`.kkkev.jjidea.vcs.possibleLogEntryFor

/**
 * Convert this [FileChange] to a platform [Change], resolving each side via [repo] the same way
 * [buildDiffRequests] resolves diff sides. The working-copy side comes back as an editable
 * `CurrentContentRevision` (see [JujutsuRepository.createContentRevision]).
 */
fun FileChange.toChange(repo: JujutsuRepository): Change =
    Change(before?.let(repo::createContentRevision), after?.let(repo::createContentRevision))

/**
 * [Change]-producing counterpart to [buildDiffRequests], for callers that need a
 * [com.intellij.openapi.vcs.changes.ui.ChangesTree]-backed view (e.g. a Changes pane) rather than
 * a bare diff chain. Same three branches, same complexity (`O(files)`, no filesystem traversal).
 */
fun buildChanges(
    project: Project,
    changes: List<FileChange>,
    files: List<VirtualFile>
): List<Change> = if (changes.isNotEmpty()) {
    changes.map { change ->
        val repo = project.jujutsuRepositoryFor(change.filePath)
        change.toChange(repo)
    }
} else if (files.isNotEmpty()) {
    val filesByLogEntry = files
        .mapNotNull { file -> project.possibleLogEntryFor(file)?.let { it to file } }
        .groupBy({ it.first }, { it.second })
    val changesByLogEntry = filesByLogEntry.keys.associateWith { entry ->
        entry.repo.logService.getFileChanges(entry).getOrNull()
            ?.filter { it.after != null }
            ?.associateBy { it.after!!.filePath }
            ?: emptyMap()
    }
    filesByLogEntry.flatMap { (logEntry, groupFiles) ->
        val changesByPath = changesByLogEntry[logEntry] ?: emptyMap()
        groupFiles.flatMap { file ->
            val repo = logEntry.repo
            if (file.isDirectory) {
                changesByPath.filter { (path, _) -> path.isUnder(file.filePath, false) }
                    .values
                    .map { change -> change.toChange(repo) }
            } else {
                val change = changesByPath[file.filePath]
                val before = change?.before ?: file.filePath.fileAt(logEntry.parentContentLocator)
                val after = change?.after ?: file.fileAtVersion
                listOf(
                    Change(
                        repo.createContentRevision(before),
                        repo.createContentRevision(after)
                    )
                )
            }
        }
    }
} else {
    emptyList()
}

fun buildDiffRequests(
    project: Project,
    changes: List<FileChange>,
    files: List<VirtualFile>
): List<DiffRequest> = if (changes.isNotEmpty()) {
    changes.map { change ->
        val repo = project.jujutsuRepositoryFor(change.filePath)
        diffRequest(
            change.filePath.name,
            repo.createDiffSideFor(change.before),
            repo.createDiffSideFor(change.after)
        )
    }
} else if (files.isNotEmpty()) {
    val filesByLogEntry = files
        .mapNotNull { file -> project.possibleLogEntryFor(file)?.let { it to file } }
        .groupBy({ it.first }, { it.second })
    val changesByLogEntry = filesByLogEntry.keys.associateWith { entry ->
        entry.repo.logService.getFileChanges(entry).getOrNull()
            ?.filter { it.after != null }
            ?.associateBy { it.after!!.filePath }
            ?: emptyMap()
    }
    filesByLogEntry.flatMap { (logEntry, groupFiles) ->
        val changesByPath = changesByLogEntry[logEntry] ?: emptyMap()
        groupFiles.flatMap { file ->
            val repo = logEntry.repo
            if (file.isDirectory) {
                changesByPath.filter { (path, _) -> path.isUnder(file.filePath, false) }
                    .values
                    .map { change ->
                        diffRequest(
                            change.filePath.name,
                            repo.createDiffSideFor(change.before),
                            repo.createDiffSideFor(change.after)
                        )
                    }
            } else {
                val change = changesByPath[file.filePath]
                val before = change?.before ?: file.filePath.fileAt(logEntry.parentContentLocator)
                val after = change?.after ?: file.fileAtVersion
                listOf(
                    diffRequest(
                        file.name,
                        repo.createDiffSideFor(before),
                        repo.createDiffSideFor(after)
                    )
                )
            }
        }
    }
} else {
    emptyList()
}
