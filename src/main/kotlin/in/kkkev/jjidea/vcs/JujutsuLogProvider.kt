package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Consumer
import com.intellij.vcs.log.*
import com.intellij.vcs.log.impl.VcsRefImpl
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuCommitMetadata
import `in`.kkkev.jjidea.jj.JujutsuFullCommitDetails
import `in`.kkkev.jjidea.jj.JujutsuLogService

/**
 * Provides repository-wide log for Jujutsu VCS
 */
class JujutsuLogProvider : VcsLogProvider {

    private val log = Logger.getInstance(JujutsuLogProvider::class.java)
    private val refManager = JujutsuLogRefManager()

    init {
        log.info("JujutsuLogProvider initialized")
    }

    @Throws(VcsException::class)
    override fun readFirstBlock(
        root: VirtualFile,
        requirements: VcsLogProvider.Requirements
    ): VcsLogProvider.DetailedLogData {
        log.info("Reading first block of commits for root: ${root.path}")

        val vcsInstance = JujutsuVcs.findRequired(root)

        // Use logService to get log entries
        val result = vcsInstance.logService.getLog("all()")

        val entries = result.getOrElse {
            throw VcsException("Failed to read commits: ${it.message}")
        }

        log.info("Read ${entries.size} commits")

        // Take only the requested number of commits
        val limit = requirements.commitCount
        val limitedEntries = if (limit > 0) entries.take(limit) else entries

        // Convert to VcsCommitMetadata
        val commits = limitedEntries.map { entry -> JujutsuCommitMetadata(entry, root) }

        // Read refs
        val refs = readAllRefsInternal(root)

        return DetailedLogDataImpl(commits, refs)
    }

    data class DetailedLogDataImpl(private val commits: List<JujutsuCommitMetadata>, private val refs: Set<VcsRef>) :
        VcsLogProvider.DetailedLogData {
        override fun getCommits() = commits
        override fun getRefs() = refs
    }

    data class LogDataImpl(private val refs: Set<VcsRef> = emptySet(), private val users: Set<VcsUser> = emptySet()) :
        VcsLogProvider.LogData {
        override fun getRefs() = refs
        override fun getUsers() = users

        companion object {
            val EMPTY = LogDataImpl()
        }
    }

    override fun readAllHashes(root: VirtualFile, consumer: Consumer<in TimedVcsCommit>): VcsLogProvider.LogData {
        log.info("Reading all commit hashes for root: ${root.path}")

        val vcsInstance = JujutsuVcs.findRequired(root)

        // Use logService to get commit graph
        val result = vcsInstance.logService.getCommitGraph("all()")

        result.getOrElse {
            log.error("Failed to read commit hashes: ${it.message}")
            return LogDataImpl.EMPTY
        }.forEach { node ->
            val commit = JujutsuTimedCommit(
                changeId = node.changeId,
                parentIds = node.parentIds,
                timestamp = node.timestamp
            )
            consumer.consume(commit)
        }

        // Read refs
        val refs = readAllRefsInternal(root)

        return LogDataImpl(refs)
    }

    override fun readFullDetails(root: VirtualFile, hashes: List<String>, consumer: Consumer<in VcsFullCommitDetails>) {
        log.info("Reading full details for ${hashes.size} commits: ${hashes.joinToString(", ")}")

        val vcsInstance = JujutsuVcs.findRequired(root)

        hashes.forEach { hexHash ->
            try {
                // Convert hex string back to JJ change ID string
                val changeIdString = ChangeId.Companion.fromHexString(hexHash)
                log.info("Reading details for hash: $hexHash (change ID: $changeIdString)")

                // Use logService to get log entry for this specific revision
                val result = vcsInstance.logService.getLog(changeIdString)

                result.onSuccess { entries ->
                    log.info("Successfully read log for $changeIdString, got ${entries.size} entries")
                    if (entries.isNotEmpty()) {
                        consumer.consume(JujutsuFullCommitDetails.Companion.create(entries[0], root))
                        log.info("Consumed details for $changeIdString")
                    } else {
                        log.error("No entries returned for $changeIdString")
                    }
                }.onFailure { error ->
                    log.error("Failed to read log for $changeIdString: ${error.message}")
                }
            } catch (e: Exception) {
                log.error("Failed to read commit details for $hexHash", e)
            }
        }
    }

    override fun readMetadata(root: VirtualFile, hashes: List<String>, consumer: Consumer<in VcsCommitMetadata>) {
        log.info("Reading metadata for ${hashes.size} commits")
        // Reuse readFullDetails since JJ loads everything anyway
        readFullDetails(root, hashes, Consumer { consumer.consume(it) })
    }

    override fun getReferenceManager(): VcsLogRefManager = refManager

    override fun getSupportedVcs(): VcsKey {
        val key = JujutsuVcs.getKey()
        log.info("getSupportedVcs() called, returning: $key")
        return key
    }

    override fun getVcsRoot(project: Project, root: VirtualFile, path: FilePath): VirtualFile? {
        log.info("getVcsRoot() called for path: ${path.path}, root: ${root.path}")
        return root
    }

    private fun readAllRefsInternal(root: VirtualFile): Set<VcsRef> {
        log.info("Reading all refs for root: ${root.path}")

        val vcsInstance = JujutsuVcs.findRequired(root)

        // Use logService to get refs
        val result = vcsInstance.logService.getRefs()

        val refs = result.getOrElse {
            log.error("Failed to read refs: ${it.message}")
            return emptySet()
        }.map { jjRef ->
            val refType = when (jjRef.type) {
                JujutsuLogService.RefType.WORKING_COPY -> JujutsuLogRefManager.WORKING_COPY
                JujutsuLogService.RefType.BOOKMARK -> JujutsuLogRefManager.BOOKMARK
            }

            VcsRefImpl(
                jjRef.changeId.hash,
                jjRef.name,
                refType,
                root
            )
        }.toSet()

        log.info("Found ${refs.size} refs")
        return refs
    }

    override fun getCurrentUser(root: VirtualFile): VcsUser? {
        // JJ uses author.email() for user info, but we'd need to run a command to get it
        // For now, return null and implement later if needed
        return null
    }

    override fun getCommitsMatchingFilter(
        root: VirtualFile,
        filterData: VcsLogFilterCollection,
        maxCount: Int
    ): List<TimedVcsCommit> {
        // Basic implementation - just return all commits
        // Full filtering would require translating IntelliJ filters to JJ revsets
        log.info("Getting commits matching filter (maxCount=$maxCount)")

        val commits = mutableListOf<TimedVcsCommit>()
        readAllHashes(root) { commits.add(it) }

        return if (maxCount > 0) commits.take(maxCount) else commits
    }

    override fun getDiffHandler(): VcsLogDiffHandler? = null // Use default handler

    override fun subscribeToRootRefreshEvents(roots: Collection<VirtualFile>, refresher: VcsLogRefresher): Disposable {
        // TODO: Subscribe to file system events to refresh when .jj directory changes
        log.info("Subscribed to refresh events for ${roots.size} roots")
        return Disposable { }
    }

    override fun getContainingBranches(
        root: VirtualFile,
        hash: Hash
    ): Collection<String?> {
        // Return empty for now - this would require checking which bookmarks contain this commit
        return emptyList()
    }

    override fun <T : Any?> getPropertyValue(property: VcsLogProperties.VcsLogProperty<T?>?): T? {
        // Return null for all properties for now
        return null
    }

    override fun getCurrentBranch(root: VirtualFile): String? {
        // Return null - JJ uses bookmarks instead of a current branch concept
        return null
    }

    companion object {
        fun getKey(): VcsKey = JujutsuVcs.getKey()
    }
}

/*
data class VcsRefImpl(private val hash: Hash, private val name: String, private val type: VcsRefType, private val root: VirtualFile) : VcsRef {
    override fun getCommitHash() = hash
    override fun getName() = name
    override fun getType() = type
    override fun getRoot() = root
}
*/

