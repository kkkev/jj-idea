package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.*
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.getChildPath
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

/**
 * Provides change information for jujutsu working copy
 */
class JujutsuChangeProvider(private val vcs: JujutsuVcs) : ChangeProvider {
    private val log = Logger.getInstance(javaClass)

    override fun getChanges(
        dirtyScope: VcsDirtyScope,
        builder: ChangelistBuilder,
        progress: ProgressIndicator,
        addGate: ChangeListManagerGate
    ) {
        log.info("getChanges called on ${Thread.currentThread().name}, ${dirtyScope.affectedContentRoots.size} roots")

        dirtyScope.affectedContentRoots.mapNotNull { vcs.project.possibleJujutsuRepositoryFor(it) }.toSet()
            .forEach { repo ->
                try {
                    val startTime = System.currentTimeMillis()
                    val result = repo.commandExecutor.status()
                    log.info("jj status for $repo took ${System.currentTimeMillis() - startTime}ms")

                    if (!result.isSuccess) {
                        log.warn("Failed to get jj status for $repo: ${result.stderr}")
                        return@forEach // Continue to next root instead of returning from entire method
                    }

                    val conflictedPaths = if (workingCopyInConflict(result.stdout)) {
                        val resolveResult = repo.commandExecutor.resolveList()
                        if (resolveResult.isSuccess) {
                            parseConflictPaths(resolveResult.stdout)
                        } else {
                            collectConflictPathsFromStatus(result.stdout)
                        }
                    } else {
                        emptySet()
                    }

                    parseStatus(result.stdout, repo, builder, conflictedPaths)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Error getting changes for $repo: ${e.message}")
                }
            }
    }

    override fun isModifiedDocumentTrackingRequired() = true

    /**
     * Parse jj status output and add changes to the builder.
     *
     * jj status output format:
     * Working copy changes:
     * M file1.txt
     * A file2.txt
     * D file3.txt
     * R foo/bar/{baz/file4.txt => newbaz/newfile4.txt}
     *
     * @param conflictedPaths Explicit set of conflicted paths. When null, derived from the status
     *   warning section (used by tests that call parseStatus directly).
     */
    fun parseStatus(
        output: String,
        repo: JujutsuRepository,
        builder: ChangelistBuilder,
        conflictedPaths: Set<String> = if (workingCopyInConflict(
                output
            )
        ) {
            collectConflictPathsFromStatus(output)
        } else {
            emptySet()
        }
    ) {
        val lines = output.lines()
        val addedConflictPaths = mutableSetOf<String>()
        var inWorkingCopy = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Working copy changes:")) {
                inWorkingCopy = true
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("Warning:")) {
                inWorkingCopy = false
                continue
            }
            if (inWorkingCopy) {
                if (trimmed.startsWith("Working copy")) {
                    inWorkingCopy = false
                } else {
                    val filePath = if (trimmed.length >= 3) trimmed.substring(2).trim() else null
                    if (filePath != null && filePath in conflictedPaths) {
                        addConflictedChange(repo.directory.getChildPath(filePath), repo, builder)
                        addedConflictPaths.add(filePath)
                    } else {
                        parseStatusLine(trimmed, repo, builder)
                    }
                }
            }
        }

        for (path in conflictedPaths - addedConflictPaths) {
            addConflictedChange(repo.directory.getChildPath(path), repo, builder)
        }
    }

    private fun workingCopyInConflict(statusOutput: String) = statusOutput.lines().any {
        val t = it.trim()
        t.startsWith("Working copy") && t.contains("(conflict)")
    }

    private fun collectConflictPathsFromStatus(statusOutput: String): Set<String> {
        val result = mutableSetOf<String>()
        var inSection = false
        for (line in statusOutput.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Warning: There are unresolved conflicts at these paths:") -> inSection = true
                trimmed.startsWith("Warning:") || trimmed.isEmpty() -> inSection = false
                inSection -> trimmed.split(Regex("\\s+")).firstOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { result.add(it) }
            }
        }
        return result
    }

    /**
     * Parses `jj resolve -l` output. Each line is "<path>  <description>" with any amount
     * of whitespace between path and description.
     */
    internal fun parseConflictPaths(output: String): Set<String> =
        output.lines()
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { p -> p.isNotEmpty() } }
            .toSet()

    /**
     * Parse a single status line
     * Format: "M path/to/file.txt" or "A path/to/file.txt", etc.
     */
    private fun parseStatusLine(line: String, repo: JujutsuRepository, builder: ChangelistBuilder) {
        if (line.length < 3) return

        val status = line[0]
        val filePath = line.substring(2).trim()

        if (filePath.isEmpty()) return

        val path = repo.directory.getChildPath(filePath)

        when (status) {
            'M' -> addModifiedChange(path, repo, builder)
            'A' -> addAddedChange(path, builder)
            'D' -> addDeletedChange(path, repo, builder)
            'R' -> addRenamedChange(filePath, repo, builder)
            'C' -> addConflictedChange(path, repo, builder)
            else -> log.debug("Unknown status '$status' for file: $filePath")
        }
    }

    private fun addModifiedChange(path: FilePath, repo: JujutsuRepository, builder: ChangelistBuilder) {
        val beforeRevision = repo.createContentRevision(path, repo.workingCopy.parentContentLocator)
        val afterRevision = CurrentContentRevision(path)
        builder.processChange(Change(beforeRevision, afterRevision, FileStatus.MODIFIED), vcs.keyInstanceMethod)
    }

    private fun addAddedChange(path: FilePath, builder: ChangelistBuilder) {
        val afterRevision = CurrentContentRevision(path)
        builder.processChange(Change(null, afterRevision, FileStatus.ADDED), vcs.keyInstanceMethod)
    }

    private fun addDeletedChange(path: FilePath, repo: JujutsuRepository, builder: ChangelistBuilder) {
        val beforeRevision = repo.createContentRevision(path, repo.workingCopy.parentContentLocator)
        builder.processChange(Change(beforeRevision, null, FileStatus.DELETED), vcs.keyInstanceMethod)
    }

    private fun addConflictedChange(path: FilePath, repo: JujutsuRepository, builder: ChangelistBuilder) {
        val beforeRevision = repo.createContentRevision(path, repo.workingCopy.parentContentLocator)
        val afterRevision = CurrentContentRevision(path)
        builder.processChange(
            Change(beforeRevision, afterRevision, FileStatus.MERGED_WITH_CONFLICTS),
            vcs.keyInstanceMethod
        )
    }

    /**
     * Parse rename status and add change
     * Format: "{oldname => newname}" or "prefix{oldname => newname}"
     */
    private fun addRenamedChange(renameSpec: String, repo: JujutsuRepository, builder: ChangelistBuilder) {
        val (prefix, before, after, suffix) = requireNotNull(
            Regex("([^{)]*)\\{([^}]+) => ([^}]+)}(.*)").find(renameSpec)
        ) {
            "Invalid rename format: $renameSpec"
        }.destructured

        val oldPath = prefix + before + suffix
        val newPath = prefix + after + suffix

        log.info("Detected rename: $oldPath => $newPath")

        val beforePath = repo.directory.getChildPath(oldPath)
        val afterPath = repo.directory.getChildPath(newPath)

        val beforeRevision = repo.createContentRevision(beforePath, repo.workingCopy.parentContentLocator)
        val afterRevision = CurrentContentRevision(afterPath)

        val change = Change(beforeRevision, afterRevision, FileStatus.MODIFIED)
        change.isIsReplaced = true

        builder.processChange(change, vcs.keyInstanceMethod)
    }
}
