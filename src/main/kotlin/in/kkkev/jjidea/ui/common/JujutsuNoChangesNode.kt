package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.ui.SimpleTextAttributes
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import javax.swing.JTree

/**
 * Shown instead of [JujutsuChangesTree.currentRepo]'s (normally unwrapped) content when that repo
 * has no changes, so the tree still names the bound repo rather than looking empty (jj-idea-xsa8).
 */
class JujutsuNoChangesNode(private val repo: JujutsuRepository) : ChangesBrowserNode<JujutsuRepository>(repo) {
    init {
        markAsHelperNode()
    }

    override fun render(
        tree: JTree,
        renderer: ChangesBrowserNodeRenderer,
        selected: Boolean,
        expanded: Boolean,
        hasFocus: Boolean
    ) {
        renderer.icon = RepositoryIcons[repo]
        renderer.append(repo.displayName)
        renderer.append(" ")
        renderer.append(JujutsuBundle.message("changes.node.nochanges"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    override fun getTextPresentation(): String = repo.displayName

    // Between Conflicts (0) and JujutsuOtherRepositoriesNode (UNVERSIONED_SORT_WEIGHT=9).
    override fun getSortWeight(): Int = NO_CHANGES_SORT_WEIGHT

    private companion object {
        const val NO_CHANGES_SORT_WEIGHT = 4
    }
}
