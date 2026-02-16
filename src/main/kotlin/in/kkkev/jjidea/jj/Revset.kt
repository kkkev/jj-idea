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

@JvmInline
value class Bookmark(val name: String) : Ref {
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
