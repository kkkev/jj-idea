package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.CacheableAnnotationProvider
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.RevisionExpression
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.jujutsuRepository

/**
 * Provides file annotations (blame) for Jujutsu files
 */
class JujutsuAnnotationProvider(private val project: Project, private val vcs: JujutsuVcs) :
    AnnotationProvider,
    CacheableAnnotationProvider {
    private val log = Logger.getInstance(javaClass)
    private val cache = mutableMapOf<VirtualFile, FileAnnotation>()

    override fun populateCache(file: VirtualFile) {
        // Pre-load annotations in the background for caching
        try {
            val annotation = annotateInternal(file, WorkingCopy)
            cache[file] = annotation
        } catch (e: Exception) {
            log.warn("Failed to populate annotation cache for ${file.path}", e)
        }
    }

    override fun getFromCache(file: VirtualFile) = cache[file]

    /**
     * Annotate a file at the current working copy revision
     */
    override fun annotate(file: VirtualFile) = annotateInternal(file, WorkingCopy)

    /**
     * Annotate a file at a specific revision
     */
    override fun annotate(file: VirtualFile, revision: VcsFileRevision?) =
        annotateInternal(file, revision?.revisionNumber?.asString()?.let(::RevisionExpression) ?: WorkingCopy)

    /**
     * Check if we can annotate this revision
     */
    override fun isAnnotationValid(rev: VcsFileRevision) = true

    /**
     * Internal method to perform annotation
     */
    private fun annotateInternal(file: VirtualFile, revision: Revision): FileAnnotation {
        try {
            val repo = file.jujutsuRepository

            // Execute annotation command with template
            val result = repo.commandExecutor.annotate(file, revision, AnnotationParser.TEMPLATE)

            if (!result.isSuccess) {
                log.warn("Failed to annotate file: ${result.stderr}")
                throw VcsException("Failed to annotate file: ${result.stderr}")
            }

            // Parse the annotation output
            val annotationLines = AnnotationParser.parse(result.stdout)

            if (annotationLines.isEmpty()) {
                throw VcsException("No annotation data received for file: $file")
            }

            // Create and return the file annotation
            return JujutsuFileAnnotation(
                project = project,
                file = file,
                annotationLines = annotationLines,
                vcsKey = vcs.keyInstanceMethod
            )
        } catch (e: VcsException) {
            throw e
        } catch (e: Exception) {
            log.error("Error during annotation", e)
            throw VcsException("Failed to annotate file: ${e.message}", e)
        }
    }
}
