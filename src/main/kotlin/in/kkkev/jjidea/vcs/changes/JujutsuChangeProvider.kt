package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.vcs.JujutsuVcs

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
        log.debug("Getting changes for dirty scope")

        val vcsRoot = getVcsRoot(dirtyScope) ?: return

        try {
            val result = vcs.commandExecutor.status()

            if (!result.isSuccess) {
                log.warn("Failed to get jj status: ${result.stderr}")
                return
            }

            parseStatus(result.stdout, vcsRoot, builder)
        } catch (e: Exception) {
            log.error("Error getting changes", e)
        }
    }

    override fun isModifiedDocumentTrackingRequired() = true

    private fun getVcsRoot(dirtyScope: VcsDirtyScope): VirtualFile? {
        return dirtyScope.affectedContentRoots.firstOrNull()
    }

    /**
     * Parse jj status output and add changes to the builder
     *
     * jj status output format:
     * Working copy changes:
     * M file1.txt
     * A file2.txt
     * D file3.txt
     */
    private fun parseStatus(output: String, root: VirtualFile, builder: ChangelistBuilder) {
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
                parseStatusLine(trimmed, root, builder)
            }
        }
    }

    /**
     * Parse a single status line
     * Format: "M path/to/file.txt" or "A path/to/file.txt", etc.
     */
    private fun parseStatusLine(line: String, root: VirtualFile, builder: ChangelistBuilder) {
        if (line.length < 3) return

        val status = line[0]
        val filePath = line.substring(2).trim()

        if (filePath.isEmpty()) return

        val file = root.findFileByRelativePath(filePath)
        val path = VcsUtil.getFilePath(root.path + "/" + filePath, false)

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
                addRenamedChange(filePath, root, builder)
            }

            else -> {
                log.debug("Unknown status '$status' for file: $filePath")
            }
        }
    }

    private fun addModifiedChange(path: FilePath, builder: ChangelistBuilder) {
        val beforeRevision = vcs.createRevision(path, WorkingCopy.parent) // Parent commit
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
        val beforeRevision = vcs.createRevision(path, WorkingCopy.parent)

        builder.processChange(
            Change(beforeRevision, null, FileStatus.DELETED),
            vcs.keyInstanceMethod
        )
    }

    /**
     * Parse rename status and add change
     * Format: "{oldname => newname}"
     */
    private fun addRenamedChange(renameSpec: String, root: VirtualFile, builder: ChangelistBuilder) {
        // Remove braces and parse
        val spec = renameSpec.trim().removeSurrounding("{", "}")
        val parts = spec.split(" => ")

        if (parts.size != 2) {
            log.warn("Invalid rename format: $renameSpec")
            return
        }

        val oldPath = parts[0].trim()
        val newPath = parts[1].trim()

        log.info("Detected rename: $oldPath => $newPath")

        // Create file paths
        val beforePath = VcsUtil.getFilePath(root.path + "/" + oldPath, false)
        val afterPath = VcsUtil.getFilePath(root.path + "/" + newPath, false)

        // Create revisions
        val beforeRevision = vcs.createRevision(beforePath, WorkingCopy.parent)
        val afterRevision = CurrentContentRevision(afterPath)

        // Create change with MODIFIED status (IntelliJ shows renames as modifications)
        val change = Change(beforeRevision, afterRevision, FileStatus.MODIFIED)

        // Mark as renamed
        change.isIsReplaced = true

        builder.processChange(change, vcs.keyInstanceMethod)
    }
}
