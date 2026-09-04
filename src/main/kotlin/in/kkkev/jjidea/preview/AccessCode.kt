package `in`.kkkev.jjidea.preview

import java.security.MessageDigest

/**
 * Offline validation for the preview-feature access code entered in Settings.
 *
 * A candidate code is normalised, salted, and hashed with SHA-256, then compared against the
 * hashes in the plugin resource `preview/access-codes.txt` - one hash per line, so rotating or
 * adding a code is a one-line resource edit, not a code change.
 *
 * Be clear-eyed about the strength: a determined user can decompile the jar or share a valid
 * code with a friend. That's fine - this is noise reduction to keep casual Marketplace users
 * from stumbling into an unfinished gesture and filing issues, not DRM. A future Marketplace
 * freemium provider would do real signature verification via `LicensingFacade`; this one isn't
 * the thing that would guard a paid feature.
 */
object AccessCode {
    private const val SALT = "in.kkkev.jjidea.preview.v1"
    private const val RESOURCE_PATH = "/preview/access-codes.txt"

    private val validHashes: Set<String> by lazy { loadHashes() }

    /** Whether [code] matches one of the hashes shipped in [RESOURCE_PATH]. */
    fun isValid(code: String): Boolean {
        val normalised = normalise(code)
        if (normalised.isEmpty()) return false
        return hash(normalised) in validHashes
    }

    private fun normalise(code: String): String = code.trim().lowercase().replace(Regex("\\s+"), "")

    private fun hash(normalisedCode: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((normalisedCode + SALT).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadHashes(): Set<String> {
        val text = javaClass.getResourceAsStream(RESOURCE_PATH)?.readBytes()?.decodeToString() ?: return emptySet()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }
}
