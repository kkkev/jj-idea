package `in`.kkkev.jjidea.jj

/**
 * Represents a jj version (e.g., 0.37.0).
 *
 * Supports parsing from "jj 0.37.0" or "jj 0.37.0-rc1" format output by `jj --version`.
 */
@JvmInline
value class JjVersion(private val components: Triple<Int, Int, Int>) : Comparable<JjVersion> {
    val major: Int get() = components.first
    val minor: Int get() = components.second
    val patch: Int get() = components.third

    override fun compareTo(other: JjVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        return patch.compareTo(other.patch)
    }

    fun meetsMinimum() = this >= MINIMUM

    override fun toString() = "$major.$minor.$patch"

    companion object {
        /** Minimum required jj version for this plugin. Required for change_offset feature. */
        val MINIMUM = JjVersion(Triple(0, 37, 0))

        private val VERSION_PATTERN = Regex("""jj\s+(?:\w+-)?(\d+)\.(\d+)\.(\d+)""")

        /**
         * Parse a version string from `jj --version` output.
         *
         * Examples:
         * - "jj 0.37.0" -> JjVersion(0, 37, 0)
         * - "jj 0.37.0-rc1" -> JjVersion(0, 37, 0)
         * - "0.37.0" -> null (must start with "jj")
         * - "invalid" -> null
         */
        fun parse(versionString: String): JjVersion? =
            VERSION_PATTERN.find(versionString)?.let { match ->
                val (major, minor, patch) = match.destructured
                JjVersion(Triple(major.toInt(), minor.toInt(), patch.toInt()))
            }
    }
}
