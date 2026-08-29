package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.ui.AsyncChangesTreeImpl
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingPolicyFactory
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport.Companion.DIRECTORY_GROUPING
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport.Companion.REPOSITORY_GROUPING
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.util.ui.ThreeStateCheckBox
import `in`.kkkev.jjidea.actions.filechange.fileChangeActionGroup
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import javax.swing.tree.DefaultTreeModel

/**
 * Changes tree for Jujutsu tool window using IntelliJ's built-in changes tree infrastructure.
 * Provides grouping, speed search, and standard VCS actions.
 *
 * @param groupConflicts when true, conflicted changes ([FileStatus.MERGED_WITH_CONFLICTS]) are
 * pulled out from the normal directory/repository grouping and shown under a single
 * [JujutsuConflictsNode] pinned to the top of the tree (GitHub #56: a reporter declined to use
 * this panel because, unlike the stock Commit tool window, it had no such grouping). Defaults to
 * false so the tree's other consumers (commit details pane, file-selection dialog, compare-changes
 * panel) are unaffected; only the Working Copy tool window opts in.
 */
class JujutsuChangesTree(
    project: Project,
    showCheckboxes: Boolean = false,
    private val groupConflicts: Boolean = false
) : AsyncChangesTreeImpl.Changes(project, showCheckboxes, true) {
    /**
     * Optional additional data provider to inject context-specific data keys.
     * Called from [uiDataSnapshot] to allow parent panels to provide context like
     * [in.kkkev.jjidea.actions.JujutsuDataKeys.LOG_ENTRY].
     */
    var additionalDataProvider: ((DataSink) -> Unit)? = null

    /**
     * Changes that should be shown as **partially** included (half-checked).
     *
     * A change in this set is still counted as included in the inclusion model (checked),
     * but the checkbox renders as [ThreeStateCheckBox.State.DONT_CARE] to signal that only
     * a subset of its hunks goes into the first commit. Parent/directory nodes that contain
     * at least one partial descendant also show DONT_CARE.
     *
     * Setting this property triggers a repaint. Clearing it (empty set) restores the
     * standard binary SELECTED/NOT_SELECTED rendering from the inclusion model.
     */
    var partialChanges: Set<Change> = emptySet()
        set(value) {
            field = value
            repaint()
        }

    /**
     * When set, every change belonging to a *different* repo is demoted into a collapsed
     * [JujutsuOtherRepositoriesNode] (jj-idea-xsa8). `null` (default): every repo grouped equally.
     *
     * Unlike [partialChanges], this changes tree structure, so the setter calls [rebuildTree]
     * directly — a pure repo-selection change doesn't change the underlying `changes` list, so
     * `setChangesToDisplay`'s [sameChangesAndStatuses] early-out wouldn't otherwise pick it up.
     */
    var currentRepo: JujutsuRepository? = null
        set(value) {
            if (field == value) return
            field = value
            rebuildTree()
        }

    override fun getNodeStatus(node: ChangesBrowserNode<*>): ThreeStateCheckBox.State {
        // Note: partialChanges may be null during superclass construction (called before Kotlin
        // property initialization completes), so we capture it and guard explicitly.
        val partial = partialChanges
        @Suppress("SENSELESS_COMPARISON")
        if (partial != null && partial.isNotEmpty() && node.traverseObjectsUnder().any { it in partial }) {
            return ThreeStateCheckBox.State.DONT_CARE
        }
        return super.getNodeStatus(node)
    }

    init {
        // Use KEEP_NON_EMPTY strategy: preserves user's manual expansion/collapse actions
        // while expanding default nodes (including new ones) when tree is rebuilt
        treeStateStrategy = KEEP_NON_EMPTY
    }

    override fun buildTreeModel(grouping: ChangesGroupingPolicyFactory, changes: List<Change>): DefaultTreeModel {
        // Note: groupConflicts/currentRepo may still read their JVM defaults if this is invoked
        // during superclass construction, before Kotlin property initialization completes - same
        // hazard as partialChanges above. Harmless here: it just means the very first (synthetic,
        // empty) model build is ungrouped, and every real rebuild after construction sees the
        // correct values.
        if (!groupConflicts && currentRepo == null) {
            return TreeModelBuilder.buildFromChanges(myProject, grouping, changes, null)
        }

        val (conflicted, rest) = if (groupConflicts) {
            changes.partition { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
        } else {
            emptyList<Change>() to changes
        }
        val builder = TreeModelBuilder(myProject, grouping)
        if (conflicted.isNotEmpty()) {
            builder.insertChanges(conflicted, builder.insertTagNode(JujutsuConflictsNode(myProject)), null)
        }

        val repo = currentRepo
        val (current, other) = partitionByCurrentRepo(rest, repo) {
            myProject.possibleJujutsuRepositoryFor(it.filePath)
        }
        if (repo != null && current.isEmpty()) {
            // Otherwise the bound repo shows nothing, easy to misread as broken.
            builder.insertSubtreeRoot(JujutsuNoChangesNode(repo))
        } else {
            builder.setChanges(current, null)
        }
        if (other.isNotEmpty()) {
            val otherRoot = builder.insertTagNode(JujutsuOtherRepositoriesNode())
            // Grouped by repo via insertSubtreeRoot (raw model insertion), not
            // insertChanges/insertChangeNode - this tree's directory/repository grouping-policy
            // chain nests directory *outside* repository, so letting it run here groups several
            // repos under their shared parent directory instead of by repo. Files within each
            // repo show flat (no directory sub-nesting) as the trade-off for bypassing it.
            other.groupBy { myProject.possibleJujutsuRepositoryFor(it.filePath) }
                .toList()
                .sortedBy { (otherRepo, _) -> otherRepo?.displayName.orEmpty() }
                .forEach { (otherRepo, repoChanges) ->
                    val target = if (otherRepo == null) {
                        otherRoot
                    } else {
                        JujutsuOtherRepositoryNode(otherRepo).also { builder.insertSubtreeRoot(it, otherRoot) }
                    }
                    repoChanges.sortedBy { it.filePath.path }.forEach { change ->
                        builder.insertSubtreeRoot(builder.createChangeNode(change, null), target)
                    }
                }
        }
        return builder.build()
    }

    override fun installGroupingSupport(): ChangesGroupingSupport {
        val support = ChangesGroupingSupport(myProject, this, false)

        // Initialize with directory and repository grouping by default for multi-root support
        val defaultGrouping = setOf(DIRECTORY_GROUPING, REPOSITORY_GROUPING)
        support.setGroupingKeysOrSkip(defaultGrouping)

        return support
    }

    override fun getToggleClickCount(): Int = 2 // Double-click to toggle

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink[VcsDataKeys.CHANGES] = selectedChanges.toTypedArray()
        additionalDataProvider?.invoke(sink)
    }

    fun installHandlers() {
        installPopupHandler(fileChangeActionGroup())
    }
}

/**
 * True if [a] and [b] represent the same set of changes, comparing each pair's
 * [Change.equals] (which only compares before/after [com.intellij.openapi.vcs.FilePath]s),
 * [Change.getFileStatus], *and* the before/after [ContentRevision]s themselves.
 *
 * The [FileStatus][com.intellij.openapi.vcs.FileStatus] check guards jj-idea-3cvb: a plain
 * [Change.equals] comparison would treat a file that transitioned MERGED_WITH_CONFLICTS ->
 * MODIFIED (same paths, resolved conflict) as unchanged, leaving stale conflict decoration in
 * the tree.
 *
 * The [ContentRevision] check guards jj-idea-4diu: selecting a different commit that happens to
 * touch the same paths with the same statuses (e.g. the same file modified in consecutive
 * commits) produces a [Change] list that is indistinguishable from the old one by path+status
 * alone, even though the revisions being diffed have moved on. Without this check the tree kept
 * the old selection's stale [Change] objects, so the diff preview kept showing the old
 * selection's diff even after the details panel's title had updated.
 *
 * Callers that rebuild a [JujutsuChangesTree]'s contents from a background refresh should skip
 * the rebuild when this returns true, to avoid discarding UI state such as tree expansion or the
 * diff preview's scroll position (jj-idea-q6vn).
 */
fun sameChangesAndStatuses(a: List<Change>, b: List<Change>): Boolean =
    a.size == b.size &&
        a.indices.all { i ->
            a[i] == b[i] &&
                a[i].fileStatus == b[i].fileStatus &&
                sameRevision(a[i].beforeRevision, b[i].beforeRevision) &&
                sameRevision(a[i].afterRevision, b[i].afterRevision)
        }

/**
 * True if [a] and [b] represent the same content revision. The plugin's own [ContentRevision]
 * implementations ([in.kkkev.jjidea.jj.ContentLogEntryImpl],
 * [in.kkkev.jjidea.jj.MergeParentContentRevision], [in.kkkev.jjidea.jj.EmptyContentRevisionImpl])
 * are `data class`es keyed on change id, so plain `==` already discriminates correctly. The one
 * wrinkle is [CurrentContentRevision] (the platform class used for the working-copy side), which
 * has no value equality; it's compared by [com.intellij.openapi.vcs.FilePath] instead, mirroring
 * `ChangeDiffRequestProducer.isEquals(ContentRevision, ContentRevision)`'s special case.
 *
 * Deliberately does *not* delegate to `ChangeDiffRequestProducer.isEquals`: that method iterates
 * `ChangeDiffViewerWrapperProvider`/`ChangeDiffRequestProvider` extension points and needs a live
 * `Application`, which would break this file's pure/unit-testable comparisons.
 */
private fun sameRevision(a: ContentRevision?, b: ContentRevision?): Boolean = when {
    a == b -> true
    a is CurrentContentRevision && b is CurrentContentRevision -> a.file == b.file
    else -> false
}

/**
 * Splits [changes] into (belonging to [currentRepo], everything else). `currentRepo == null`
 * means "don't split" — everything belongs. [repoFor] is injected so this stays pure and
 * unit-testable, unlike the real [in.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor] resolution
 * [JujutsuChangesTree.buildTreeModel] uses, which needs a live `Project`.
 */
internal fun <T> partitionByCurrentRepo(
    changes: List<T>,
    currentRepo: JujutsuRepository?,
    repoFor: (T) -> JujutsuRepository?
): Pair<List<T>, List<T>> =
    if (currentRepo == null) changes to emptyList() else changes.partition { repoFor(it) == currentRepo }
