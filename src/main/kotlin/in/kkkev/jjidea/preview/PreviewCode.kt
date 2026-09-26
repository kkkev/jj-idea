package `in`.kkkev.jjidea.preview

import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Codec for `JJP1` preview access codes: short, feature-scoped, and mintable offline at any time
 * without a plugin release, unlike the legacy hash codes in [AccessCode]'s `access-codes.txt`.
 *
 * A code is `JJP1-` followed by 16 Crockford-base32 characters (grouped in 4s with `-` for
 * display, e.g. `JJP1-ABCD-EFGH-JKMN-PQRS`) encoding a 4-byte payload plus a 6-byte truncated
 * HMAC-SHA256 tag over `"JJP1" + payload`, keyed by a secret shared between this codec and the
 * offline minting tool (`PreviewCodeMinter`, never shipped in the plugin).
 *
 * Payload layout (4 bytes, big-endian):
 * - `features: u8` - a bitmap, bit *n* = [PreviewFeature.bit] `n`. Bit 7 ([ALL_BIT]) is a
 *   wildcard granting every feature, including ones added after the code was minted.
 * - `expiry: u8` - months since [EPOCH_YEAR_MONTH], `0` meaning "never expires". A non-zero value
 *   `m` is valid through the last day of month `EPOCH_YEAR_MONTH + (m - 1)`.
 * - `serial: u16` - not used for validation; distinguishes otherwise-identical codes so each can
 *   be attributed and individually revoked (see `revoked-serials.txt`).
 *
 * Why HMAC rather than a public-key signature: this is explicitly "noise reduction, not DRM" (see
 * [AccessCode]'s KDoc) - the `-Djjidea.preview.<id>=true` system property already bypasses the
 * whole gate. A signature's only extra benefit over HMAC is stopping someone who has decompiled
 * the jar (and thus already has the key either way) from minting their own codes, which isn't
 * worth roughly 5x the code length (a 64-byte signature vs. a 6-byte truncated MAC).
 */
object PreviewCode {
    private const val PREFIX = "JJP1"
    private const val MAC_ALGORITHM = "HmacSHA256"
    private const val TAG_BYTES = 6
    private const val PAYLOAD_BYTES = 4
    private val EPOCH_YEAR_MONTH: YearMonth = YearMonth.of(2026, 1)

    /** Bit 7 of the features byte: grants every [PreviewFeature], including future ones. */
    const val ALL_BIT = 7

    /** Result of decoding and verifying a code string. */
    sealed interface Grant {
        /** A syntactically and cryptographically valid, currently-active grant. */
        data class Valid(val features: Set<PreviewFeature>, val expiry: LocalDate?, val serial: Int) : Grant

        /** Valid and well-formed, but its expiry date has passed. */
        data class Expired(val lastValidDate: LocalDate) : Grant

        /** Valid and well-formed, but its serial is in the revoked list. */
        data object Revoked : Grant

        /** Malformed, wrong length, tampered, or signed with a different key. */
        data object Invalid : Grant
    }

    /**
     * Encodes [features] and [expiryMonth] (months since [EPOCH_YEAR_MONTH], `0` = never) with
     * [serial] into a display-formatted code string, signed with [key]. Used by the minting tool;
     * exposed here (rather than only in the tool) so the codec and its tests stay the single
     * source of truth for the wire format. [all] sets the [ALL_BIT] wildcard, granting every
     * feature (including ones added after minting) regardless of [features].
     */
    fun encode(
        features: Set<PreviewFeature>,
        expiryMonth: Int,
        serial: Int,
        key: ByteArray,
        all: Boolean = false
    ): String {
        require(expiryMonth in 0..255) { "expiryMonth must fit in a byte: $expiryMonth" }
        require(serial in 0..0xFFFF) { "serial must fit in 16 bits: $serial" }
        val featureByte = features.fold(if (all) (1 shl ALL_BIT) else 0) { acc, f -> acc or (1 shl f.bit) }
        val payload = byteArrayOf(
            featureByte.toByte(),
            expiryMonth.toByte(),
            (serial shr 8).toByte(),
            serial.toByte()
        )
        val tag = hmac(key, PREFIX.toByteArray(Charsets.US_ASCII) + payload).copyOf(TAG_BYTES)
        val body = Base32Crockford.encode(payload + tag)
        return "$PREFIX-" + body.chunked(4).joinToString("-")
    }

    /**
     * Decodes and verifies [code] against [key], returning what it grants as of [today].
     * [revokedSerials] is checked only for a structurally/cryptographically valid code.
     */
    fun verify(
        code: String,
        key: ByteArray,
        today: LocalDate = LocalDate.now(),
        revokedSerials: Set<Int> = emptySet()
    ): Grant {
        val normalised = normalise(code)
        if (!normalised.startsWith(PREFIX)) return Grant.Invalid
        val body = normalised.removePrefix(PREFIX)
        val bytes = Base32Crockford.decode(body) ?: return Grant.Invalid
        if (bytes.size != PAYLOAD_BYTES + TAG_BYTES) return Grant.Invalid

        val payload = bytes.copyOfRange(0, PAYLOAD_BYTES)
        val tag = bytes.copyOfRange(PAYLOAD_BYTES, bytes.size)
        val expectedTag = hmac(key, PREFIX.toByteArray(Charsets.US_ASCII) + payload).copyOf(TAG_BYTES)
        if (!MessageDigest.isEqual(tag, expectedTag)) return Grant.Invalid

        val featureByte = payload[0].toInt() and 0xFF
        val expiryMonth = payload[1].toInt() and 0xFF
        val serial = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)

        if (serial in revokedSerials) return Grant.Revoked

        val expiryDate = if (expiryMonth == 0) {
            null
        } else {
            EPOCH_YEAR_MONTH.plusMonths((expiryMonth - 1).toLong()).atEndOfMonth()
        }
        if (expiryDate != null && today.isAfter(expiryDate)) return Grant.Expired(expiryDate)

        val features = if ((featureByte shr ALL_BIT) and 1 == 1) {
            PreviewFeature.entries.toSet()
        } else {
            PreviewFeature.entries.filter { (featureByte shr it.bit) and 1 == 1 }.toSet()
        }
        return Grant.Valid(features, expiryDate, serial)
    }

    /** Converts a calendar [YearMonth] to the `expiry` byte value `encode`/`verify` use. */
    fun monthsSinceEpoch(month: YearMonth): Int {
        val months = ChronoUnit.MONTHS.between(EPOCH_YEAR_MONTH, month) + 1
        require(months in 1..255) { "month out of encodable range: $month" }
        return months.toInt()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(key, MAC_ALGORITHM))
        return mac.doFinal(data)
    }

    private fun normalise(code: String): String =
        code.trim().uppercase().replace(Regex("[\\s-]"), "").replace('O', '0').replace('I', '1').replace('L', '1')
}

/** Crockford base32: excludes I/L/O/U, tolerant decoding not required here (input pre-normalised). */
private object Base32Crockford {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0L
        var bitsInBuffer = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bitsInBuffer += 8
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5
                val index = ((buffer shr bitsInBuffer) and 0x1F).toInt()
                sb.append(ALPHABET[index])
            }
        }
        if (bitsInBuffer > 0) {
            val index = ((buffer shl (5 - bitsInBuffer)) and 0x1F).toInt()
            sb.append(ALPHABET[index])
        }
        return sb.toString()
    }

    /** Returns null if [text] contains a character outside the Crockford alphabet. */
    fun decode(text: String): ByteArray? {
        var buffer = 0L
        var bitsInBuffer = 0
        val out = ArrayList<Byte>((text.length * 5) / 8 + 1)
        for (c in text) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value.toLong()
            bitsInBuffer += 5
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8
                out.add(((buffer shr bitsInBuffer) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
