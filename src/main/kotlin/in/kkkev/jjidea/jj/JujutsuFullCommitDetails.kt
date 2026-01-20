package `in`.kkkev.jjidea.jj

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsFullCommitDetails
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.jujutsuVcs

/**
 * Full commit details for a Jujutsu commit including file changes.
 * Extends JujutsuCommitMetadataBase for common metadata handling.
 */
class JujutsuFullCommitDetails(entry: LogEntry, root: VirtualFile, private val changesList: Collection<Change>) :
    JujutsuCommitMetadataBase(entry, root),
    VcsFullCommitDetails {
    override fun getChanges(): Collection<Change> = changesList

    // TODO Is this correct? Doesn't it need to be a subset? Test with merges
    override fun getChanges(parent: Int): Collection<Change> = changesList

    companion object {
        private val log = Logger.getInstance(JujutsuFullCommitDetails::class.java)

        /**
         * Create JujutsuFullCommitDetails by loading changes for the entry.
         * Must be called from a background thread.
         */
        fun create(entry: LogEntry, root: VirtualFile): JujutsuFullCommitDetails {
            val changes = loadChanges(entry, root)
            return JujutsuFullCommitDetails(entry, root, changes)
        }

        private fun loadChanges(entry: LogEntry, root: VirtualFile): Collection<Change> {
            val vcs = root.jujutsuVcs

            // First, detect renames using git-format diff
            val renames = detectRenames(entry, vcs)
            val renamedPaths = renames
                .flatMap { (oldPath, newPath) ->
                    listOf(oldPath, newPath)
                }.toSet()

            // Get regular file changes
            val result = vcs.logService.getFileChanges(entry.changeId)

            val regularChanges = result.getOrElse { error ->
                log.error("Error loading changes for ${entry.changeId}: ${error.message}", error)
                emptyList()
            }.filter { fileChange ->
                // Filter out files that are part of renames to avoid duplicates
                fileChange.filePath !in renamedPaths
            }.mapNotNull { fileChange ->
                convertToChange(fileChange, entry, root, vcs)
            }

            // Create Change objects for renames
            val renameChanges = renames.map { (oldPath, newPath) ->
                createRenameChange(oldPath, newPath, entry, root, vcs)
            }

            return regularChanges + renameChanges
        }

        /**
         * Detect file renames using git-format diff.
         * Returns a list of (oldPath, newPath) pairs.
         */
        private fun detectRenames(entry: LogEntry, vcs: JujutsuVcs): List<Pair<String, String>> {
            val result = vcs.commandExecutor.diffGit(entry.changeId)

            if (!result.isSuccess) {
                log.debug("Failed to get git diff for ${entry.changeId}: ${result.stderr}")
                return emptyList()
            }

            return parseGitDiffRenames(result.stdout)
        }

        /**
         * Parse git-format diff to extract rename pairs.
         * Format:
         * ```
         * diff --git a/old.txt b/new.txt
         * rename from old.txt
         * rename to new.txt
         * ```
         */
        private fun parseGitDiffRenames(diffOutput: String): List<Pair<String, String>> {
            val lines = diffOutput.lines()
            val renames = mutableListOf<Pair<String, String>>()
            var renameFrom: String? = null

            for (line in lines) {
                when {
                    line.startsWith("rename from ") -> {
                        renameFrom = line.substringAfter("rename from ").trim()
                    }

                    line.startsWith("rename to ") -> {
                        val renameTo = line.substringAfter("rename to ").trim()
                        if (renameFrom != null) {
                            renames.add(renameFrom to renameTo)
                            log.info("Detected rename in ${diffOutput.lines().firstOrNull()}: $renameFrom -> $renameTo")
                            renameFrom = null
                        }
                    }
                }
            }

            return renames
        }

        /**
         * Create a Change object for a renamed file.
         */
        private fun createRenameChange(
            oldPath: String,
            newPath: String,
            entry: LogEntry,
            root: VirtualFile,
            vcs: JujutsuVcs
        ): Change {
            val beforePath = VcsUtil.getFilePath(root.path + "/" + oldPath, false)
            val afterPath = VcsUtil.getFilePath(root.path + "/" + newPath, false)

            val beforeRevision = entry.parentIds.firstOrNull()?.let { vcs.createRevision(beforePath, it) }
            val afterRevision = vcs.createRevision(afterPath, entry.changeId)

            return Change(beforeRevision, afterRevision, FileStatus.MODIFIED).apply {
                this.isIsReplaced = true
            }
        }

        /**
         * Convert a FileChange DTO to an IntelliJ Change object.
         * This is where we integrate with the IntelliJ VCS framework.
         */
        private fun convertToChange(
            fileChange: FileChange,
            entry: LogEntry,
            root: VirtualFile,
            vcs: JujutsuVcs
        ): Change? {
            val path = VcsUtil.getFilePath(root.path + "/" + fileChange.filePath, false)

            // For historical commits, we use the parent revision as "before"
            val parentRevision = entry.parentIds.firstOrNull()

            return when (fileChange.status) {
                FileChangeStatus.MODIFIED -> {
                    val beforeRevision = parentRevision?.let { vcs.createRevision(path, it) }
                    val afterRevision = vcs.createRevision(path, entry.changeId)
                    Change(beforeRevision, afterRevision, FileStatus.MODIFIED)
                }

                FileChangeStatus.ADDED -> {
                    val afterRevision = vcs.createRevision(path, entry.changeId)
                    Change(null, afterRevision, FileStatus.ADDED)
                }

                FileChangeStatus.DELETED -> {
                    val beforeRevision = parentRevision?.let { vcs.createRevision(path, it) }
                    Change(beforeRevision, null, FileStatus.DELETED)
                }

                FileChangeStatus.UNKNOWN -> {
                    log.debug("Skipping file with unknown status: ${fileChange.filePath}")
                    null
                }
            }
        }
    }
}
