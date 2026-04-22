package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.util.GitDiffReverseApplier
import `in`.kkkev.jjidea.vcs.JujutsuRootChecker
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import `in`.kkkev.jjidea.vcs.pathRelativeTo
import java.util.concurrent.CompletableFuture

/**
 * A (possible) JJ repository. "Possible" because the directory could be uninitialised, as this class allows
 * repository initialisation as well as access to all JJ actions.
 */
interface JujutsuRepository {
    val project: Project
    val directory: VirtualFile
    val displayName: String
    val commandExecutor: CommandExecutor
    val logService: LogService
    val isInitialised: Boolean

    /** Git remotes for this repository, lazily fetched once per session. */
    val gitRemotes: List<GitRemote>

    fun getLogEntry(revision: Revision): LogEntry

    val workingCopy: LogEntry

    fun createContentRevision(filePath: FilePath, revision: Revision): ContentRevision
    fun createContentRevision(filePath: FilePath, logEntry: LogEntry): ContentRevision
    fun createParentContentRevision(filePath: FilePath, entry: LogEntry): ContentRevision

    fun getRelativePath(filePath: FilePath): String
    fun getRelativePath(file: VirtualFile): String

    /**
     * The revision to use as the working copy's parent for change/diff providers.
     *
     * Using the raw revset `@-` breaks when the working copy is a merge, because `@-` resolves
     * to multiple commits and `jj file show -r @-` then fails. This resolves to the first parent's
     * change id via the cached working copy entry in the state model. Falls back to `@-` only when
     * the cache is not populated yet (harmless for non-merge working copies).
     */
    val workingCopyParent: Revision
}

data class JujutsuRepositoryImpl(
    override val project: Project,
    override val directory: VirtualFile,
    override val displayName: String
) : JujutsuRepository {
    private val executor: CommandExecutor by lazy { project.commandExecutorFactory.create(directory) }

    /**
     * Command executor for initialized repositories. Throws if repository is not initialized.
     */
    override val commandExecutor: CommandExecutor
        get() {
            requireInitialised()
            return executor
        }

    override val logService: LogService by lazy { CliLogService(this) }

    private val gitRemotesFuture: CompletableFuture<List<GitRemote>> = CompletableFuture.supplyAsync(
        {
            val result = executor.gitRemoteList()
            if (!result.isSuccess) return@supplyAsync emptyList()
            result.stdout.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val name = line.substringBefore(' ')
                    val url = line.substringAfter(' ', "").trim()
                    if (url.isEmpty()) null else GitRemote(name, url)
                }
        },
        AppExecutorUtil.getAppExecutorService()
    ).exceptionally { emptyList() }

    override val gitRemotes: List<GitRemote> get() = gitRemotesFuture.getNow(emptyList())

    private fun requireInitialised() {
        check(isInitialised) { "Repository at ${directory.path} is not initialized. Use initExecutor for gitInit." }
    }

    /**
     * Path of this root, relative to the project directory.
     */
    val relativePath get() = project.guessProjectDir()?.let { directory.pathRelativeTo(it) } ?: directory.path

    override val isInitialised get() = JujutsuRootChecker.isJujutsuRoot(directory)

    override fun toString() = "Repository:$relativePath"

    /**
     * Gets the path for the specified file path, relative to this root.
     */
    override fun getRelativePath(filePath: FilePath): String {
        val absolutePath = filePath.path
        val rootPath = directory.path
        return if (absolutePath.startsWith(rootPath)) {
            absolutePath.removePrefix(rootPath).removePrefix("/")
        } else {
            // Fall back to just the file name if path doesn't start with root
            filePath.name
        }
    }

    override fun getRelativePath(file: VirtualFile) = getRelativePath(VcsUtil.getFilePath(file))

    override fun createContentRevision(filePath: FilePath, revision: Revision): ContentRevision = when (revision) {
        is MergeParentOf -> JujutsuContentRevision(filePath, revision)
        else -> ContentLogEntryImpl(filePath, getLogEntry(revision))
    }

    override fun createContentRevision(filePath: FilePath, logEntry: LogEntry): ContentLogEntry =
        ContentLogEntryImpl(filePath, logEntry)

    override fun getLogEntry(revision: Revision) = logService.getLog(revision).getOrThrow().singleOrNull()
        ?: throw VcsException("Multiple log entries found for revision $revision")

    override val workingCopy: LogEntry
        get() = project.stateModel.workingCopies.value[directory.path]
            ?: throw VcsException("Working copy not found for $this")

    override val workingCopyParent get() = getParentRevisionFor(workingCopy)

    /**
     * Returns the revision to use as "before" content for a log entry's parent.
     * For merge commits (multiple parents), returns [MergeParentOf] so that content is
     * reconstructed via reverse-apply of the entry's diff rather than using first-parent content.
     */
    private fun getParentRevisionFor(entry: LogEntry) = when (entry.parentIds.size) {
        1 -> entry.parentIds.first()
        0 -> entry.id.parent
        else -> MergeParentOf(entry.id)
    }

    override fun createParentContentRevision(filePath: FilePath, entry: LogEntry) =
        createContentRevision(filePath, getParentRevisionFor(entry))

    /**
     * Represents the content of a file at a specific jujutsu revision.
     *
     * When [revision] is [MergeParentOf], reconstructs the auto-merged parent tree content by
     * reverse-applying `jj diff --git -r <childRevision> -- <file>` to the file's content at
     * [MergeParentOf.childRevision]. This is necessary because `jj file show -r <firstParent>`
     * only returns the first parent's content, not the merge parent tree jj diffs against.
     */
    private inner class JujutsuContentRevision(private val filePath: FilePath, private val revision: Revision) :
        ContentRevision {
        override fun getContent(): String? {
            if (revision !is MergeParentOf) {
                val result = commandExecutor.show(filePath, revision)
                return result.stdout.takeIf { result.isSuccess }
            }
            val childRevision = revision.childRevision
            val afterContent = commandExecutor.show(filePath, childRevision).let {
                if (it.isSuccess) it.stdout else ""
            }
            val diffResult = commandExecutor.diffGitFile(childRevision, filePath)
            if (!diffResult.isSuccess || diffResult.stdout.isBlank()) return afterContent
            return GitDiffReverseApplier.reverseApply(afterContent, diffResult.stdout) ?: afterContent
        }

        override fun getFile() = filePath

        override fun getRevisionNumber() = JujutsuRevisionNumber(revision)
    }

    private inner class ContentLogEntryImpl(override val filePath: FilePath, override val logEntry: LogEntry) :
        ContentLogEntry {
        override fun getContent(): String? {
            val result = commandExecutor.show(filePath, logEntry.commitId)
            return result.stdout.takeIf { result.isSuccess }
        }
    }
}
