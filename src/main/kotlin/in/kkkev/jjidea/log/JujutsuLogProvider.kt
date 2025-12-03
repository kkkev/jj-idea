package `in`.kkkev.jjidea.log

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
import `in`.kkkev.jjidea.JujutsuVcs
import `in`.kkkev.jjidea.ui.JujutsuLogParser

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

        // Create template for commit metadata
        val template = """
            change_id ++ "\0" ++
            change_id.shortest() ++ "\0" ++
            commit_id ++ "\0" ++
            description ++ "\0" ++
            bookmarks ++ "\0" ++
            parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(", ") ++ "\0" ++
            if(current_working_copy, "true", "false") ++ "\0" ++
            if(conflict, "true", "false") ++ "\0" ++
            if(empty, "true", "false") ++ "\0" ++
            author.timestamp().utc().format("%s") ++ "\0" ++
            committer.timestamp().utc().format("%s") ++ "\0" ++
            author.name() ++ "\0" ++
            author.email() ++ "\0" ++
            committer.name() ++ "\0" ++
            committer.email() ++ "\0"
        """.trimIndent().replace("\n", " ")

        // Read commits with limit
        val limit = requirements.commitCount
        val result = vcsInstance.commandExecutor.log("all()", template)

        if (!result.isSuccess) {
            throw VcsException("Failed to read commits: ${result.stderr}")
        }

        val entries = JujutsuLogParser.parseLog(result.stdout)
        log.info("Read ${entries.size} commits")

        // Take only the requested number of commits
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

        // Simple template for just IDs and parents
        val template = """
            change_id ++ "\0" ++
            commit_id ++ "\0" ++
            parents.map(|c| c.change_id()).join(",") ++ "\0"
        """.trimIndent().replace("\n", " ")

        val result = vcsInstance.commandExecutor.log("all()", template)

        if (!result.isSuccess) {
            log.error("Failed to read commit hashes: ${result.stderr}")
            return LogDataImpl.EMPTY
        }

        // Parse the output
        val lines = result.stdout.trim().split("\u0000")
        lines.chunked(3).forEach { chunk ->
            if (chunk.size == 3) {
                // TODO Short ids and parents
                val changeId = chunk[0]
                val commitId = chunk[1]
                val parentIds = chunk[2].split(",").filter { it.isNotEmpty() }.map { ChangeId(it) }

                val commit = JujutsuTimedCommit(
                    changeId = ChangeId(changeId),
                    parentIds = parentIds,
                    // TODO Is this correct?
                    timestamp = System.currentTimeMillis() // JJ doesn't track timestamps by default
                )
                consumer.consume(commit)
            }
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
                // Convert hex string back to JJ change ID
                val changeId = ChangeId.fromHexString(hexHash)
                log.info("Reading details for hash: $hexHash (change ID: $changeId)")

                // TODO The general pattern of providing a template outside of the JJ interface seems wrong
                // We have a lot of repetitive code that sets up a template and parses output - this needs to live
                // behind an interface that one day, we could replace with a JJ library.
                // Also there are many places where we reuse templates - consolidate them where possible

                // Read single commit details
                val template = """
                    change_id ++ "\0" ++
                    change_id.shortest() ++ "\0" ++
                    commit_id ++ "\0" ++
                    description ++ "\0" ++
                    bookmarks ++ "\0" ++
                    parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(", ") ++ "\0" ++
                    if(current_working_copy, "true", "false") ++ "\0" ++
                    if(conflict, "true", "false") ++ "\0" ++
                    if(empty, "true", "false") ++ "\0" ++
                    author.timestamp().utc().format("%s") ++ "\0" ++
                    committer.timestamp().utc().format("%s") ++ "\0" ++
                    author.name() ++ "\0" ++
                    author.email() ++ "\0" ++
                    committer.name() ++ "\0" ++
                    committer.email() ++ "\0"
                """.trimIndent().replace("\n", " ")

                val result = vcsInstance.commandExecutor.log(changeId, template)

                if (result.isSuccess) {
                    log.info("Successfully read log for $changeId, parsing output...")
                    val entries = JujutsuLogParser.parseLog(result.stdout)
                    log.info("Parsed ${entries.size} entries for $changeId")
                    if (entries.isNotEmpty()) {
                        consumer.consume(JujutsuFullCommitDetails.create(entries[0], root))
                        log.info("Consumed details for $changeId")
                    } else {
                        log.error("No entries parsed for $changeId. stdout: ${result.stdout}")
                    }
                } else {
                    log.error("Failed to read log for $changeId: ${result.stderr}")
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

        // TODO Should this be findRequired?
        val vcsInstance = JujutsuVcs.findRequired(root) ?: return emptySet()
        val refs = mutableListOf<VcsRef>()

        // Get all bookmarks using jj bookmark list
        try {
            val template = """
                change_id ++ "\0" ++
                bookmarks ++ "\0" ++
                if(current_working_copy, "true", "false") ++ "\0"
            """.trimIndent().replace("\n", " ")

            val result = vcsInstance.commandExecutor.log("all()", template)

            if (result.isSuccess) {
                val lines = result.stdout.trim().split("\u0000")
                lines.chunked(3).forEach { chunk ->
                    if (chunk.size == 3) {
                        val changeId = chunk[0]
                        val bookmarks = chunk[1]
                        val isWorkingCopy = chunk[2].toBoolean()

                        // Create working copy ref
                        if (isWorkingCopy) {
                            refs.add(
                                VcsRefImpl(
                                    // TODO Short id
                                    ChangeId(changeId).hashImpl,
                                    "@",
                                    JujutsuLogRefManager.WORKING_COPY,
                                    root
                                )
                            )
                        }

                        // Create bookmark refs
                        if (bookmarks.isNotEmpty()) {
                            bookmarks.split(",").forEach { bookmark ->
                                refs.add(
                                    VcsRefImpl(
                                        // TODO Short id
                                        ChangeId(changeId).hashImpl,
                                        bookmark.trim(),
                                        JujutsuLogRefManager.BOOKMARK,
                                        root
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to read refs", e)
        }

        log.info("Found ${refs.size} refs")
        return refs.toSet()
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

