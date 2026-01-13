package `in`.kkkev.jjidea.vcs

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Consumer
import com.intellij.vcs.log.*
import com.intellij.vcs.log.VcsLogProperties.VcsLogProperty
import com.intellij.vcs.log.graph.PermanentGraph
import com.intellij.vcs.log.impl.VcsRefImpl
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*

/**
 * Provides repository-wide log for Jujutsu VCS
 */
class JujutsuLogProvider : VcsLogProvider {
    private val log = Logger.getInstance(javaClass)
    private val refManager = JujutsuLogRefManager()

    init {
        log.info("JujutsuLogProvider initialized")
    }

    @Throws(VcsException::class)
    override fun readFirstBlock(
        root: VirtualFile,
        requirements: VcsLogProvider.Requirements
    ): VcsLogProvider.DetailedLogData {
        log.debug("Reading first block of commits for root: ${root.path}")

        // Use logService to get log entries
        val result = root.jujutsuVcs.logService.getLog(Expression.ALL)

        val entries = result.getOrElse {
            throw VcsException("Failed to read commits: ${it.message}")
        }

        // Take only the requested number of commits
        val limit = requirements.commitCount
        val limitedEntries = if (limit > 0) entries.take(limit) else entries

        // Convert to VcsCommitMetadata
        val commits = limitedEntries.map { entry -> JujutsuCommitMetadata(entry, root) }

        // Read refs
        val refs = readAllRefsInternal(root)

        log.debug("Returning ${commits.size} commits and ${refs.size} refs")
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
        log.debug("Reading all commit hashes for root: ${root.path}")

        // Use logService to get commit graph
        val result = root.jujutsuVcs.logService.getCommitGraph(Expression.ALL)

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
        log.debug("Reading full details for ${hashes.size} commits")

        val vcsInstance = root.jujutsuVcs

        hashes.forEach { hexHash ->
            try {
                // Convert hex string back to JJ change ID
                val changeId = ChangeId.fromHexString(hexHash)

                // Use logService to get log entry for this specific revision
                // IMPORTANT: Use Expression with the FULL change ID, not the short prefix!
                val result = vcsInstance.logService.getLog(Expression(changeId.full))

                result.onSuccess { entries ->
                    if (entries.isNotEmpty()) {
                        consumer.consume(JujutsuFullCommitDetails.create(entries[0], root))
                    } else {
                        log.error("No entries returned for $changeId")
                    }
                }.onFailure { error ->
                    log.error("Failed to read log for $changeId: ${error.message}")
                }
            } catch (e: Exception) {
                log.error("Failed to read commit details for $hexHash", e)
            }
        }
    }

    override fun readMetadata(root: VirtualFile, hashes: List<String>, consumer: Consumer<in VcsCommitMetadata>) {
        log.debug("Reading metadata for ${hashes.size} commits: ${hashes.take(3).joinToString(", ")}...")
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
        log.debug("Reading all refs for root: ${root.path}")

        // Use logService to get refs
        val result = root.jujutsuVcs.logService.getRefs()

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
            VcsRefImpl(refAtChange.changeId.hash, refAtChange.ref.toString(), refType, root)
        }.toSet()

        log.debug("Found ${refs.size} refs")
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
        log.debug("Getting commits matching filter (maxCount=$maxCount)")

        val commits = mutableListOf<TimedVcsCommit>()
        readAllHashes(root) { commits.add(it) }

        return if (maxCount > 0) commits.take(maxCount) else commits
    }

    override fun subscribeToRootRefreshEvents(roots: Collection<VirtualFile>, refresher: VcsLogRefresher): Disposable {
        // TODO: Subscribe to file system events to refresh when .jj directory changes
        log.debug("Subscribed to refresh events for ${roots.size} roots")
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

    override fun getDiffHandler(): VcsLogDiffHandler = JujutsuLogDiffHandler
}

/**
 * Diff handler for Jujutsu VCS Log.
 * Enables comparing revisions from the VCS Log view.
 */
private object JujutsuLogDiffHandler : VcsLogDiffHandler {
    private val log = Logger.getInstance(javaClass)

    override fun showDiff(
        root: VirtualFile,
        leftPath: FilePath?,
        leftHash: Hash,
        rightPath: FilePath?,
        rightHash: Hash
    ) {
        val path = rightPath ?: leftPath ?: return
        val fileName = path.name

        // Find project for this root
        val project = root.jujutsuProject

        // Load content in background thread to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val vcs = root.jujutsuVcs
                val leftChangeId = ChangeId.fromHexString(leftHash.asString())
                val rightChangeId = ChangeId.fromHexString(rightHash.asString())

                // Get relative path for jj commands
                val relPath = vcs.getRelativePath(path)

                // Load content for both revisions
                val leftResult = vcs.commandExecutor.show(relPath, leftChangeId)
                val rightResult = vcs.commandExecutor.show(relPath, rightChangeId)

                val leftContent = if (leftResult.isSuccess) leftResult.stdout else ""
                val rightContent = if (rightResult.isSuccess) rightResult.stdout else ""

                // Show diff on EDT
                ApplicationManager.getApplication().invokeLater {
                    val contentFactory = DiffContentFactory.getInstance()
                    val diffManager = DiffManager.getInstance()

                    val content1 = contentFactory.create(project, leftContent)
                    val content2 = contentFactory.create(project, rightContent)

                    val diffRequest = SimpleDiffRequest(
                        fileName,
                        content1,
                        content2,
                        leftChangeId.short,
                        rightChangeId.short
                    )

                    diffManager.showDiff(project, diffRequest)
                }
            } catch (e: Exception) {
                log.error("Failed to show diff for ${path.path}", e)
            }
        }
    }

    override fun showDiffWithLocal(
        root: VirtualFile,
        revisionPath: FilePath?,
        hash: Hash,
        localPath: FilePath
    ) {
        val path = revisionPath ?: localPath
        val fileName = path.name

        // Find project for this root
        val project = root.jujutsuProject

        // Load content in background thread to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val vcs = root.jujutsuVcs
                val changeId = ChangeId.fromHexString(hash.asString())

                // Get relative path for jj commands
                val relPath = vcs.getRelativePath(path)

                // Load revision content
                val revisionResult = vcs.commandExecutor.show(relPath, changeId)
                val revisionContent = if (revisionResult.isSuccess) revisionResult.stdout else ""

                // Show diff on EDT
                ApplicationManager.getApplication().invokeLater {
                    val contentFactory = DiffContentFactory.getInstance()
                    val diffManager = DiffManager.getInstance()

                    val content1 = contentFactory.create(project, revisionContent)

                    // Use VirtualFile for local file to allow editing
                    val localFile = LocalFileSystem.getInstance().findFileByPath(localPath.path)
                    val content2 = if (localFile != null && localFile.exists()) {
                        contentFactory.create(project, localFile)
                    } else {
                        contentFactory.createEmpty()
                    }

                    val diffRequest = SimpleDiffRequest(
                        fileName,
                        content1,
                        content2,
                        changeId.short,
                        JujutsuBundle.message("diff.label.local")
                    )

                    diffManager.showDiff(project, diffRequest)
                }
            } catch (e: Exception) {
                log.error("Failed to show diff with local for ${path.path}", e)
            }
        }
    }

    override fun showDiffForPaths(
        root: VirtualFile,
        affectedPaths: Collection<FilePath>?,
        leftRevision: Hash,
        rightRevision: Hash?
    ) {
        // If no paths specified, nothing to do
        if (affectedPaths.isNullOrEmpty()) return

        // If right revision is null, compare with working copy
        if (rightRevision == null) {
            // Show diff with local for each path
            affectedPaths.forEach { path ->
                showDiffWithLocal(root, path, leftRevision, path)
            }
            return
        }

        // Show diff between two revisions for each path
        affectedPaths.forEach { path ->
            showDiff(root, path, leftRevision, path, rightRevision)
        }
    }

    override fun createContentRevision(filePath: FilePath, hash: Hash): ContentRevision {
        // Convert hash back to ChangeId
        val changeId = ChangeId.fromHexString(hash.asString())

        // Find VCS instance by checking all open projects
        // This is done in a read action to avoid EDT violations
        val vcs = ReadAction.compute<JujutsuVcs?, RuntimeException> {
            ProjectManager.getInstance().openProjects.firstNotNullOfOrNull { project ->
                project.jujutsuRoots
                    .firstOrNull { filePath.path.startsWith(it.path.path) }
                    ?.vcs as? JujutsuVcs
            }
        } ?: throw VcsException("Cannot find Jujutsu VCS for file: ${filePath.path}")

        // Create and return the content revision
        return vcs.createRevision(filePath, changeId)
    }
}
