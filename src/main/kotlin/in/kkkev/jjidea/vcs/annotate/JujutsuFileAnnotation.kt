package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect
import com.intellij.openapi.vcs.annotate.LineAnnotationAspectAdapter
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsFileRevisionEx
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.util.VcsUserUtil
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.components.DateTimeFormatter
import `in`.kkkev.jjidea.ui.log.JujutsuCustomLogTabManager
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.filePath
import kotlinx.datetime.Instant
import java.util.*

/**
 * File annotation for Jujutsu, showing line-by-line change information
 */
class JujutsuFileAnnotation(
    project: Project,
    private val repo: JujutsuRepository,
    private val file: VirtualFile,
    private val annotationLines: List<AnnotationLine>,
    private val vcsKey: VcsKey,
    private val workingCopyChangeId: ChangeId? = null
) : FileAnnotation(project) {
    private val log = Logger.getInstance(javaClass)

    override fun getFile() = file

    override fun getLineRevisionNumber(lineNumber: Int) = getAnnotationLine(lineNumber)
        ?.id
        ?.let(::ChangeIdRevisionNumber)

    override fun getToolTip(lineNumber: Int): String? = null

    override fun getHtmlToolTip(lineNumber: Int) = getAnnotationLine(lineNumber)?.getHtmlTooltip()

    override fun getLineDate(lineNumber: Int) = getAnnotationLine(lineNumber)?.authorTimestamp?.toJavaDate()

    override fun getVcsKey() = vcsKey

    override fun getAspects(): Array<LineAnnotationAspect> = arrayOf(
        ChangeIdAspect(),
        AuthorAspect(),
        AuthorInstantAspect(),
        DescriptionAspect()
    )

    override fun getLineCount() = annotationLines.size

    override fun getAnnotatedContent() = annotationLines.joinToString("\n") { it.lineContent }

    override fun getCurrentRevision() = workingCopyChangeId?.let(::ChangeIdRevisionNumber)

    override fun getRevisions(): List<VcsFileRevision> = annotationLines
        .distinctBy { it.id }
        .sortedByDescending { it.authorTimestamp }
        .map { AnnotationFileRevision(it, file, repo) }

    override fun getAuthorsMappingProvider() = AuthorsMappingProvider {
        annotationLines
            .distinctBy { it.id }
            .associate { ChangeIdRevisionNumber(it.id) as VcsRevisionNumber to it.author.name }
    }

    override fun getRevisionsOrderProvider() = RevisionsOrderProvider {
        annotationLines
            .distinctBy { it.id }
            .sortedByDescending { it.authorTimestamp }
            .map { listOf(ChangeIdRevisionNumber(it.id) as VcsRevisionNumber) }
    }

    /**
     * "Previous" is the ancestrally correct parent of the line's own change, not merely the
     * next entry in some ordering (the platform default, which the line-order/timestamp-order
     * of [getRevisions] cannot support correctly). A merge commit has multiple parents with no
     * single correct "previous", so those lines decline (return null) rather than guess.
     */
    override fun getPreviousFileRevisionProvider() = object : PreviousFileRevisionProvider {
        override fun getPreviousRevision(lineNumber: Int): VcsFileRevision? {
            val parentIds = getAnnotationLine(lineNumber)?.parentIds ?: return null
            return parentIds.singleOrNull()?.let { ParentFileRevision(it, file, repo) }
        }

        override fun getLastRevision(): VcsFileRevision? =
            workingCopyChangeId?.let { ParentFileRevision(it, file, repo) } ?: getRevisions().firstOrNull()
    }

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
            log.info("Annotation clicked for line $lineNumber, change ID: ${line.id}")
            project.stateModel.changeSelection.notify(ChangeKey(repo, line.id))
            JujutsuCustomLogTabManager.getInstance(project).activateLogTab()
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

        override fun showAffectedPaths(lineNum: Int) = handleAnnotationClick(lineNum)
    }

    private inner class ChangeIdAspect : Aspect(
        "change-id",
        JujutsuBundle.message("annotation.aspect.change"),
        true,
        { it.id.short }
    ) {
        override fun getColor(line: Int) = CHANGE_ID_COLOR_KEY
    }

    companion object {
        val CHANGE_ID_COLOR_KEY: ColorKey = ColorKey.createColorKey(
            "JUJUTSU_ANNOTATION_CHANGE_ID",
            JujutsuColors.WORKING_COPY
        )
    }

    private inner class AuthorAspect : Aspect(
        AUTHOR,
        JujutsuBundle.message("annotation.aspect.author"),
        true,
        { VcsUserUtil.toExactString(it.author) }
    )

    private inner class AuthorInstantAspect : Aspect(
        "author-instant",
        JujutsuBundle.message("annotation.aspect.authordate"),
        true,
        { it.authorTimestamp?.let(DateTimeFormatter::formatAbsolute) }
    )

    private inner class DescriptionAspect : Aspect(
        "description",
        JujutsuBundle.message("annotation.aspect.description"),
        false,
        { it.description.summary },
        JujutsuBundle.message("description.empty")
    )
}

private class AnnotationFileRevision(
    private val line: AnnotationLine,
    private val virtualFile: VirtualFile,
    private val repo: JujutsuRepository
) : VcsFileRevisionEx() {
    override fun getRevisionNumber(): VcsRevisionNumber = ChangeIdRevisionNumber(line.id)
    override fun getRevisionDate() = line.authorTimestamp?.toJavaDate()
    override fun getAuthorDate() = line.authorTimestamp?.toJavaDate()
    override fun getAuthor() = line.author.name
    override fun getAuthorEmail() = line.author.email
    override fun getCommitMessage() = line.description.display
    override fun getBranchName(): String? = null
    override fun getCommitterName(): String? = null
    override fun getCommitterEmail(): String? = null
    override fun getChangedRepositoryPath() = null
    override fun getPath() = VcsUtil.getFilePath(virtualFile)
    override fun isDeleted() = false

    @Throws(VcsException::class)
    override fun loadContent(): ByteArray {
        val future = runInBackground { repo.commandExecutor.show(virtualFile.filePath, line.id) }
        val result = ProgressIndicatorUtils.awaitWithCheckCanceled(future)
        if (!result.isSuccess) throw VcsException("Failed to load content at ${line.id}: ${result.stderr}")
        return result.stdout.toByteArray()
    }

    @Throws(VcsException::class)
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getContent(): ByteArray = loadContent()
}

/**
 * A revision known only by its [ChangeId] (e.g. a parent change with no annotation metadata
 * of its own). Used as the "previous revision" target for re-annotation.
 */
private class ParentFileRevision(
    private val changeId: ChangeId,
    private val virtualFile: VirtualFile,
    private val repo: JujutsuRepository
) : VcsFileRevisionEx() {
    override fun getRevisionNumber(): VcsRevisionNumber = ChangeIdRevisionNumber(changeId)
    override fun getRevisionDate(): Date? = null
    override fun getAuthorDate(): Date? = null
    override fun getAuthor(): String? = null
    override fun getAuthorEmail(): String? = null
    override fun getCommitMessage(): String? = null
    override fun getBranchName(): String? = null
    override fun getCommitterName(): String? = null
    override fun getCommitterEmail(): String? = null
    override fun getChangedRepositoryPath() = null
    override fun getPath() = VcsUtil.getFilePath(virtualFile)
    override fun isDeleted() = false

    @Throws(VcsException::class)
    override fun loadContent(): ByteArray {
        val future = runInBackground { repo.commandExecutor.show(virtualFile.filePath, changeId) }
        val result = ProgressIndicatorUtils.awaitWithCheckCanceled(future)
        if (!result.isSuccess) throw VcsException("Failed to load content at $changeId: ${result.stderr}")
        return result.stdout.toByteArray()
    }

    @Throws(VcsException::class)
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getContent(): ByteArray = loadContent()
}

fun Instant.toJavaDate() = Date(this.toEpochMilliseconds())
