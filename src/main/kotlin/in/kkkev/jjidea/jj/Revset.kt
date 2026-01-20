package `in`.kkkev.jjidea.jj

sealed interface Revset

/**
 * Revset pointing to a single revision, which could be a [Ref] or any expression that resolves to a single revision.
 */
sealed interface Revision : Revset {
    val parent get() = RevisionExpression("$this-")
    val full: String get() = toString()
    val short: String get() = toString()
}

/**
 * Reference to a single revision, e.g. a bookmark or tag.
 */
sealed interface Ref : Revision

@JvmInline
value class Bookmark(
    val name: String
) : Ref {
    override fun toString() = name
}

@JvmInline
value class Tag(
    val name: String
) : Ref {
    override fun toString() = name
}

@JvmInline
value class Expression(
    val value: String
) : Revset {
    override fun toString() = value

    companion object {
        val ALL = Expression("all()")
    }
}

/**
 * An expression that points to a single revision
 */
@JvmInline
value class RevisionExpression(
    val value: String
) : Revision {
    override fun toString() = value
}

// TODO Find other references to @
object WorkingCopy : Ref {
    override fun toString() = "@"
}

data class RefAtRevision(
    val changeId: ChangeId,
    val ref: Ref
)
