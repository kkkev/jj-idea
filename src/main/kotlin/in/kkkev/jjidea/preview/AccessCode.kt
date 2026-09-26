package `in`.kkkev.jjidea.preview

import java.security.MessageDigest
import java.time.LocalDate

/**
 * Resolves a preview-feature access code as entered in Settings to what it grants.
 *
 * Two code families, tried in order:
 *
 * 1. **`JJP1-...`** ([PreviewCode]): short, feature-scoped, HMAC-signed codes mintable offline at
 *    any time with no plugin release (`PreviewCodeMinter`). The signing key is never committed -
 *    it's injected into the shipped jar at release-build time from a CI secret (see
 *    `preview/code-key.bin` and `docs/design/preview-gating-and-dnd-sequencing.md`). A dev build
 *    or a fork without the secret simply can't validate `JJP1` codes.
 * 2. **Anything else** - the original flat hash list in `access-codes.txt`, kept for existing
 *    testers' already-issued codes. A hash always grants every [PreviewFeature] (this family
 *    predates per-feature scoping).
 *
 * Be clear-eyed about the strength of either family: a determined user can decompile the jar or
 * share a valid code with a friend. That's fine - this is noise reduction to keep casual
 * Marketplace users from stumbling into an unfinished gesture and filing issues, not DRM. A
 * future Marketplace freemium provider would do real signature verification via
 * `LicensingFacade`; neither code family here is the thing that would guard a paid feature.
 */
object AccessCode {
    private const val SALT = "in.kkkev.jjidea.preview.v1"
    private const val LEGACY_HASHES_RESOURCE = "/preview/access-codes.txt"
    private const val SIGNING_KEY_RESOURCE = "/preview/code-key.bin"
    private const val REVOKED_SERIALS_RESOURCE = "/preview/revoked-serials.txt"

    private val legacyHashes: Set<String> by lazy { loadLegacyHashes() }
    private val signingKey: ByteArray? by lazy { loadSigningKey() }
    private val revokedSerials: Set<Int> by lazy { loadRevokedSerials() }

    // Memoises the last (code, grant) pair: PreviewEntitlement.isEnabled calls this once per
    // PreviewFeature per resolution, so a settings panel with several features would otherwise
    // repeat the same HMAC/hash work for an unchanged code.
    @Volatile
    private var lastCode: String? = null

    @Volatile
    private var lastGrant: PreviewCode.Grant = PreviewCode.Grant.Invalid

    /** What [code] currently grants. See [PreviewCode.Grant]. */
    fun grant(code: String, today: LocalDate = LocalDate.now()): PreviewCode.Grant {
        val normalised = code.trim()
        if (normalised.isEmpty()) return PreviewCode.Grant.Invalid

        if (normalised.startsWith("JJP1", ignoreCase = true)) {
            if (normalised == lastCode) return lastGrant
            val key = signingKey ?: return PreviewCode.Grant.Invalid
            val result = PreviewCode.verify(normalised, key, today, revokedSerials)
            lastCode = normalised
            lastGrant = result
            return result
        }

        return if (hash(normalise(normalised)) in legacyHashes) {
            PreviewCode.Grant.Valid(PreviewFeature.entries.toSet(), expiry = null, serial = -1)
        } else {
            PreviewCode.Grant.Invalid
        }
    }

    /** The set of [PreviewFeature]s [code] currently grants - empty if invalid, expired or revoked. */
    fun grantedFeatures(code: String, today: LocalDate = LocalDate.now()): Set<PreviewFeature> =
        (grant(code, today) as? PreviewCode.Grant.Valid)?.features ?: emptySet()

    private fun normalise(code: String): String = code.trim().lowercase().replace(Regex("\\s+"), "")

    private fun hash(normalisedCode: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((normalisedCode + SALT).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadLegacyHashes(): Set<String> {
        val stream = javaClass.getResourceAsStream(LEGACY_HASHES_RESOURCE) ?: return emptySet()
        val text = stream.readBytes().decodeToString()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    private fun loadSigningKey(): ByteArray? = javaClass.getResourceAsStream(SIGNING_KEY_RESOURCE)?.readBytes()

    private fun loadRevokedSerials(): Set<Int> {
        val stream = javaClass.getResourceAsStream(REVOKED_SERIALS_RESOURCE) ?: return emptySet()
        val text = stream.readBytes().decodeToString()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }
}
