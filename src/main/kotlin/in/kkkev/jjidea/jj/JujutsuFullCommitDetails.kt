package `in`.kkkev.jjidea.jj

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsFullCommitDetails
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.vcs.JujutsuVcs

/**
 * Full commit details for a Jujutsu commit including file changes.
 * Extends JujutsuCommitMetadataBase for common metadata handling.
 */
class JujutsuFullCommitDetails(
    entry: LogEntry,
    root: VirtualFile,
    private val changesList: Collection<Change>
) : JujutsuCommitMetadataBase(entry, root), VcsFullCommitDetails {

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
            val vcs = JujutsuVcs.findRequired(root)

            // Use logService to get file changes
            val result = vcs.logService.getFileChanges(entry.changeId.short)

            return result.getOrElse { error ->
                log.error("Error loading changes for ${entry.changeId}: ${error.message}", error)
                emptyList()
            }.mapNotNull { fileChange ->
                convertToChange(fileChange, entry, root, vcs)
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
            val parentRevision = entry.parentIds.firstOrNull()?.full

            return when (fileChange.status) {
                FileChangeStatus.MODIFIED -> {
                    val beforeRevision = parentRevision?.let { vcs.createRevision(path, it) }
                    val afterRevision = vcs.createRevision(path, entry.changeId.full)
                    Change(beforeRevision, afterRevision, FileStatus.MODIFIED)
                }

                FileChangeStatus.ADDED -> {
                    val afterRevision = vcs.createRevision(path, entry.changeId.full)
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
