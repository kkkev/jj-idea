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
import com.intellij.vcs.log.VcsLogProperties.VcsLogProperty
import com.intellij.vcs.log.graph.PermanentGraph
import com.intellij.vcs.log.impl.VcsRefImpl
import `in`.kkkev.jjidea.jj.*

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
        val result = vcsInstance.logService.getLog(Expression.ALL)

        val entries = result.getOrElse {
            throw VcsException("Failed to read commits: ${it.message}")
        }

        log.info("Read ${entries.size} commits")

        // Take only the requested number of commits
        val limit = requirements.commitCount
        val limitedEntries = if (limit > 0) entries.take(limit) else entries

        // Convert to VcsCommitMetadata
        val commits = limitedEntries.map { entry ->
            JujutsuCommitMetadata(entry, root).apply {
                log.debug("Created commit: id='$id' subject='${subject.take(50)}'")
            }
        }

        // Read refs
        val refs = readAllRefsInternal(root)

        log.info("Returning ${commits.size} commits and ${refs.size} refs")
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
        val result = vcsInstance.logService.getCommitGraph(Expression.ALL)

        result.getOrElse {
            log.error("Failed to read commit hashes: ${it.message}")
            return LogDataImpl.EMPTY
        }.forEach { node ->
            val commit = JujutsuTimedCommit(
                changeId = node.changeId,
                parentIds = node.parentIds,
                timestamp = node.timestamp.toEpochMilliseconds()
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
                val changeIdString = ChangeId.fromHexString(hexHash)
                log.info("Reading details for hash: $hexHash (change ID: $changeIdString)")

                // Use logService to get log entry for this specific revision
                val result = vcsInstance.logService.getLog(changeIdString)

                result.onSuccess { entries ->
                    log.info("Successfully read log for $changeIdString, got ${entries.size} entries")
                    if (entries.isNotEmpty()) {
                        consumer.consume(JujutsuFullCommitDetails.create(entries[0], root))
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

    override fun getSupportedVcs() = JujutsuVcs.getKey()

    // TODO This looks wrong - when is it called? Does this work for multi-root projects?
    override fun getVcsRoot(project: Project, root: VirtualFile, path: FilePath): VirtualFile {
        log.debug("getVcsRoot() called for path: ${path.path}, root: ${root.path}")
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
        }.map { refAtChange ->
            val refType = when (refAtChange.ref) {
                WorkingCopy -> JujutsuLogRefManager.WORKING_COPY
                is Bookmark -> JujutsuLogRefManager.BOOKMARK
                // TODO Is this a sensible default?
                // TODO What about tags?
                else -> JujutsuLogRefManager.BOOKMARK
            }

            // We have no choice but to use this implementation here - anything else breaks IntelliJ during serde
            @Suppress("UnstableApiUsage")
            val ref = VcsRefImpl(refAtChange.changeId.hash, refAtChange.ref.toString(), refType, root)
            log.info("Created ref: name='${ref.name}' hash='${ref.commitHash}' type='${ref.type}'")
            ref
        }.toSet()

        log.info("Found ${refs.size} refs: ${refs.joinToString { "${it.name}@${it.commitHash}" }}")
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
        graphOptions: PermanentGraph.Options,
        maxCount: Int
    ): List<TimedVcsCommit> {
        // Basic implementation - just return all commits
        // Full filtering would require translating IntelliJ filters to JJ revsets
        log.info("Getting commits matching filter (maxCount=$maxCount)")

        val commits = mutableListOf<TimedVcsCommit>()
        readAllHashes(root) { commits.add(it) }

        return if (maxCount > 0) commits.take(maxCount) else commits
    }

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

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getPropertyValue(property: VcsLogProperty<T?>?): T? {
        return when (property) {
            VcsLogProperties.LIGHTWEIGHT_BRANCHES -> true as T
            VcsLogProperties.SUPPORTS_INDEXING -> false as T
            VcsLogProperties.SUPPORTS_LOG_DIRECTORY_HISTORY -> false as T
            else -> null
        }
    }

    override fun getCurrentBranch(root: VirtualFile): String? {
        // Return null - JJ uses bookmarks instead of a current branch concept
        return null
    }
}
