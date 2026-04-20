package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.CacheableAnnotationProvider
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.RevisionExpression
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.vcs.JujutsuVcs
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
            val repo = project.jujutsuRepositoryFor(file)
            cache[file] = annotateInternal(file, repo.workingCopyParent(), repo)
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
        return annotateInternal(file, repo.workingCopyParent(), repo)
    }

    /**
     * Annotate a file at a specific revision (used for "Annotate This/Previous Revision").
     */
    override fun annotate(file: VirtualFile, revision: VcsFileRevision?) =
        annotateInternal(file, revision?.revisionNumber?.asString()?.let(::RevisionExpression) ?: WorkingCopy)

    override fun isAnnotationValid(rev: VcsFileRevision) = true

    private fun annotateInternal(
        file: VirtualFile,
        revision: Revision,
        repo: JujutsuRepository? = null
    ): FileAnnotation {
        try {
            val resolvedRepo = repo ?: project.jujutsuRepositoryFor(file)
            val result = resolvedRepo.commandExecutor.annotate(file, revision, AnnotationParser.TEMPLATE)

            if (!result.isSuccess) {
                log.warn("Failed to annotate file: ${result.stderr}")
                throw VcsException("Failed to annotate file: ${result.stderr}")
            }

            val annotationLines = AnnotationParser.parse(result.stdout)

            val wcChangeId = project.stateModel.repositoryStates.value
                .find { it.repo == resolvedRepo && it.isWorkingCopy }?.id

            return JujutsuFileAnnotation(
                project = project,
                repo = resolvedRepo,
                file = file,
                annotationLines = annotationLines,
                vcsKey = vcs.keyInstanceMethod,
                workingCopyChangeId = wcChangeId
            )
        } catch (e: VcsException) {
            throw e
        } catch (e: Exception) {
            log.error("Error during annotation", e)
            throw VcsException("Failed to annotate file: ${e.message}", e)
        }
    }
}
