package `in`.kkkev.jjidea.jj

/**
 * A change id qualified with an optional offset. This uniquely identifies a commit, even if the change has become
 * conflicted.
 */
class ChangeId(full: String, short: String? = null, val offset: Int? = null) : Revision {
    constructor(full: String, short: String, offset: String) : this(
        full,
        short,
        offset.takeIf { offset.isNotEmpty() }?.toInt()
    )

    override fun toString() = full

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is ChangeId -> false
        else -> this.full == other.full
    }

    override fun hashCode() = full.hashCode()

    val shortenable = Shortenable(full, short)
    val full get() = "${shortenable.full}$optionalOffset"
    override val short get() = "${shortenable.short}$optionalOffset"
    val remainder get() = shortenable.remainder
    val divergent get() = offset != null
    val optionalOffset get() = offset?.let { "/$it" } ?: ""

    companion object {
        val EMPTY = ChangeId("", "")
    }
}
