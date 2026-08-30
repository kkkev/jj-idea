package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.ui.JBColor
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.bookmark.bookmarkWidgetText
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RepositoryReferences
import `in`.kkkev.jjidea.jj.TagItem
import `in`.kkkev.jjidea.ui.common.JujutsuColors

/**
 * Nodes of the bookmarks-panel tree (jj-idea-b2ae / GitHub #48), built fresh from state-model
 * snapshots on every rebuild rather than patched incrementally — the source data (a repo's
 * bookmarks + tags) is small and already loaded for other consumers, so a full rebuild is cheap
 * and avoids a whole class of stale-node bugs.
 *
 * Modelled on git4idea's `BranchNodeDescriptor` (`BranchesTreeModel.kt`): a [WorkingCopy] node
 * (jj's analogue of Git's `HEAD`), then one [Category] per kind of reference (Local / one per
 * remote / Tags), each recursively grouped on `/` in the name via [Prefix] nodes
 * (see [buildPrefixTree]).
 */
/**
 * Which reference kind a [BookmarkNode.Category]/[BookmarkNode.Prefix] groups, so the renderer
 * ([in.kkkev.jjidea.ui.log.bookmarks.JujutsuBookmarksPanel]) can colour a folder the same as the
 * leaves inside it (bookmark-brown or tag-green, matching the log's chip colours in
 * [in.kkkev.jjidea.ui.components.LogEntryText]) without walking the tree to find out.
 */
enum class RefKind(val color: JBColor) { BOOKMARK(JujutsuColors.BOOKMARK), TAG(JujutsuColors.TAG) }

sealed interface BookmarkNode {
    val displayName: String

    /** The "@" node: mirrors the main-toolbar widget's [bookmarkWidgetText] label. */
    data class WorkingCopy(val repo: JujutsuRepository, override val displayName: String) : BookmarkNode

    /** One node per repository; only present in multi-repo projects. */
    data class RepoGroup(val repo: JujutsuRepository, val children: List<BookmarkNode>) : BookmarkNode {
        override val displayName get() = repo.displayName
    }

    interface WithRefKind {
        val refKind: RefKind
    }

    /** A top-level kind: "Local", a remote's name, or "Tags". */
    data class Category(
        override val displayName: String,
        override val refKind: RefKind,
        val children: List<BookmarkNode>
    ) :
        BookmarkNode, WithRefKind

    /** One `/`-separated path segment shared by two or more descendants, all of the same [refKind]. */
    data class Prefix(
        override val displayName: String,
        override val refKind: RefKind,
        val children: List<BookmarkNode>
    ) :
        BookmarkNode, WithRefKind

    /** A local bookmark leaf. [onWorkingCopy] is true when it sits exactly on `@`. */
    data class Local(
        val repo: JujutsuRepository,
        val item: BookmarkItem,
        override val displayName: String,
        val onWorkingCopy: Boolean
    ) : BookmarkNode

    /** A remote-tracking bookmark leaf (e.g. `main@origin`), nested under its remote's [Category]. */
    data class Remote(val repo: JujutsuRepository, val item: BookmarkItem, override val displayName: String) :
        BookmarkNode

    /** A tag leaf. */
    data class Tag(val repo: JujutsuRepository, val item: TagItem, override val displayName: String) : BookmarkNode
}

/**
 * Builds the panel's top-level node list from state-model snapshots.
 *
 * O(B·S) where B is the number of bookmarks+tags across all repos and S is the number of `/`
 * segments in a name: one `split("/")` per ref, then a single cursor walk per grouping level, no
 * re-splitting per level (see [buildPrefixTree]). Makes no `jj` invocation of its own — every
 * input ([references], [workingCopies], [closest]) is already loaded by
 * [in.kkkev.jjidea.jj.JujutsuStateModel] for other consumers (the toolbar widget, the reference
 * filter), so this is O(1) in commits, working-tree files, and ignored files. Multi-root
 * multiplies by root count only through refs already fetched for each root.
 */
fun buildBookmarkTree(
    references: Map<JujutsuRepository, RepositoryReferences>,
    workingCopies: Map<JujutsuRepository, LogEntry>,
    closest: Map<JujutsuRepository, ClosestBookmarks?>
): List<BookmarkNode> {
    val repos = references.keys.sortedBy { it.displayName }
    val perRepoNodes = repos.associateWith { repo ->
        buildRepoNodes(repo, references.getValue(repo), workingCopies[repo], closest[repo])
    }

    return if (repos.size > 1) {
        repos.map { repo -> BookmarkNode.RepoGroup(repo, perRepoNodes.getValue(repo)) }
    } else {
        repos.firstOrNull()?.let { perRepoNodes.getValue(it) }.orEmpty()
    }
}

private fun buildRepoNodes(
    repo: JujutsuRepository,
    refs: RepositoryReferences,
    wcEntry: LogEntry?,
    closest: ClosestBookmarks?
): List<BookmarkNode> = buildList {
    val onWcNames = wcEntry?.bookmarks?.filterNot { it.isRemote }?.map { it.name.name }.orEmpty()
    val wcLabel = bookmarkWidgetText(onWcNames, closest)
    if (wcLabel.isNotEmpty()) add(BookmarkNode.WorkingCopy(repo, wcLabel))

    val localBookmarks = refs.bookmarks.filterNot { it.bookmark.isRemote }
    if (localBookmarks.isNotEmpty()) {
        val leaves = localBookmarks.map { item ->
            RefPath(item.bookmark.localName) { name ->
                BookmarkNode.Local(repo, item, name, item.bookmark.name.name in onWcNames)
            }
        }
        add(
            BookmarkNode.Category(
                JujutsuBundle.message("bookmarks.panel.local"),
                RefKind.BOOKMARK,
                buildPrefixTree(leaves, RefKind.BOOKMARK)
            )
        )
    }

    val remoteNames = refs.bookmarks.filter { it.bookmark.isRemote }.map { it.bookmark.remote }.distinct().sorted()
    for (remote in remoteNames) {
        val leaves = refs.bookmarks
            .filter { it.bookmark.isRemote && it.bookmark.remote == remote }
            .map { item -> RefPath(item.bookmark.localName) { name -> BookmarkNode.Remote(repo, item, name) } }
        add(BookmarkNode.Category(remote, RefKind.BOOKMARK, buildPrefixTree(leaves, RefKind.BOOKMARK)))
    }

    if (refs.tags.isNotEmpty()) {
        val leaves = refs.tags.map { item -> RefPath(item.tag.name) { name -> BookmarkNode.Tag(repo, item, name) } }
        add(
            BookmarkNode.Category(
                JujutsuBundle.message("bookmarks.panel.tags"),
                RefKind.TAG,
                buildPrefixTree(leaves, RefKind.TAG)
            )
        )
    }
}

/** A ref pending placement in the `/`-grouped tree: its `/`-split name plus a leaf-node factory. */
private class RefPath(fullName: String, val toLeaf: (displayName: String) -> BookmarkNode) {
    val segments = fullName.split("/")
}

/**
 * Recursively groups [refs] into [BookmarkNode.Prefix] nodes on `/` in their name, mirroring
 * git4idea's `NodeDescriptorsModel.groupByPrefix`: refs sharing a segment at [offset] nest under
 * one [BookmarkNode.Prefix], recursing per remaining segment. A leaf's display name is its last
 * segment only — the full name lives on the wrapped item for actions. Groups sort before leaves;
 * both sort case-insensitively by name within their bucket.
 */
private fun buildPrefixTree(refs: List<RefPath>, refKind: RefKind, offset: Int = 0): List<BookmarkNode> {
    val leaves = mutableListOf<BookmarkNode>()
    val groups = LinkedHashMap<String, MutableList<RefPath>>()

    for (ref in refs) {
        if (offset == ref.segments.lastIndex) {
            leaves += ref.toLeaf(ref.segments[offset])
        } else {
            groups.getOrPut(ref.segments[offset]) { mutableListOf() } += ref
        }
    }

    val prefixNodes = groups.map { (segment, children) ->
        BookmarkNode.Prefix(segment, refKind, buildPrefixTree(children, refKind, offset + 1))
    }

    return (prefixNodes + leaves)
        .sortedWith(compareBy({ it !is BookmarkNode.Prefix }, { it.displayName.lowercase() }))
}
