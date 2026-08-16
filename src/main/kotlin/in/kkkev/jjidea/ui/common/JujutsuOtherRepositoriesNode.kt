package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.TagChangesBrowserNode
import com.intellij.ui.SimpleTextAttributes
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository

/**
 * A single, stable tag so the platform's node bookkeeping treats every
 * [JujutsuOtherRepositoriesNode] as the same logical group — see [JujutsuConflictsNode]'s
 * [CONFLICTS_TAG] for the identical reasoning.
 */
private val OTHER_REPOS_TAG: ChangesBrowserNode.Tag = object : ChangesBrowserNode.Tag {
    override fun toString() = JujutsuBundle.message("changes.node.otherrepos")
}

/**
 * Demotes every repository other than [JujutsuChangesTree.currentRepo] into one collapsed node
 * (jj-idea-xsa8), so the changes tree — which spans every repo — doesn't read as if it applies
 * equally to whichever one is bound. Mirror image of [JujutsuConflictsNode], which promotes
 * conflicts to the top instead; same [TagChangesBrowserNode] tool the platform itself uses to
 * demote its own `UNVERSIONED_FILES_TAG`/`IGNORED_FILES_TAG`. `expandByDefault = false`: collapsed
 * is the point of demoting.
 *
 * Children are grouped by repo under [JujutsuOtherRepositoryNode] via raw model insertion, not
 * `insertChanges` — see [JujutsuChangesTree.buildTreeModel]'s comment at the call site for why.
 */
class JujutsuOtherRepositoriesNode :
    TagChangesBrowserNode(OTHER_REPOS_TAG, SimpleTextAttributes.GRAYED_ATTRIBUTES, false) {
    // Between Conflicts (0) and ordinary top-level content (5-7); matches UNVERSIONED_SORT_WEIGHT.
    override fun getSortWeight(): Int = UNVERSIONED_SORT_WEIGHT
}

/**
 * One repo's own sub-group *within* [JujutsuOtherRepositoriesNode] (jj-idea-xsa8). Children are
 * plain [com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode] leaves inserted via raw
 * model insertion, bypassing grouping entirely — files show flat, without their own directory
 * sub-structure, a fine trade-off for this demoted area. Renders with [RepositoryIcons]' per-repo
 * icon, same as [JujutsuNoChangesNode] and the bookmark widget's multi-repo dropdown.
 */
class JujutsuOtherRepositoryNode(private val repo: JujutsuRepository) :
    TagChangesBrowserNode(
        object : Tag {
            override fun toString() = repo.displayName
        },
        SimpleTextAttributes.REGULAR_ATTRIBUTES,
        true
    ) {
    override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) {
        renderer.icon = RepositoryIcons[repo]
        super.render(renderer, selected, expanded, hasFocus)
    }
}
