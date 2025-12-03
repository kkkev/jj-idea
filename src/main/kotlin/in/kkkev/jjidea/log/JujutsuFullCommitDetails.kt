package `in`.kkkev.jjidea.log

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.VcsFullCommitDetails
import com.intellij.vcs.log.VcsUser
import com.intellij.vcs.log.impl.VcsUserImpl
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuVcs
import `in`.kkkev.jjidea.ui.JujutsuLogEntry

/**
 * Full commit details for a Jujutsu commit including file changes
 */
// TODO Lots repeated in here from JujutsuCommitMetadata - dedupe
class JujutsuFullCommitDetails(
    private val entry: JujutsuLogEntry,
    private val root: VirtualFile,
    private val changesList: Collection<Change>
) : VcsFullCommitDetails {

    override fun getId(): Hash = entry.changeId.hashImpl

    override fun getParents(): List<Hash> = entry.parentIds.map { it.hashImpl }

    override fun getCommitTime(): Long = entry.committerTimestamp

    override fun getTimestamp(): Long = commitTime

    override fun getAuthor(): VcsUser = VcsUserImpl(entry.authorName, entry.authorEmail)

    override fun getFullMessage(): String = entry.description

    override fun getSubject(): String {
        // First line of description
        val lines = entry.description.lines()
        return if (lines.isNotEmpty()) lines[0] else entry.description
    }

    override fun getCommitter(): VcsUser = VcsUserImpl(entry.committerName, entry.committerEmail)

    override fun getAuthorTime(): Long = entry.authorTimestamp

    override fun getRoot(): VirtualFile = root

    override fun getChanges(): Collection<Change> = changesList

    // TODO Is this correct? Doesn't it need to be a subset? Test with merges
    override fun getChanges(parent: Int): Collection<Change> = changesList

    companion object {
        private val log = Logger.getInstance(JujutsuFullCommitDetails::class.java)

        /**
         * Create JujutsuFullCommitDetails by loading changes for the entry.
         * Must be called from a background thread.
         */
        fun create(entry: JujutsuLogEntry, root: VirtualFile): JujutsuFullCommitDetails {
            val changes = loadChanges(entry, root)
            return JujutsuFullCommitDetails(entry, root, changes)
        }

        private fun loadChanges(entry: JujutsuLogEntry, root: VirtualFile): Collection<Change> {
            val vcs = JujutsuVcs.findRequired(root)

            try {
                // Get diff summary for this revision
                val result = vcs.commandExecutor.diffSummary(entry.changeId.short)

                if (!result.isSuccess) {
                    log.warn("Failed to get diff summary for ${entry.changeId}: ${result.stderr}")
                    return emptyList()
                }

                return parseDiffSummaryOutput(result.stdout, entry, root, vcs)
            } catch (e: Exception) {
                log.error("Error loading changes for ${entry.changeId}", e)
                return emptyList()
            }
        }

        private fun parseDiffSummaryOutput(
            output: String,
            entry: JujutsuLogEntry,
            root: VirtualFile,
            vcs: JujutsuVcs
        ): List<Change> {
            val changes = mutableListOf<Change>()
            val lines = output.lines()

            for (line in lines) {
                val trimmed = line.trim()

                // Skip empty lines
                if (trimmed.isEmpty()) {
                    continue
                }

                // Parse each line as a file status
                parseStatusLine(trimmed, entry, root, vcs)?.let { changes.add(it) }
            }

            return changes
        }

        private fun parseStatusLine(
            line: String,
            entry: JujutsuLogEntry,
            root: VirtualFile,
            vcs: JujutsuVcs
        ): Change? {
            if (line.length < 3) return null

            val status = line[0]
            val filePath = line.substring(2).trim()

            if (filePath.isEmpty()) return null

            val path = VcsUtil.getFilePath(root.path + "/" + filePath, false)

            // For historical commits, we use the parent revision as "before"
            val parentRevision = if (entry.parentIds.isNotEmpty()) {
                entry.parentIds.first().full
            } else {
                null
            }

            return when (status) {
                'M' -> {
                    // Modified file
                    val beforeRevision = parentRevision?.let { vcs.createRevision(path, it) }
                    val afterRevision = vcs.createRevision(path, entry.changeId.full)
                    Change(beforeRevision, afterRevision, FileStatus.MODIFIED)
                }

                'A' -> {
                    // Added file
                    val afterRevision = vcs.createRevision(path, entry.changeId.full)
                    Change(null, afterRevision, FileStatus.ADDED)
                }

                'D' -> {
                    // Deleted file
                    val beforeRevision = parentRevision?.let { vcs.createRevision(path, it) }
                    Change(beforeRevision, null, FileStatus.DELETED)
                }

                else -> {
                    log.debug("Unknown status '$status' for file: $filePath")
                    null
                }
            }
        }
    }
}
