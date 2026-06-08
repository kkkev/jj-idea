package `in`.kkkev.jjidea.actions.filechange

import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.diffRequest
import `in`.kkkev.jjidea.jj.fileAt
import `in`.kkkev.jjidea.vcs.fileAtVersion
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import `in`.kkkev.jjidea.vcs.possibleLogEntryFor

fun openDiffChain(project: Project, requests: List<DiffRequest>, tabTitle: String) {
    val file = ChainDiffVirtualFile(SimpleDiffRequestChain(requests), tabTitle)
    FileEditorManager.getInstance(project).openFile(file, true)
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
