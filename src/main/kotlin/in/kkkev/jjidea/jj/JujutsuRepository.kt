package `in`.kkkev.jjidea.jj

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.JujutsuDataKeys.DiffContentInfo
import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.util.GitDiffReverseApplier
import `in`.kkkev.jjidea.vcs.*
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.changes.MergeParentRevisionNumber
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
    fun getLogEntry(contentLocator: ContentLocator): LogEntry?
    fun getLogEntry(changeId: ChangeId) = getLogEntry(changeId as Revision)

    val workingCopy: LogEntry

    fun revisionNumberFor(filePath: FilePath): VcsRevisionNumber

    fun createContentRevision(filePath: FilePath, contentLocator: ContentLocator): ContentRevision
    fun createContentRevision(filePath: FilePath, logEntry: LogEntry): ContentRevision
    fun createContentRevision(fileAtVersion: FileAtVersion): ContentRevision

    fun createDiffSideFor(fileAtVersion: FileAtVersion?): DiffSide

    fun getVirtualFile(fileAtVersion: FileAtVersion): VirtualFile

    fun getRelativePath(filePath: FilePath): String
    fun getRelativePath(file: VirtualFile): String
}

data class JujutsuRepositoryImpl(
    override val project: Project,
    override val directory: VirtualFile,
    override val displayName: String
) : JujutsuRepository {
    private val executor: CommandExecutor by lazy { project.commandExecutorFactory.create(directory) }

    /**
     * Command executor for initialised repositories. Throws if repository is not initialised.
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

    override fun revisionNumberFor(filePath: FilePath) = when (val parent = workingCopy.parentContentLocator) {
        is MergeParentOf -> MergeParentRevisionNumber(parent.childRevision)
        is ChangeId -> ChangeIdRevisionNumber(parent)
        else -> throw VcsException("Cannot find revision number for $parent")
    }

    override fun createContentRevision(filePath: FilePath, contentLocator: ContentLocator): ContentRevision =
        when (contentLocator) {
            is WorkingCopy -> CurrentContentRevision(filePath)
            is MergeParentOf -> MergeParentContentRevision(filePath, contentLocator)
            is ChangeId -> ContentLogEntryImpl(filePath, contentLocator)
            is ContentLocator.Empty -> EmptyContentRevisionImpl(filePath)
        }

    override fun createContentRevision(filePath: FilePath, logEntry: LogEntry): ContentRevision =
        if (logEntry.isWorkingCopy) {
            CurrentContentRevision(filePath)
        } else {
            ContentLogEntryImpl(filePath, logEntry.id)
        }

    override fun createContentRevision(fileAtVersion: FileAtVersion) =
        createContentRevision(fileAtVersion.filePath, fileAtVersion.contentLocator)

    override fun getLogEntry(revision: Revision) = logService.getLog(revision).getOrThrow().singleOrNull()
        ?: throw VcsException("Multiple log entries found for revision $revision")

    override fun getLogEntry(contentLocator: ContentLocator) = (contentLocator as? Revision)?.let(this::getLogEntry)

    override val workingCopy: LogEntry
        get() = project.stateModel.workingCopies.value[directory.path]
            ?: throw VcsException("Working copy not found for $this")

    override fun createDiffSideFor(fileAtVersion: FileAtVersion?): DiffSide =
        DiffSideImpl(fileAtVersion?.let(this::getVirtualFile))

    override fun getVirtualFile(fileAtVersion: FileAtVersion) = if (getLogEntry(
            fileAtVersion.contentLocator
        )?.isWorkingCopy ==
        true
    ) {
        fileAtVersion.filePath.virtualFile ?: throw VcsException("Cannot find virtual file for $fileAtVersion")
    } else {
        JujutsuVirtualFile(fileAtVersion, this)
    }

    /**
     * Represents the content of a file prior to a merge.
     */
    private inner class MergeParentContentRevision(
        private val filePath: FilePath,
        private val mergeParentOf: MergeParentOf
    ) : ContentRevision {
        /**
         * Reconstructs the auto-merged parent tree content by
         * reverse-applying `jj diff --git -r <childRevision> -- <file>` to the file's content at
         * [MergeParentOf.childRevision]. This is necessary because `jj file show -r <firstParent>`
         * only returns the first parent's content, not the merge parent tree jj diffs against.
         */
        override fun getContent(): String {
            val childRevision = mergeParentOf.childRevision
            val afterContent = commandExecutor.show(filePath, childRevision).let {
                if (it.isSuccess) it.stdout else ""
            }
            val diffResult = commandExecutor.diffGitFile(childRevision, filePath)
            if (!diffResult.isSuccess || diffResult.stdout.isBlank()) return afterContent
            return GitDiffReverseApplier.reverseApply(afterContent, diffResult.stdout) ?: afterContent
        }

        override fun getFile() = filePath

        override fun getRevisionNumber() = MergeParentRevisionNumber(mergeParentOf.childRevision)
    }

    private inner class ContentLogEntryImpl(private val filePath: FilePath, private val changeId: ChangeId) :
        ContentRevision {
        override fun getFile() = filePath

        override fun getRevisionNumber() = ChangeIdRevisionNumber(changeId)

        override fun getContent(): String? {
            val result = commandExecutor.show(filePath, changeId)
            return result.stdout.takeIf { result.isSuccess }
        }
    }

    private inner class DiffSideImpl(val file: VirtualFile?) : DiffSide {
        init {
            file?.cacheContents()
        }

        override val content = createDiffContentFor(file) ?: EmptyContent()

        override val title
            get() = file?.let { "${it.name} (${it.contentLocator.title})" }
                ?: JujutsuBundle.message("diff.label.empty")

        private fun createDiffContentFor(file: VirtualFile?): DiffContent? {
            val logEntry = file?.let { project.possibleLogEntryFor(it) ?: workingCopy }
            return when {
                logEntry == null -> null
                logEntry.isWorkingCopy -> {
                    val contentFactory = DiffContentFactory.getInstance()
                    if (file.exists()) {
                        contentFactory.create(project, file)
                    } else {
                        contentFactory.createEmpty()
                    }
                }

                else -> {
                    val filePath = file.filePath
                    createContentRevision(filePath, logEntry).content?.let { content ->
                        DiffContentFactory.getInstance().create(project, content, filePath.fileType).apply {
                            putUserData(
                                JujutsuDataKeys.DIFF_CONTENT_INFO,
                                DiffContentInfo(logEntry.repo, filePath, logEntry.commitId)
                            )
                        }
                    }
                }
            }
        }
    }

    private class EmptyContentRevisionImpl(private val filePath: FilePath) : ContentRevision {
        override fun getFile() = filePath
        override fun getContent() = null
        override fun getRevisionNumber() = dummyRevisionNumber(ContentLocator.Empty.title)
    }
}

private fun dummyRevisionNumber(title: String) = object : VcsRevisionNumber {
    override fun asString() = title
    override fun toString() = title

    override fun compareTo(other: VcsRevisionNumber?) = when {
        other === this -> 0
        else -> this.toString().compareTo(other.toString())
    }
}
