package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect
import com.intellij.openapi.vcs.annotate.LineAnnotationAspectAdapter
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.text.DateFormatUtil
import com.intellij.vcs.log.impl.VcsLogNavigationUtil.jumpToRevisionAsync
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import kotlinx.datetime.Instant
import java.util.*

/**
 * File annotation for Jujutsu, showing line-by-line change information
 */
class JujutsuFileAnnotation(
    project: Project,
    private val file: VirtualFile,
    private val annotationLines: List<AnnotationLine>,
    private val vcsKey: VcsKey
) : FileAnnotation(project) {

    private val log = Logger.getInstance(JujutsuFileAnnotation::class.java)

    override fun getFile() = file

    override fun getLineRevisionNumber(lineNumber: Int) = getAnnotationDetail(lineNumber) { it.changeId.short }
        ?.let(::JujutsuRevisionNumber)

    override fun getToolTip(lineNumber: Int) = getAnnotationLine(lineNumber)?.getTooltip()

    override fun getLineDate(lineNumber: Int) = getAnnotationLine(lineNumber)?.authorTimestamp?.toJavaDate()

    override fun getVcsKey() = vcsKey

    override fun getAspects(): Array<LineAnnotationAspect> = arrayOf(
        ChangeIdAspect(), AuthorAspect(), AuthorInstantAspect(), DescriptionAspect()
    )

    override fun getLineCount() = annotationLines.size

    override fun getAnnotatedContent() = annotationLines.joinToString("\n") { it.lineContent }

    // For Jujutsu, the current revision is typically the working copy commit (@)
    override fun getCurrentRevision() = JujutsuRevisionNumber("@")

    // TODO Should this integrate with JujutsuHistoryProvider?
    override fun getRevisions() = null

    /**
     * Override to prevent EDT slow operations.
     * The default implementation calls ProjectLevelVcsManager.getVcsFor() which performs
     * slow file system checks (like isIgnored()) on EDT, causing SlowOperations warnings.
     * Returning null disables the "Show Diff" feature from annotation gutter.
     */
    override fun getRevisionsChangesProvider(): Nothing? = null

    /**
     * Handle click on annotation - open the change in the VCS Log tool window
     */
    fun handleAnnotationClick(lineNumber: Int) {
        getAnnotationLine(lineNumber)?.let { line ->
            log.info("Annotation clicked for line $lineNumber, change ID: ${line.changeId}")

            jumpToRevisionAsync(project, file, line.changeId.hash, VcsUtil.getFilePath(file.path, false))
        }
    }

    /**
     * Get annotation line by line number (0-indexed)
     */
    private fun getAnnotationLine(lineNumber: Int) = annotationLines.getOrNull(lineNumber)

    private fun getAnnotationDetail(
        lineNumber: Int,
        defaultValue: String? = null,
        extractor: (AnnotationLine) -> String?
    ) = getAnnotationLine(lineNumber)?.let(extractor)?.ifEmpty { defaultValue }

    private abstract inner class Aspect(
        id: String,
        displayName: String,
        showByDefault: Boolean = false,
        private val extractor: (AnnotationLine) -> String?,
        private val defaultValue: String? = null
    ) : LineAnnotationAspectAdapter(id, displayName, showByDefault) {
        override fun getValue(line: Int) = getAnnotationDetail(line, defaultValue, extractor)

        override fun showAffectedPaths(lineNum: Int) {
            // Handle click on the change ID
            handleAnnotationClick(lineNum)
        }
    }

    private inner class ChangeIdAspect : Aspect("change-id", "Change", false, { it.changeId.short })
    private inner class AuthorAspect : Aspect("author", "Author", true, { it.author.name })
    private inner class AuthorInstantAspect : Aspect(
        "author-instant",
        "Author date/time",
        true,
        { it.authorTimestamp?.let(::formatPrettyDate) })

    private inner class DescriptionAspect : Aspect(
        "description", "Description", false, { it.descriptionFirstLine },
        // TODO Localise this
        "(no description)"
    )
}

fun Instant.toJavaDate() = Date(this.toEpochMilliseconds())
fun formatPrettyDate(instant: Instant) = DateFormatUtil.formatPrettyDate(instant.toJavaDate())