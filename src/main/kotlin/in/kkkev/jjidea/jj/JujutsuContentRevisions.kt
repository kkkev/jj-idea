package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.changes.MergeParentRevisionNumber

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

internal data class ContentLogEntryImpl(
    private val repo: JujutsuRepository,
    private val filePath: FilePath,
    private val changeId: ChangeId
) : ContentRevision {
    override fun getFile() = filePath

    override fun getRevisionNumber() = ChangeIdRevisionNumber(changeId)

    override fun getContent(): String? {
        val result = repo.commandExecutor.show(filePath, changeId)
        return result.stdout.takeIf { result is CommandExecutor.CommandResult.Success }
    }
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
