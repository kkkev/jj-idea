package `in`.kkkev.jjidea.jj

open class Shortenable(open val full: String, private val shortLength: Int? = null) {
    constructor(full: String, short: String? = null) : this(full, short?.let { calculateShortLength(full, it) })

    /**
     * The string form can always safely be the short form - this can be used in commands.
     */
    override fun toString() = short

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is Shortenable -> false
        this::class != other::class -> false
        // Ignore the short length; two ids are equal if their full representations are the same
        else -> full == other.full
    }

    override fun hashCode() = full.hashCode()

    open val short get() = shortLength?.let { full.take(it) } ?: full

    val remainder get() = shortLength?.let { full.drop(it) } ?: ""

    /**
     * Remainder for display version (after the display limit)
     */
    val displayRemainder: String
        get() {
            val maxLength = maxOf(8, shortLength ?: 0)
            return if (full.length > maxLength) {
                full.drop(shortLength ?: 0).take(maxLength - (shortLength ?: 0))
            } else {
                remainder
            }
        }
    companion object {
        fun calculateShortLength(full: String, short: String): Int {
            require(full.indexOf(short) == 0) { "Invalid short change id $short (not prefix of $full)" }
            return short.length
        }
    }
}
