package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.CacheableAnnotationProvider
import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.jj.ContentLocator
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.MergeParentOf
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import `in`.kkkev.jjidea.jj.reconstructMergeParentContent
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.contentLocator
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.history.JujutsuFileRevision
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import java.util.concurrent.CancellationException

/**
 * Provides file annotations (blame) for Jujutsu files
 */
class JujutsuAnnotationProvider(private val project: Project, private val vcs: JujutsuVcs) :
    AnnotationProvider,
    CacheableAnnotationProvider {
    private val log = Logger.getInstance(javaClass)
    private val cache = mutableMapOf<VirtualFile, FileAnnotation>()

    override fun populateCache(file: VirtualFile) {
        if (project.isDisposed) return
        // Skip files that have no jj history: ignored files and unversioned files both lack
        // a parent-revision entry, so jj annotate fails with "No such path".
        val status = ChangeListManager.getInstance(project).getStatus(file)
        if (status == FileStatus.IGNORED || status == FileStatus.UNKNOWN) return
        try {
            cache[file] = annotate(file)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to populate annotation cache for ${file.path}", e)
        }
    }

    override fun getFromCache(file: VirtualFile) = cache[file]

    /**
     * Annotate a file at the working copy parent (@-), matching the LineStatusTracker base.
     * Lines changed in @ appear unannotated; IntelliJ's UpToDateLineNumberProvider handles the mapping.
     */
    override fun annotate(file: VirtualFile): FileAnnotation {
        val repo = project.jujutsuRepositoryFor(file)

        // 1. Find the content locator
        val contentLocator = file.contentLocator

        // 2. Find the change object
        val change = repo.logService.getFileChanges(repo.getLogEntry(contentLocator) ?: repo.workingCopy, file.filePath)
            .getOrNull()?.firstOrNull()

        // 3. Locate the revision and file path of the before
        // TODO: If we can't find the log entry, it's probably a merge parent - so default to parent for now
        // If there is no change, then can just use the parent
        val before = change?.before ?: FileAtVersion(file.filePath, repo.workingCopy.parentContentLocator)
        val beforeFile = repo.getVirtualFile(before)
            ?: throw VcsException("Cannot find virtual file for $before")

        // TODO Or for a rename, the filename would have changed
        // If we get this information from a change... that's great... but we can annotate files too
        // In that case, we need to find the change object from a working copy virtual file
        (before.contentLocator as? MergeParentOf)?.let { mergeParentOf ->
            annotateMerge(beforeFile, mergeParentOf, repo)?.let { return it }
        }

        val beforeRevision = beforeRevisionFor(before.contentLocator, contentLocator, repo)
        return annotateInternal(beforeFile, beforeRevision, repo)
    }

    /**
     * Annotates a merge commit's auto-merged parent tree by annotating each real parent and
     * reconciling their blame via [MergeAnnotationReconciler], so the resulting line count
     * matches the actual resolved file (only genuine conflict-resolution edits show as
     * unattributed, rather than the whole file diverging from one arbitrary parent).
     *
     * A parent that doesn't have the file at all (e.g. it was added on only one side of a
     * criss-cross merge) is skipped rather than aborting the whole reconciliation — jj correctly
     * reports "No such path" for that parent, but the *other* parent(s) can still supply blame.
     *
     * Returns null if reconstructing the merge tree fails, or if *no* parent could be annotated,
     * so the caller can fall back to [beforeRevisionFor]'s arbitrary-first-parent behavior rather
     * than failing outright.
     */
    internal fun annotateMerge(
        file: VirtualFile,
        mergeParentOf: MergeParentOf,
        repo: JujutsuRepository
    ): FileAnnotation? {
        val childRevision = mergeParentOf.childRevision
        return try {
            val mergeCommit = repo.getLogEntry(childRevision)
            val mergeContent = repo.reconstructMergeParentContent(childRevision, file.filePath)
            val parentAnnotations = mergeCommit.parentIds.mapNotNull { parentId ->
                annotationLinesOrNull(file, parentId, repo, childRevision)
            }
            if (parentAnnotations.isEmpty()) {
                log.warn("No parent of merge $childRevision could be annotated for $file, falling back to first parent")
                return null
            }
            val annotationLines = MergeAnnotationReconciler.reconcile(mergeContent, mergeCommit, parentAnnotations)

            JujutsuFileAnnotation(
                project = project,
                repo = repo,
                file = file,
                annotationLines = annotationLines,
                vcsKey = vcs.keyInstanceMethod,
                workingCopyChangeId = repo.workingCopy.id
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to reconcile merge annotation for $childRevision, falling back to first parent", e)
            null
        }
    }

    /** [getAnnotationLines], but null (rather than throwing) if this specific parent lacks the file. */
    private fun annotationLinesOrNull(
        file: VirtualFile,
        parentId: Revision,
        repo: JujutsuRepository,
        childRevision: Revision
    ): List<AnnotationLine>? = try {
        getAnnotationLines(file, parentId, repo)
    } catch (e: VcsException) {
        log.warn("Failed to annotate parent $parentId of merge $childRevision, treating as absent in that parent", e)
        null
    }

    /**
     * `jj file annotate` requires a single revision, but a merge commit's "before" is
     * [MergeParentOf] — a synthetic reconstruction of the auto-merged tree, not a real revision
     * jj can annotate against. Used as a fallback (arbitrary first parent) when
     * [annotateMerge] can't reconcile a full multi-parent annotation.
     */
    internal fun beforeRevisionFor(
        beforeLocator: ContentLocator,
        contentLocator: ContentLocator,
        repo: JujutsuRepository
    ): Revision {
        (beforeLocator as? Revision)?.let { return it }
        (beforeLocator as? MergeParentOf)?.let { mergeParent ->
            repo.getLogEntry(mergeParent.childRevision).parentIds.firstOrNull()?.let { return it }
        }
        return ((contentLocator as? Revision) ?: WorkingCopy).parent
    }

    /** Annotate a file at a specific revision (used for "Annotate This/Previous Revision"). */
    override fun annotate(file: VirtualFile, revision: VcsFileRevision?): FileAnnotation {
        val repo = project.jujutsuRepositoryFor(file)
        val revisionId = (revision as? JujutsuFileRevision)?.entry?.id
            ?: (revision?.revisionNumber as? ChangeIdRevisionNumber)?.changeId
            ?: repo.workingCopy.id
        return annotateInternal(file, revisionId, repo)
    }

    override fun isAnnotationValid(rev: VcsFileRevision) = true

    /** Runs `jj file annotate` for a single revision and parses the result. */
    private fun getAnnotationLines(
        file: VirtualFile,
        revision: Revision,
        repo: JujutsuRepository
    ): List<AnnotationLine> {
        val result = repo.commandExecutor.annotate(file, revision, AnnotationParser.TEMPLATE)
        if (!result.isSuccess) {
            log.warn("Failed to annotate file: ${result.stderr}")
            throw VcsException("Failed to annotate file: ${result.stderr}")
        }
        return AnnotationParser.parse(result.stdout)
    }

    internal fun annotateInternal(
        file: VirtualFile,
        revision: Revision,
        repo: JujutsuRepository
    ): FileAnnotation = try {
        val annotationLines = getAnnotationLines(file, revision, repo)

        JujutsuFileAnnotation(
            project = project,
            repo = repo,
            file = file,
            annotationLines = annotationLines,
            vcsKey = vcs.keyInstanceMethod,
            workingCopyChangeId = repo.workingCopy.id
        )
    } catch (e: VcsException) {
        throw e
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: CancellationException) {
        // ContainerDisposedException (raised when the working copy / state model is queried while the
        // project is being disposed on window close) is a CancellationException, not a
        // ProcessCanceledException. Rethrow control-flow exceptions rather than logging them —
        // Logger.error() rethrows them anyway and reports a spurious error in the process.
        throw e
    } catch (e: Exception) {
        log.error("Error during annotation", e)
        throw VcsException("Failed to annotate file: ${e.message}", e)
    }
}
