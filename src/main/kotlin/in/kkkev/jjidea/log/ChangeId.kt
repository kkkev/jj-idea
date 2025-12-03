package `in`.kkkev.jjidea.log

import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.impl.HashImpl

/*
For now, store as a string in reverse (z-k) hex form.
Eventually, store in 2-long form and allow transforms to reverse and forward hex.
 */
data class ChangeId(val full: String, private val shortLength: Int? = null) : Hash {
    constructor(full: String, short: String) : this(full, calculateShortLength(full, short))

    override fun asString() = full

    override fun toShortString() = shortLength?.let { full.take(it) } ?: full

    override fun toString() = shortLength?.let { "$short:$remainder" } ?: full

    val short get() = toShortString()

    val remainder get() = shortLength?.let { full.drop(it) } ?: ""

    // TODO Track where this is called
    val hashImpl: Hash = HashImpl.build(full.map { HEX[CHARS.indexOf(it)] }.joinToString(""))

    companion object {
        fun calculateShortLength(full: String, short: String): Int {
            require(full.indexOf(short) == 0) { "Invalid short change id $short (not prefix of $full)" }
            return short.length
        }

        val CHARS = "zyxwvutsrqponmlk"
        val HEX = "0123456789abcdef"

        /**
         * Convert a hex string (from HashImpl) back to a JJ change ID
         */
        fun fromHexString(hexString: String): String {
            return hexString.map { hexChar ->
                val hexIndex = HEX.indexOf(hexChar.lowercaseChar())
                if (hexIndex >= 0) CHARS[hexIndex] else hexChar
            }.joinToString("")
        }
    }
}