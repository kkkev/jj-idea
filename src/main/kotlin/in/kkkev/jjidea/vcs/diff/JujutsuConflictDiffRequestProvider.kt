package `in`.kkkev.jjidea.vcs.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProvider
import com.intellij.util.ThreeState
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import `in`.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractor
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

/**
 * Shows a conflicted [Change]'s three-way conflict content for the revision it actually belongs
 * to, instead of the platform's default of reading the working copy off disk (GitHub #119,
 * jj-idea-ct7e).
 *
 * ### The problem this pre-empts
 * `ChangeDiffRequestProducer.createRequest` routes any [com.intellij.openapi.vcs.FileStatus.MERGED_WITH_CONFLICTS]
 * change through `createMergeRequest`, which calls `vcs.getMergeProvider().loadRevisions(file)` -
 * a `VirtualFile`-only API with no revision parameter. [in.kkkev.jjidea.vcs.merge.JujutsuMergeProvider]
 * necessarily answers that from the working copy (it has nothing else to go on), so browsing the
 * log and opening a *different* conflicted commit's diff either fails outright (nothing conflicted
 * on disk right now) or - worse - silently shows the working copy's conflict mislabelled as the
 * clicked commit's.
 *
 * ### The fix
 * [ChangeDiffRequestProvider] is consulted before that default path
 * (`ChangeDiffRequestProducer.loadCurrentContents`) and, if [canCreate] returns true, replaces it
 * entirely. Here, [process] reads the conflict from the [Change]'s own after-revision -
 * [in.kkkev.jjidea.jj.ChangeService.loadChanges] already builds that revision correctly per commit
 * (`ContentLogEntryImpl` running `jj file show -r <rev>` for a historical commit,
 * `CurrentContentRevision` - i.e. disk - for the working copy) - so this single hook is
 * revision-correct for every case: the working copy itself, a descendant that inherited the
 * conflict, an unrelated clean commit, and an unrelated commit that happens to be conflicted
 * itself.
 *
 * This only replaces the *read-only* log/preview diff. Resolving a conflict
 * ([in.kkkev.jjidea.vcs.merge.JujutsuConflictResolver], the platform's own multi-file merge
 * dialog) still goes through [in.kkkev.jjidea.vcs.merge.JujutsuMergeProvider] and is still
 * working-copy-only by design - jj resolution writes to disk, so it's only ever offered for `@`
 * (see `resolveConflictsAvailability`'s `NEEDS_EDIT` state for every other commit).
 */
class JujutsuConflictDiffRequestProvider(
    private val extractor: ConflictExtractor = JjMarkerConflictExtractor(),
    private val repoFor: (Project, Change) -> JujutsuRepository? = { project, change ->
        project.possibleJujutsuRepositoryFor(change.filePath)
    },
    private val contentFor: (Project?, String, FilePath) -> DiffContent =
        { project, text, path -> DiffContentFactory.getInstance().create(project, text, path.fileType) }
) : ChangeDiffRequestProvider {
    private val log = Logger.getInstance(JujutsuConflictDiffRequestProvider::class.java)

    // Cache identity is left to the platform's default Change comparison (path/status/revision
    // equality, which relies on JujutsuContentRevisions' data-class equality - see its class doc,
    // jj-idea-q6vn) rather than asserted here.
    override fun isEquals(change1: Change, change2: Change) = ThreeState.UNSURE

    override fun canCreate(project: Project?, change: Change) =
        project != null &&
            ChangesUtil.isTextConflictingChange(change) &&
            change.afterRevision != null &&
            repoFor(project, change) != null

    override fun process(
        presentable: ChangeDiffRequestProducer,
        context: UserDataHolder,
        indicator: ProgressIndicator
    ): DiffRequest {
        val project = presentable.project
        val change = presentable.change
        val filePath = change.filePath
        val afterRevision = change.afterRevision

        val conflict = try {
            afterRevision?.content?.let { extractor.extract(it.toByteArray(Charsets.UTF_8)) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.info("Could not read conflict content for $filePath: ${e.message}")
            null
        }

        val title = JujutsuBundle.message("dialog.resolve.conflict.title", filePath.name)
        return conflict?.let { c ->
            val mergeData = c.mergeData
            val contents = listOf(mergeData.CURRENT, mergeData.ORIGINAL, mergeData.LAST).map {
                contentFor(project, String(it, Charsets.UTF_8), filePath)
            }
            val titles = listOf(
                c.currentTitle ?: JujutsuBundle.message("merge.column.side1"),
                JujutsuBundle.message("merge.column.base"),
                c.lastTitle ?: JujutsuBundle.message("merge.column.side2")
            )
            SimpleDiffRequest(title, contents, titles)
        }
            ?: // Binary conflict, unresolvable content, or the revision no longer has markers
            // (e.g. resolved since the log entry was loaded) - fall back to an ordinary two-side
            // diff rather than erroring out. Built directly from the Change's own revisions
            // rather than via ChangeDiffRequestProducer.createSimpleRequest: that method is
            // private on some floor IDE versions despite being public on the compile target,
            // which would throw IllegalAccessError at runtime there (caught by verifyPlugin).
            SimpleDiffRequest(
                title,
                this@JujutsuConflictDiffRequestProvider.contentForRevision(project, change.beforeRevision),
                this@JujutsuConflictDiffRequestProvider.contentForRevision(project, change.afterRevision),
                JujutsuBundle.message("diff.title.before"),
                JujutsuBundle.message("diff.title.after")
            )
    }

    private fun contentForRevision(project: Project?, revision: ContentRevision?): DiffContent {
        val text = revision?.content
        return if (text == null) EmptyContent() else contentFor(project, text, revision.file)
    }
}
