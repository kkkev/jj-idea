package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.CacheableAnnotationProvider
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import `in`.kkkev.jjidea.vcs.JujutsuVcs

/**
 * Provides file annotations (blame) for Jujutsu files
 */
class JujutsuAnnotationProvider(
    private val project: Project,
    private val vcs: JujutsuVcs
) : AnnotationProvider,
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
    override fun annotate(
        file: VirtualFile,
        revision: VcsFileRevision?
    ) = annotateInternal(file, revision?.revisionNumber?.asString()?.let(::ChangeId) ?: WorkingCopy)

    /**
     * Check if we can annotate this revision
     */
    override fun isAnnotationValid(rev: VcsFileRevision) = true

    /**
     * Internal method to perform annotation
     */
    private fun annotateInternal(
        file: VirtualFile,
        revision: Revision
    ): FileAnnotation {
        try {
            // Get the relative path from the root
            val relativePath = vcs.getRelativePath(VcsUtil.getFilePath(file))

            // Execute annotation command with template
            val result =
                vcs.commandExecutor.annotate(
                    filePath = relativePath,
                    revision = revision,
                    template = AnnotationParser.TEMPLATE
                )

            if (!result.isSuccess) {
                log.warn("Failed to annotate file: ${result.stderr}")
                throw VcsException("Failed to annotate file: ${result.stderr}")
            }

            // Parse the annotation output
            val annotationLines = AnnotationParser.parse(result.stdout)

            if (annotationLines.isEmpty()) {
                log.warn("No annotation data received for file: $relativePath")
                throw VcsException("No annotation data received for file: $relativePath")
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
