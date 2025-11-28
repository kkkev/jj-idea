package `in`.kkkev.jjidea.changes

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuVcs

/**
 * Provides change information for jujutsu working copy
 */
class JujutsuChangeProvider(private val project: Project, private val vcs: JujutsuVcs) : ChangeProvider {
    private val log = Logger.getInstance(JujutsuChangeProvider::class.java)

    override fun getChanges(
        dirtyScope: VcsDirtyScope,
        builder: ChangelistBuilder,
        progress: ProgressIndicator,
        addGate: ChangeListManagerGate
    ) {
        log.debug("Getting changes for dirty scope")

        val vcsRoot = getVcsRoot(dirtyScope) ?: return

        try {
            val result = vcs.commandExecutor.status(vcsRoot)

            if (!result.isSuccess) {
                log.warn("Failed to get jj status: ${result.stderr}")
                return
            }

            parseStatus(result.stdout, vcsRoot, builder)
        } catch (e: Exception) {
            log.error("Error getting changes", e)
        }
    }

    override fun isModifiedDocumentTrackingRequired(): Boolean = true

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
        val path = VcsUtil.getFilePath(root.path + "/" + filePath)

        when (status) {
            'M' -> {
                // Modified file
                if (file != null) {
                    addModifiedChange(path, file, builder)
                }
            }

            'A' -> {
                // Added file
                if (file != null) {
                    addAddedChange(path, file, builder)
                }
            }

            'D' -> {
                // Deleted file
                addDeletedChange(path, builder)
            }

            'R' -> {
                // Renamed file - jj shows this differently, may need special handling
                log.debug("Renamed file detected: $filePath")
            }

            else -> {
                log.debug("Unknown status '$status' for file: $filePath")
            }
        }
    }

    private fun addModifiedChange(path: FilePath, file: VirtualFile, builder: ChangelistBuilder) {
        val beforeRevision = JujutsuContentRevision.createRevision(
            path,
            "@-", // Parent of working copy
            project,
            vcs
        )
        val afterRevision = CurrentContentRevision(path)

        builder.processChange(
            Change(beforeRevision, afterRevision, FileStatus.MODIFIED),
            vcs.keyInstanceMethod
        )
    }

    private fun addAddedChange(path: FilePath, file: VirtualFile, builder: ChangelistBuilder) {
        val afterRevision = CurrentContentRevision(path)

        builder.processChange(
            Change(null, afterRevision, FileStatus.ADDED),
            vcs.keyInstanceMethod
        )
    }

    private fun addDeletedChange(path: FilePath, builder: ChangelistBuilder) {
        val beforeRevision = JujutsuContentRevision.createRevision(
            path,
            "@-",
            project,
            vcs
        )

        builder.processChange(
            Change(beforeRevision, null, FileStatus.DELETED),
            vcs.keyInstanceMethod
        )
    }
}
