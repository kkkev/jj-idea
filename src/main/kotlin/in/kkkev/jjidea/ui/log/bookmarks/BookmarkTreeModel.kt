package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.ui.JBColor
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.bookmark.bookmarkWidgetText
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.DanglingHead
import `in`.kkkev.jjidea.jj.GIT_PSEUDO_REMOTE
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

/**
 * Aggregates [Bookmark.aheadCount]/[Bookmark.behindCount]/untracked-or-conflicted status over a
 * [BookmarkNode.Category] or [BookmarkNode.Prefix]'s descendant leaves, so a collapsed category
 * can still surface "something in here needs attention" (jj-idea-a7a7, GitHub #48 Finding 4) —
 * collapsing a remote category would otherwise hide the per-bookmark `↑n↓m` indicators entirely.
 *
 * [unsyncedCount] counts descendant leaves that are untracked or conflicted (jj-idea-uyu9's
 * tooltip redesign needs an actual number, not just "something's off" - see
 * [in.kkkev.jjidea.ui.log.bookmarks.bookmarkNodeTooltip]); the in-row collapsed badge
 * ([in.kkkev.jjidea.ui.log.bookmarks.JujutsuBookmarksPanel]) only ever needed [isNotable], so it
 * is unaffected by this being a count rather than a boolean.
 *
 * [leafCount] is the total number of bookmark/tag leaves reachable under the node (jj-idea-uyu9
 * follow-up) - shown in a folder's tooltip so hovering a collapsed/nested group answers "how many
 * are in here" without expanding it.
 */
data class BookmarkRollup(
    val aheadCount: Int = 0,
    val behindCount: Int = 0,
    val unsyncedCount: Int = 0,
    val leafCount: Int = 0
) {
    val isNotable get() = aheadCount > 0 || behindCount > 0 || unsyncedCount > 0

    operator fun plus(other: BookmarkRollup) = BookmarkRollup(
        aheadCount + other.aheadCount,
        behindCount + other.behindCount,
        unsyncedCount + other.unsyncedCount,
        leafCount + other.leafCount
    )

    /** The `↑n↓m` arrow text for this rollup - shared by the in-row collapsed badge and
     * [in.kkkev.jjidea.ui.log.bookmarks.bookmarkNodeTooltip]'s group bracket. See [divergenceText]
     * for the underlying (ahead, behind) -> text logic, also reused for a single [Bookmark]'s own
     * counts (jj-idea-uyu9 follow-up: the per-remote breakdown on a local bookmark's tooltip). */
    fun divergenceText(): String = divergenceText(aheadCount, behindCount)

    companion object {
        val EMPTY = BookmarkRollup()
    }
}

/** The `↑n↓m` arrow text for [aheadCount]/[behindCount] (empty if neither is positive). */
fun divergenceText(aheadCount: Int, behindCount: Int): String = buildString {
    if (aheadCount > 0) append("↑$aheadCount")
    if (behindCount > 0) append("↓$behindCount")
}

sealed interface BookmarkNode {
    val displayName: String

    /**
     * The "@" node: [displayName] mirrors the main-toolbar widget's [bookmarkWidgetText] label.
     * [id] is `@`'s own change (`null` only if the working-copy entry itself failed to load -
     * this node is otherwise only ever added once a real [in.kkkev.jjidea.jj.LogEntry] exists).
     * [onWorkingCopyNames]/[closest] are the same two raw inputs [bookmarkWidgetText] derives
     * [displayName] from, kept alongside it (rather than re-derived by re-parsing [displayName])
     * so [in.kkkev.jjidea.ui.log.bookmarks.bookmarkNodeTooltip] can render its own compact
     * bracket form (jj-idea-uyu9) - a bookmark sitting directly on `@` needs different tooltip
     * wording than "N commits past the nearest ancestor bookmark", and only the raw data
     * distinguishes those two cases (both collapse to the same kind of text in [displayName]).
     */
    data class WorkingCopy(
        val repo: JujutsuRepository,
        override val displayName: String,
        val id: ChangeId?,
        val onWorkingCopyNames: List<String>,
        val closest: ClosestBookmarks?
    ) : BookmarkNode

    /** One node per repository; only present in multi-repo projects. */
    data class RepoGroup(val repo: JujutsuRepository, val children: List<BookmarkNode>) : BookmarkNode {
        override val displayName get() = repo.displayName
    }

    interface WithRefKind {
        val refKind: RefKind

        /** Rolled-up status of every bookmark leaf under this node — see [BookmarkRollup]. */
        val rollup: BookmarkRollup
    }

    /**
     * A top-level kind: "Local", a remote's name, or "Tags" - or, when [isDanglingHeadsGroup] is
     * `true`, the one-of-a-kind "Unbookmarked heads" group (jj-idea-lig7), which unlike every
     * other [Category] doesn't group refs by [RefKind] at all and never carries a [rollup] (its
     * children are [DanglingHead]s, not bookmark/tag leaves) - flagged explicitly rather than
     * inferred by comparing [displayName] against the bundle string, so
     * [in.kkkev.jjidea.ui.log.bookmarks.bookmarkNodeTooltip] can give it its own compact-count
     * tooltip instead of the normal group-bracket one (jj-idea-uyu9).
     *
     * [defaultExpanded] is `false` for a remote category (collapsed by default, jj-idea-a7a7) and
     * `true` otherwise — the actual per-node state a rebuild applies also considers any explicit
     * user toggle recorded in [in.kkkev.jjidea.settings.LogWindowConfig.bookmarkNodeExpanded].
     */
    data class Category(
        val repo: JujutsuRepository,
        override val displayName: String,
        override val refKind: RefKind,
        val children: List<BookmarkNode>,
        val defaultExpanded: Boolean = true,
        override val rollup: BookmarkRollup = BookmarkRollup.EMPTY,
        val isDanglingHeadsGroup: Boolean = false
    ) :
        BookmarkNode, WithRefKind

    /**
     * One `/`-separated path segment shared by two or more descendants, all of the same
     * [refKind]. [groupLabel] is the *owning* top-level [Category]'s own [Category.displayName]
     * ("Local", a remote's name, or "Tags") - constant across an entire [buildPrefixTree] call,
     * not this [Prefix]'s own [displayName] - and [fullPath] is the accumulated `/`-path from
     * that [Category] down to and including this segment (e.g. `"branches/foo"` for the `foo`
     * node under `Local ▸ branches ▸ foo`). Both exist solely for
     * [in.kkkev.jjidea.ui.log.bookmarks.bookmarkNodeTooltip] (jj-idea-uyu9): a bare leaf's
     * qualified path is already available via its own wrapped item and needs no such field (see
     * [BookmarkItem]/[TagItem]), but a [Prefix] carries no ref of its own to read that from.
     */
    data class Prefix(
        val repo: JujutsuRepository,
        override val displayName: String,
        override val refKind: RefKind,
        val children: List<BookmarkNode>,
        override val rollup: BookmarkRollup = BookmarkRollup.EMPTY,
        val groupLabel: String,
        val fullPath: String
    ) :
        BookmarkNode, WithRefKind

    /**
     * A local bookmark leaf. [onWorkingCopy] is true when it sits exactly on `@`. [remotes] is
     * every tracked, non-[GIT_PSEUDO_REMOTE] remote-tracking [Bookmark] row sharing this
     * bookmark's local name (jj-idea-uyu9 follow-up) - display-only, purely for
     * [in.kkkev.jjidea.ui.log.bookmarks.bookmarkNodeTooltip]'s per-remote ahead/behind breakdown.
     * [item.bookmark]'s own `aheadCount`/`behindCount` remain the single collapsed max across
     * these (see [in.kkkev.jjidea.jj.withDivergenceFrom]), unaffected by this - this field
     * doesn't feed derivation, only display.
     */
    data class Local(
        val repo: JujutsuRepository,
        val item: BookmarkItem,
        override val displayName: String,
        val onWorkingCopy: Boolean,
        val remotes: List<Bookmark> = emptyList()
    ) : BookmarkNode

    /** A remote-tracking bookmark leaf (e.g. `main@origin`), nested under its remote's [Category]. */
    data class Remote(val repo: JujutsuRepository, val item: BookmarkItem, override val displayName: String) :
        BookmarkNode

    /** A tag leaf. */
    data class Tag(val repo: JujutsuRepository, val item: TagItem, override val displayName: String) : BookmarkNode

    /**
     * A visible head with no bookmark on it (jj-idea-lig7, GitHub #107) — nests under the
     * "Unbookmarked heads" [Category]. [closest] is `null` when [id] has no ancestor bookmark at
     * all.
     */
    data class DanglingHead(
        val repo: JujutsuRepository,
        val id: ChangeId,
        val closest: ClosestBookmarks?,
        override val displayName: String
    ) : BookmarkNode
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
 *
 * [BookmarkRollup] aggregation (jj-idea-a7a7) adds no separate pass: each [BookmarkNode.Category]/
 * [BookmarkNode.Prefix] folds only its own direct children's already-computed rollups
 * ([rollupOf]), so the total rollup work across the whole tree is still O(B), not O(B·depth).
 */
fun buildBookmarkTree(
    references: Map<JujutsuRepository, RepositoryReferences>,
    workingCopies: Map<JujutsuRepository, LogEntry>,
    closest: Map<JujutsuRepository, ClosestBookmarks?>,
    danglingHeads: Map<JujutsuRepository, List<DanglingHead>> = emptyMap()
): List<BookmarkNode> {
    val repos = references.keys.sortedBy { it.displayName }
    val perRepoNodes = repos.associateWith { repo ->
        buildRepoNodes(
            repo,
            references.getValue(repo),
            workingCopies[repo],
            closest[repo],
            danglingHeads[repo].orEmpty()
        )
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
    closest: ClosestBookmarks?,
    danglingHeads: List<DanglingHead>
): List<BookmarkNode> = buildList {
    val onWcNames = wcEntry?.bookmarks?.filterNot { it.isRemote }?.map { it.name.name }.orEmpty()
    val wcLabel = bookmarkWidgetText(onWcNames, closest)
    if (wcLabel.isNotEmpty()) {
        add(BookmarkNode.WorkingCopy(repo, wcLabel, wcEntry?.id, onWcNames, closest))
    }

    if (danglingHeads.isNotEmpty()) {
        val leaves = danglingHeads
            .sortedWith(compareBy({ it.closest?.distance ?: Int.MAX_VALUE }, { it.id.full }))
            .map { head ->
                val label = "${danglingHeadLabel(head.closest)} ${head.id.short}"
                BookmarkNode.DanglingHead(repo, head.id, head.closest, label)
            }
        add(
            BookmarkNode.Category(
                repo,
                JujutsuBundle.message("bookmarks.panel.unbookmarked"),
                RefKind.BOOKMARK,
                leaves,
                isDanglingHeadsGroup = true
            )
        )
    }

    // Divergence (ahead/behind) and the deleted-local/absent-remote corrections are all already
    // applied once per repo, upstream, by LogService.withDerivedDivergence (jj-idea-ks5k,
    // GitHub #110) — see in.kkkev.jjidea.jj.JujutsuStateModel.references. That keeps this panel
    // and the log table's bookmark chips reading the same numbers instead of each deriving them
    // independently.
    val localBookmarks = refs.bookmarks.filterNot { it.bookmark.isRemote }
    if (localBookmarks.isNotEmpty()) {
        // Display-only re-grouping for BookmarkNode.Local.remotes (jj-idea-uyu9 follow-up) - not
        // used for derivation, which already happened upstream (see the comment above).
        val remotesByLocalName = refs.bookmarks.asSequence()
            .map { it.bookmark }
            .filter { it.isRemote && it.remote != GIT_PSEUDO_REMOTE && it.tracked }
            .groupBy { it.localName }
        val localLabel = JujutsuBundle.message("bookmarks.panel.local")
        val leaves = localBookmarks.map { item ->
            RefPath(item.bookmark.localName) { name ->
                BookmarkNode.Local(
                    repo,
                    item,
                    name,
                    item.bookmark.name.name in onWcNames,
                    remotesByLocalName[item.bookmark.localName].orEmpty()
                )
            }
        }
        val children = buildPrefixTree(repo, leaves, RefKind.BOOKMARK, localLabel)
        add(
            BookmarkNode.Category(
                repo,
                localLabel,
                RefKind.BOOKMARK,
                children,
                rollup = rollupOf(children)
            )
        )
    }

    // "git" is not a peer remote: it's jj's own view of a colocated repo's local Git refs
    // (`jj git remote add git ...` fails with "reserved for local Git repository", and
    // `jj git remote list` never lists it), so every bookmark in a colocated repo would
    // otherwise appear here a third time alongside Local and its real remote(s)
    // (jj-idea-j0zv, GitHub #48). Keyed on the literal name, not on absence from
    // repo.cachedGitRemotes, which is deliberately non-blocking and empty when cold - that
    // would transiently hide every remote category instead of just this one. An escape
    // hatch to still show it lives in jj-idea-k5d7's settings gear.
    val remoteNames = refs.bookmarks.filter { it.bookmark.isRemote }
        .map { it.bookmark.remote }
        .filter { it != GIT_PSEUDO_REMOTE }
        .distinct()
        .sorted()
    for (remote in remoteNames) {
        val leaves = refs.bookmarks
            .filter { it.bookmark.isRemote && it.bookmark.remote == remote }
            .map { item -> RefPath(item.bookmark.localName) { name -> BookmarkNode.Remote(repo, item, name) } }
        val children = buildPrefixTree(repo, leaves, RefKind.BOOKMARK, remote)
        add(
            BookmarkNode.Category(
                repo,
                remote,
                RefKind.BOOKMARK,
                children,
                defaultExpanded = false,
                rollup = rollupOf(children)
            )
        )
    }

    if (refs.tags.isNotEmpty()) {
        val tagsLabel = JujutsuBundle.message("bookmarks.panel.tags")
        val leaves = refs.tags.map { item -> RefPath(item.tag.name) { name -> BookmarkNode.Tag(repo, item, name) } }
        val children = buildPrefixTree(repo, leaves, RefKind.TAG, tagsLabel)
        add(
            BookmarkNode.Category(
                repo,
                tagsLabel,
                RefKind.TAG,
                children,
                rollup = rollupOf(children)
            )
        )
    }
}

/**
 * The bookmark-coloured half of a [BookmarkNode.DanglingHead]'s label (jj-idea-lig7): reuses
 * [bookmarkWidgetText] to render "[closest] +n" exactly as the working-copy row does, or a
 * localized fallback when the head has no ancestor bookmark at all. The change id itself is
 * appended separately by the caller (both the plain-text `displayName` here and the styled
 * renderer in [in.kkkev.jjidea.ui.log.bookmarks.JujutsuBookmarksPanel]) via
 * [in.kkkev.jjidea.ui.components.append].
 */
fun danglingHeadLabel(closest: ClosestBookmarks?): String =
    if (closest != null) {
        bookmarkWidgetText(emptyList(), closest)
    } else {
        JujutsuBundle.message("bookmarks.panel.unbookmarked.nobookmark")
    }

/** Sums [BookmarkRollup] contributions of every bookmark leaf reachable under [nodes]. */
private fun rollupOf(nodes: List<BookmarkNode>): BookmarkRollup =
    nodes.fold(BookmarkRollup.EMPTY) { acc, n ->
        acc + n.leafRollup()
    }

private fun BookmarkNode.leafRollup(): BookmarkRollup = when (this) {
    is BookmarkNode.Local -> item.bookmark.toRollup()
    is BookmarkNode.Remote -> item.bookmark.toRollup()
    is BookmarkNode.Tag -> BookmarkRollup(leafCount = 1)
    is BookmarkNode.WithRefKind -> rollup
    is BookmarkNode.WorkingCopy, is BookmarkNode.RepoGroup, is BookmarkNode.DanglingHead -> BookmarkRollup.EMPTY
}

private fun Bookmark.toRollup() = BookmarkRollup(
    aheadCount,
    behindCount,
    unsyncedCount = if (!tracked || conflict) 1 else 0,
    leafCount = 1
)

/**
 * Whether [in.kkkev.jjidea.ui.log.bookmarks.JujutsuBookmarksPanel] should expand this node absent
 * an explicit user override recorded in
 * [in.kkkev.jjidea.settings.LogWindowConfig.bookmarkNodeExpanded] — `false` only for a remote
 * [BookmarkNode.Category] (jj-idea-a7a7, GitHub #48); every other node kind defaults to expanded,
 * matching the pre-existing unconditional-expand behavior.
 */
fun BookmarkNode.defaultExpanded(): Boolean = (this as? BookmarkNode.Category)?.defaultExpanded ?: true

/**
 * The key [in.kkkev.jjidea.ui.log.bookmarks.JujutsuBookmarksPanel] persists this node's expansion
 * state under: the `/`-joined [BookmarkNode.displayName] path from the tree root, e.g. `"origin"`
 * for a single-repo project or `"myrepo/origin/feature"` under a [BookmarkNode.RepoGroup] and a
 * `/`-grouped [BookmarkNode.Prefix].
 */
fun BookmarkNode.expansionPathKey(parentPath: String): String =
    if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"

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
 *
 * [groupLabel] is constant across the whole call (the owning top-level [BookmarkNode.Category]'s
 * own name) and [pathPrefix] accumulates the `/`-joined segments seen so far, so every
 * [BookmarkNode.Prefix] built here can carry its own [BookmarkNode.Prefix.groupLabel]/
 * [BookmarkNode.Prefix.fullPath] with no second pass (jj-idea-uyu9) - every [RefPath] reaching a
 * given recursion shares segments `0 until offset` by construction, so `pathPrefix` never needs
 * to be recomputed from the group's members.
 */
private fun buildPrefixTree(
    repo: JujutsuRepository,
    refs: List<RefPath>,
    refKind: RefKind,
    groupLabel: String,
    offset: Int = 0,
    pathPrefix: String = ""
): List<BookmarkNode> {
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
        val fullPath = if (pathPrefix.isEmpty()) segment else "$pathPrefix/$segment"
        val childNodes = buildPrefixTree(repo, children, refKind, groupLabel, offset + 1, fullPath)
        BookmarkNode.Prefix(repo, segment, refKind, childNodes, rollupOf(childNodes), groupLabel, fullPath)
    }

    return (prefixNodes + leaves)
        .sortedWith(compareBy({ it !is BookmarkNode.Prefix }, { it.displayName.lowercase() }))
}
