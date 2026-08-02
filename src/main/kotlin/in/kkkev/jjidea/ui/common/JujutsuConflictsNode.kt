package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.TagChangesBrowserNode
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.FontUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.resolveConflicts
import javax.swing.JTree

/**
 * A single, stable tag used for every [JujutsuConflictsNode] instance so the platform's node
 * bookkeeping (e.g. [ChangesBrowserNode.isUnderTag]) treats them as the same logical group.
 */
private val CONFLICTS_TAG: ChangesBrowserNode.Tag = object : ChangesBrowserNode.Tag {}

/**
 * Groups conflicted files at the top of the Working Copy changes tree, mirroring the platform's
 * own "Merge Conflicts" node in the (now largely hidden, see jj-idea-wb5l) stock Commit tool
 * window - see GitHub #56, where a reporter declined to switch to the Working Copy panel because
 * it lacked this affordance and instead required hunting for red files one at a time.
 *
 * Deliberately does not extend the platform's `@ApiStatus.Internal` `ChangesBrowserConflictsNode`:
 * that class's "Resolve" link routes through `ConflictsResolutionService`, which ultimately calls
 * `AbstractVcsHelper.showMergeDialog` - the exact call GitHub #63 found to silently discard a side
 * of a jj conflict on cancel. This node's link instead funnels through [resolveConflicts], the
 * same #63-safe [in.kkkev.jjidea.vcs.merge.JujutsuConflictResolver] entry point used by
 * `Jujutsu.ResolveSelectedConflicts` and the log-table "Resolve Conflicts…" action.
 */
class JujutsuConflictsNode(private val project: Project) :
    TagChangesBrowserNode(CONFLICTS_TAG, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, true) {
    override fun render(
        tree: JTree,
        renderer: ChangesBrowserNodeRenderer,
        selected: Boolean,
        expanded: Boolean,
        hasFocus: Boolean
    ) {
        renderer.append(JujutsuBundle.message("changes.node.conflicts"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        appendCount(renderer)
        renderer.append(FontUtil.spaceAndThinSpace(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        renderer.append(
            JujutsuBundle.message("changes.node.conflicts.resolve"),
            SimpleTextAttributes.LINK_BOLD_ATTRIBUTES,
            // Explicit Runnable: Kotlin will not SAM-convert a lambda to append()'s Object tag
            // parameter, and the link silently won't fire if this is written as a lambda.
            Runnable { resolveConflicts(project, conflictedFilesUnder()) }
        )
    }

    /** Scoped to this node's own subtree (O(conflicted files)), not a project-wide lookup. */
    private fun conflictedFilesUnder(): List<VirtualFile> =
        traverseObjectsUnder().filterIsInstance<Change>().mapNotNull { it.virtualFile }.toList()

    // Must stay count-free: UnifiedWorkingCopyPanel.getPathIdentifier derives its persisted
    // collapsed-path key from textPresentation, so embedding the count here would reset the
    // user's collapse state every time a conflict is resolved or a new one appears.
    override fun getTextPresentation(): String = JujutsuBundle.message("changes.node.conflicts")

    override fun getSortWeight(): Int = CONFLICTS_SORT_WEIGHT
}
