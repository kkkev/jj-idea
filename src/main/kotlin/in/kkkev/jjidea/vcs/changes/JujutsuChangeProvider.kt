package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.*
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.ui.JujutsuNotifications
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.getChildPath
import `in`.kkkev.jjidea.vcs.jujutsuRepository

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

        dirtyScope.affectedContentRoots.map { it.jujutsuRepository }.forEach { repo ->
            // Handle uninitialized roots (e.g., .jj directory was deleted)
            if (!repo.isInitialised) {
                log.info("Root configured for Jujutsu but not initialized: $repo")
                JujutsuNotifications.notifyUninitializedRoot(vcs.project, repo)
                return@forEach
            }

            try {
                val startTime = System.currentTimeMillis()
                val result = repo.commandExecutor.status()
                log.info("jj status for $repo took ${System.currentTimeMillis() - startTime}ms")

                if (!result.isSuccess) {
                    log.warn("Failed to get jj status for $repo: ${result.stderr}")
                    return@forEach // Continue to next root instead of returning from entire method
                }

                parseStatus(result.stdout, repo, builder)
            } catch (e: Exception) {
                log.error("Error getting changes", e)
            }
        }
    }

    override fun isModifiedDocumentTrackingRequired() = true

    /**
     * Parse jj status output and add changes to the builder
     *
     * jj status output format:
     * Working copy changes:
     * M file1.txt
     * A file2.txt
     * D file3.txt
     * R foo/bar/{baz/file4.txt => newbaz/newfile4.txt}
     */
    fun parseStatus(output: String, repo: JujutsuRepository, builder: ChangelistBuilder) {
        val lines = output.lines()
        var inWorkingCopy = false

        for (line in lines) {
            val trimmed = line.trim()

            // Look for the "Working copy changes:" header
            if (trimmed.startsWith("Working copy changes:")) {
                inWorkingCopy = true
                continue
            }

            // Skip empty lines
            if (trimmed.isEmpty()) {
                continue
            }

            // If we're in the working copy section, parse file statuses
            if (inWorkingCopy) {
                if (trimmed.startsWith("Working copy")) {
                    inWorkingCopy = false
                } else {
                    parseStatusLine(trimmed, repo, builder)
                }
            }
        }
    }

    /**
     * Parse a single status line
     * Format: "M path/to/file.txt" or "A path/to/file.txt", etc.
     */
    private fun parseStatusLine(line: String, repo: JujutsuRepository, builder: ChangelistBuilder) {
        if (line.length < 3) return

        val status = line[0]
        val filePath = line.substring(2).trim()

        if (filePath.isEmpty()) return

        val file = repo.directory.findFileByRelativePath(filePath)
        // Need to get the path even if the file is not found
        val path = repo.directory.getChildPath(filePath)

        when (status) {
            'M' -> {
                // Modified file
                if (file != null) {
                    addModifiedChange(path, builder)
                }
            }

            'A' -> {
                // Added file
                if (file != null) {
                    addAddedChange(path, builder)
                }
            }

            'D' -> {
                // Deleted file
                addDeletedChange(path, builder)
            }

            'R' -> {
                // Renamed file: format is "R {oldname => newname}"
                addRenamedChange(filePath, repo, builder)
            }

            else -> {
                log.debug("Unknown status '$status' for file: $filePath")
            }
        }
    }

    private fun addModifiedChange(path: FilePath, builder: ChangelistBuilder) {
        val beforeRevision = path.jujutsuRepository.createRevision(path, WorkingCopy.parent) // Parent commit
        val afterRevision = CurrentContentRevision(path)

        builder.processChange(
            Change(beforeRevision, afterRevision, FileStatus.MODIFIED),
            vcs.keyInstanceMethod
        )
    }

    private fun addAddedChange(path: FilePath, builder: ChangelistBuilder) {
        val afterRevision = CurrentContentRevision(path)

        builder.processChange(
            Change(null, afterRevision, FileStatus.ADDED),
            vcs.keyInstanceMethod
        )
    }

    private fun addDeletedChange(path: FilePath, builder: ChangelistBuilder) {
        val beforeRevision = path.jujutsuRepository.createRevision(path, WorkingCopy.parent)

        builder.processChange(
            Change(beforeRevision, null, FileStatus.DELETED),
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
        // Remove braces and parse

        val oldPath = prefix + before + suffix
        val newPath = prefix + after + suffix

        log.info("Detected rename: $oldPath => $newPath")

        // Create file paths
        val beforePath = repo.directory.getChildPath(oldPath)
        val afterPath = repo.directory.getChildPath(newPath)

        // Create revisions
        val beforeRevision = repo.createRevision(beforePath, WorkingCopy.parent)
        val afterRevision = CurrentContentRevision(afterPath)

        // Create change with MODIFIED status (IntelliJ shows renames as modifications)
        val change = Change(beforeRevision, afterRevision, FileStatus.MODIFIED)

        // Mark as renamed
        change.isIsReplaced = true

        builder.processChange(change, vcs.keyInstanceMethod)
    }
}
