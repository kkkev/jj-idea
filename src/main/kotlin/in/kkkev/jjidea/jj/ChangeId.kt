package `in`.kkkev.jjidea.jj

import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.impl.HashImpl

/*
For now, store as a string in reverse (z-k) hex form.
Eventually, store in 2-long form and allow transforms to reverse and forward hex.
 */
data class ChangeId(override val full: String, private val shortLength: Int? = null) : Revision {
    constructor(full: String, short: String) : this(full, calculateShortLength(full, short))

    /**
     * The string form can always safely be the short form - this can be used in commands.
     */
    override fun toString() = short

    override val short get() = shortLength?.let { full.take(it) } ?: full

    val remainder get() = shortLength?.let { full.drop(it) } ?: ""

    /**
     * Display version limited to 8 characters or short prefix length (whichever is greater)
     */
    val display: String
        get() {
            val maxLength = maxOf(8, shortLength ?: 0)
            return if (full.length > maxLength) full.take(maxLength) else full
        }

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

    /**
     * Convert to hex format (0-9a-f) for Hash compatibility
     */
    val hexString: String
        get() {
            val hex =
                full
                    .map { char ->
                        val index = CHARS.indexOf(char)
                        require(index >= 0) { "Invalid character '$char' in change ID '$full'" }
                        HEX[index]
                    }.joinToString("")
            return hex
        }

    /**
     * Lazy initialization of Hash to allow tests without IntelliJ Platform.
     * Only accessed when integrating with IntelliJ VCS framework.
     */
    val hash: Hash by lazy {
        HashImpl.build(hexString)
    }

    companion object {
        fun calculateShortLength(full: String, short: String): Int {
            require(full.indexOf(short) == 0) { "Invalid short change id $short (not prefix of $full)" }
            return short.length
        }

        const val CHARS = "zyxwvutsrqponmlk"
        const val HEX = "0123456789abcdef"

        /**
         * Convert a hex string (from HashImpl) back to a JJ change ID
         */
        fun fromHexString(hexString: String) = hexString
            .map { hexChar ->
                val hexIndex = HEX.indexOf(hexChar.lowercaseChar())
                if (hexIndex >= 0) CHARS[hexIndex] else hexChar
            }.joinToString("")
            .let(::ChangeId)
    }
}
