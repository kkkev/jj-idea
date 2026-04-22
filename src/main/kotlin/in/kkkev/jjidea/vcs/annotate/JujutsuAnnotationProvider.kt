package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.CacheableAnnotationProvider
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.contentLocator
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.history.JujutsuFileRevision
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor

/**
 * Provides file annotations (blame) for Jujutsu files
 */
class JujutsuAnnotationProvider(private val project: Project, private val vcs: JujutsuVcs) :
    AnnotationProvider,
    CacheableAnnotationProvider {
    private val log = Logger.getInstance(javaClass)
    private val cache = mutableMapOf<VirtualFile, FileAnnotation>()

    override fun populateCache(file: VirtualFile) {
        try {
            cache[file] = annotate(file)
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
        val beforeRevision = (before.contentLocator as? Revision)
            ?: ((contentLocator as? Revision) ?: WorkingCopy).parent

        // For the purpose of annotating, pick an arbitrary parent (the first one)
        // TODO What happens if the file has merged from multiple parents?
        // TODO Or for a rename, the filename would have changed
        // If we get this information from a change... that's great... but we can annotate files too
        // In that case, we need to find the change object from a working copy virtual file
        // MergeParentOf will need its own special handling - which will be complex. We would probably need to
        // annotate all of the parents, then compare these to the merge parent (reverse diff), copying annotations
        // across for all lines that match.
        return annotateInternal(beforeFile, beforeRevision, repo)
    }

    /**
     * Annotate a file at a specific revision (used for "Annotate This/Previous Revision").
     */
    override fun annotate(file: VirtualFile, revision: VcsFileRevision?): FileAnnotation {
        val repo = project.jujutsuRepositoryFor(file)
        return annotateInternal(
            file,
            (revision as? JujutsuFileRevision)?.entry?.id ?: repo.workingCopy.id,
            repo
        )
    }

    override fun isAnnotationValid(rev: VcsFileRevision) = true

    private fun annotateInternal(
        file: VirtualFile,
        revision: Revision,
        repo: JujutsuRepository
    ): FileAnnotation = try {
        val result = repo.commandExecutor.annotate(file, revision, AnnotationParser.TEMPLATE)

        if (!result.isSuccess) {
            log.warn("Failed to annotate file: ${result.stderr}")
            throw VcsException("Failed to annotate file: ${result.stderr}")
        }

        val annotationLines = AnnotationParser.parse(result.stdout)

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
    } catch (e: Exception) {
        log.error("Error during annotation", e)
        throw VcsException("Failed to annotate file: ${e.message}", e)
    }
}
