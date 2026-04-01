package `in`.kkkev.jjidea.jj

sealed interface Revset

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

// TODO Find other references to @
object WorkingCopy : Ref {
    override fun toString() = "@"
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
