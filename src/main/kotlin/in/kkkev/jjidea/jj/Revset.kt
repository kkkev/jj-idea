package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.JujutsuBundle

sealed interface Revset {
    /** Omit `-r` flag entirely, letting jj use its `revsets.log` config. */
    data object Default : Revset
}

/**
 * Revset pointing to a single revision, which could be a [Ref] or any expression that resolves to a single revision.
 */
sealed interface Revision : Revset {
    val parent get() = RevisionExpression("$this-")
    val short: String get() = toString()
}

/**
 * Reference to a single revision, e.g. a bookmark or tag.
 */
sealed interface Ref : Revision

data class Bookmark(val name: String, val tracked: Boolean = true) : Ref {
    override fun toString() = name

    val isRemote get() = '@' in name
    val localName get() = name.substringBefore('@')
    val remote get() = name.substringAfter('@', "")
}

/**
 * A group of bookmarks sharing the same [localName], e.g. `master` (local) + `master@origin` + `master@github`.
 * Used for collapsed display: `master (@origin, @github)`.
 */
data class BookmarkGroup(val localName: String, val local: Bookmark?, val remotes: List<Bookmark>) {
    val tracked get() = local != null || remotes.any { it.tracked }
}

fun List<Bookmark>.grouped(): List<BookmarkGroup> =
    groupBy { it.localName }.map { (localName, bookmarks) ->
        BookmarkGroup(
            localName,
            local = bookmarks.find { !it.isRemote },
            remotes = bookmarks.filter { it.isRemote }
        )
    }

@JvmInline
value class Remote(val name: String) {
    override fun toString() = name
}

@JvmInline
value class Tag(val name: String) : Ref {
    override fun toString() = name
}

@JvmInline
value class Expression(val value: String) : Revset {
    override fun toString() = value

    companion object {
        val ALL = Expression("all()")
    }
}

/**
 * An expression that points to a single revision
 */
@JvmInline
value class RevisionExpression(val value: String) : Revision {
    override fun toString() = value
}

object WorkingCopy : Ref, ContentLocator {
    override fun toString() = REF
    override val title = JujutsuBundle.message("diff.label.current")
    override val full = REF
    override val short = REF

    const val REF = "@"
}

/**
 * Identifier that can be used to identify content within the repository. Includes individual revisions and locators
 * from which content can be constructed.
 */
sealed interface ContentLocator : Shortenable {
    /**
     * Title to give to editors etc. that display content behind this locator.
     */
    val title: String

    object Empty : ContentLocator {
        override val title = JujutsuBundle.message("diff.label.empty")
        override val full = ""
        override val short = ""
    }
}

/**
 * Signals that content should be obtained by reverse-applying `jj diff --git -r [childRevision]`
 * to the file content at [childRevision], reconstructing the auto-merged parent tree content.
 *
 * Used when [childRevision] is a merge commit (working copy or historical), where
 * `jj file show -r <firstParent>` would give only the first parent's content rather than
 * the auto-merged tree that jj actually diffs against.
 */
data class MergeParentOf(val childRevision: Revision) : ContentLocator {
    override fun toString() = "MergeParent($childRevision)"
    override val title get() = JujutsuBundle.message("diff.label.merged.parents")
    override val full get() = title
    override val short = title
}

data class RefAtCommit(val commitId: CommitId, val ref: Ref)

/** How to select source revisions for `jj rebase`. */
enum class RebaseSourceMode(val flag: String) {
    /** Rebase only the specified revisions; descendants stay in place. */
    REVISION("-r"),

    /** Rebase the revision and all its descendants. */
    SOURCE("-s"),

    /** Rebase the entire branch containing the revision. */
    BRANCH("-b")
}

/** Where to place rebased revisions. */
enum class RebaseDestinationMode(val flag: String) {
    /** Standard rebase: become children of the destination. */
    ONTO("-d"),

    /** Insert after the destination, before its current children. */
    INSERT_AFTER("-A"),

    /** Insert before the destination, after its parents. */
    INSERT_BEFORE("-B")
}
