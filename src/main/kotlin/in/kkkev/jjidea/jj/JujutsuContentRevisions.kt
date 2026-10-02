package `in`.kkkev.jjidea.jj

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.changes.MergeParentRevisionNumber

private val contentRevisionLog = Logger.getInstance("in.kkkev.jjidea.jj.JujutsuContentRevisions")

/**
 * [ContentRevision] implementations for [JujutsuRepository.createContentRevision].
 *
 * These are `data class`es (not inner classes of `JujutsuRepositoryImpl`) so that they get
 * value-based `equals`/`hashCode`. The platform's diff request cache
 * (`CacheDiffRequestProcessor`/`ChangeDiffRequestProducer`) keys on `Change`, which in turn
 * compares its before/after `ContentRevision`s with `equals` (special-casing only
 * `CurrentContentRevision`, which the platform itself provides). Without value equality here,
 * every background refresh that reconstructs an otherwise-identical `Change` produces a cache
 * miss, forcing the diff viewer to rebuild and reset its scroll position (jj-idea-q6vn).
 */

/**
 * Represents the content of a file prior to a merge.
 */
internal data class MergeParentContentRevision(
    private val repo: JujutsuRepository,
    private val filePath: FilePath,
    private val mergeParentOf: MergeParentOf
) : ContentRevision {
    override fun getContent() = repo.reconstructMergeParentContent(mergeParentOf.childRevision, filePath)

    override fun getFile() = filePath

    override fun getRevisionNumber() = MergeParentRevisionNumber(mergeParentOf.childRevision)
}

/**
 * A file's content at a historical (non-working-copy) change.
 *
 * Implements [ByteBackedContentRevision] so the platform's diff machinery
 * (`ChangeDiffRequestProducer`) loads the exact bytes and decides for itself whether the file is
 * text, an image, or some other binary - rather than receiving [getContent]'s UTF-8-decoded
 * string, which is lossy for binary files and made images render as garbled text.
 */
internal data class ContentLogEntryImpl(
    private val repo: JujutsuRepository,
    private val filePath: FilePath,
    private val changeId: ChangeId
) : ByteBackedContentRevision {
    override fun getFile() = filePath

    override fun getRevisionNumber() = ChangeIdRevisionNumber(changeId)

    /** Text view for text-only callers (annotate, line-status trackers); binary content is not meaningful here. */
    override fun getContent(): String? {
        val result = repo.commandExecutor.show(filePath, changeId)
        return result.stdout.takeIf { result is CommandExecutor.CommandResult.Success }
    }

    override fun getContentAsBytes(): ByteArray = repo.commandExecutor.showBytes(filePath, changeId)
}

/**
 * This revision's exact content bytes, or null if it has no content or can't be loaded.
 *
 * Prefers [ByteBackedContentRevision.getContentAsBytes] so binary files (images etc.) survive
 * intact; only text-only revisions (e.g. [MergeParentContentRevision]'s reconstruction) fall back
 * to encoding [ContentRevision.getContent]. Use this, never `content?.toByteArray()`, wherever
 * revision content becomes bytes.
 */
internal fun ContentRevision.contentBytes(): ByteArray? = try {
    if (this is ByteBackedContentRevision) contentAsBytes else content?.toByteArray()
} catch (e: VcsException) {
    contentRevisionLog.warn("Could not load content of ${file.path} at ${revisionNumber.asString()}", e)
    null
}

internal data class EmptyContentRevisionImpl(private val filePath: FilePath) : ContentRevision {
    override fun getFile() = filePath
    override fun getContent() = null
    override fun getRevisionNumber() = dummyRevisionNumber(ContentLocator.Empty.title)
}

private fun dummyRevisionNumber(title: String) = object : VcsRevisionNumber {
    override fun asString() = title
    override fun toString() = title

    override fun compareTo(other: VcsRevisionNumber?) = when {
        other === this -> 0
        else -> this.toString().compareTo(other.toString())
    }
}
