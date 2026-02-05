package `in`.kkkev.jjidea.jj

/*
For now, store as a string in reverse (z-k) hex form.
Eventually, store in 2-long form and allow transforms to reverse and forward hex.
 */
class ChangeId(full: String, shortLength: Int? = null) : ShortenableId(full, shortLength) {
    constructor(full: String, short: String) : this(full, calculateShortLength(full, short))

    /**
     * Convert to hex format (0-9a-f) for Hash compatibility
     */
    val hexString: String
        get() = full.map { char ->
            val index = CHARS.indexOf(char)
            require(index >= 0) { "Invalid character '$char' in change ID '$full'" }
            HEX[index]
        }.joinToString("")

    companion object {
        const val CHARS = "zyxwvutsrqponmlk"
        const val HEX = "0123456789abcdef"

    }
}
