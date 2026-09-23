package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangeNodeDecorator
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.FontUtil
import `in`.kkkev.jjidea.jj.conflict.conflictRegistry
import `in`.kkkev.jjidea.vcs.filePath

/**
 * Appends jj's own conflict-shape text (e.g. "2-sided conflict including 1 deletion", verbatim
 * from `jj resolve --list`) after each row under [JujutsuConflictsNode], so a user picking which
 * file to resolve next (GitHub #66) can see modify/delete conflicts at a glance instead of
 * discovering them only after opening the merge tool.
 *
 * Looks up [in.kkkev.jjidea.jj.conflict.JujutsuConflictRegistry], which is already populated for
 * the working copy on every `jj status` pass - this decorator adds no new jj calls, just an O(1)
 * map lookup per rendered row.
 */
class ConflictShapeDecorator(private val project: Project) : ChangeNodeDecorator {
    override fun decorate(change: Change, component: SimpleColoredComponent, isShowFlatten: Boolean) {
        val description = change.filePath.virtualFile
            ?.let { project.conflictRegistry.get(it) }
            ?.description
            ?.takeIf { it.isNotBlank() }
            ?: return
        component.append(FontUtil.spaceAndThinSpace(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        component.append(description, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    override fun preDecorate(
        change: Change,
        renderer: ChangesBrowserNodeRenderer,
        isShowFlatten: Boolean
    ) = Unit
}
