package `in`.kkkev.jjidea.preview

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Offline CLI for minting/inspecting `JJP1` preview access codes ([PreviewCode]). Deliberately
 * lives under `src/test`, never `src/main`: it must never ship inside the plugin jar, since it
 * needs the same signing key the plugin only ever sees as a verifier via
 * `preview/code-key.bin` (see `build.gradle.kts`'s `generatePreviewCodeKey` task and
 * `docs/design/preview-gating-and-dnd-sequencing.md`).
 *
 * Run via `./gradlew previewCode --args="<command> ..."`. Not a JUnit test - has no `@Test`, so
 * the test runner never picks it up; it's just a `main()` sharing the test classpath so it can
 * depend on [PreviewCode] without a new source set.
 *
 * Commands:
 * - `keygen` - writes a new random 32-byte key, base64, to `~/.config/jj-idea/preview-code-key`
 *   (refuses to overwrite an existing one). Prints next steps.
 * - `mint --features <id>[,<id>...]|all [--expires yyyy-MM] [--serial N] [--for X] [--ref Y]` -
 *   prints a code and a markdown registry row.
 * - `inspect <code>` - decodes and verifies a code against the local key.
 */
object PreviewCodeMinter {
    private val keyPath: File
        get() = File(System.getProperty("user.home"), ".config/jj-idea/preview-code-key")

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            printUsageAndExit()
        }
        when (args[0]) {
            "keygen" -> keygen()
            "mint" -> mint(args.drop(1))
            "inspect" -> inspect(args.drop(1))
            else -> printUsageAndExit()
        }
    }

    private fun printUsageAndExit(): Nothing {
        println(
            """
            Usage:
              previewCode keygen
              previewCode mint --features <id>[,<id>...]|all [--expires yyyy-MM] [--serial N] [--for X] [--ref Y]
              previewCode inspect <code>

            Known feature ids: ${PreviewFeature.entries.joinToString(", ") { it.id }}
            """.trimIndent()
        )
        exitProcess(1)
    }

    private fun keygen() {
        if (keyPath.exists()) {
            println("Refusing to overwrite existing key at $keyPath")
            exitProcess(1)
        }
        keyPath.parentFile.mkdirs()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        Files.write(keyPath.toPath(), Base64.getEncoder().encode(key))
        try {
            Files.setPosixFilePermissions(keyPath.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows) - best effort only.
        }
        println("Wrote a new signing key to $keyPath")
        println()
        println("Next steps:")
        println("1. Back up this file's contents in your password manager - losing it means rotating keys.")
        println("2. Set the GitHub secret: gh secret set PREVIEW_CODE_KEY --repo kkkev/jj-idea < $keyPath")
        println("3. Cut a release so the plugin ships a verifier for codes signed with this key.")
    }

    private fun mint(rest: List<String>) {
        val opts = parseOptions(rest)
        val featuresArg = opts["--features"] ?: run {
            println("mint requires --features <id>[,<id>...]|all")
            exitProcess(1)
        }
        val all = featuresArg == "all"
        val features = if (all) {
            emptySet()
        } else {
            featuresArg.split(",").map { id ->
                PreviewFeature.entries.find { it.id == id } ?: run {
                    println("Unknown feature id: $id (known: ${PreviewFeature.entries.joinToString(", ") { it.id }})")
                    exitProcess(1)
                }
            }.toSet()
        }
        val expiryMonth = opts["--expires"]?.let { PreviewCode.monthsSinceEpoch(YearMonth.parse(it)) } ?: 0
        val serial = opts["--serial"]?.toInt() ?: (0..0xFFFF).random()
        val key = loadKey()

        val code = PreviewCode.encode(features, expiryMonth, serial, key, all = all)
        val featuresLabel = if (all) "ALL" else features.joinToString(",") { it.id }
        val expiresLabel = opts["--expires"] ?: "never"

        println(code)
        println()
        println("Registry row:")
        println(
            "| $serial | `$code` | $featuresLabel | $expiresLabel | ${opts["--for"] ?: ""} | " +
                "${LocalDate.now()} | ${opts["--ref"] ?: ""} | Granted |"
        )
    }

    private fun inspect(rest: List<String>) {
        val code = rest.firstOrNull() ?: run {
            println("inspect requires a code argument")
            exitProcess(1)
        }
        val key = loadKey()
        when (val grant = PreviewCode.verify(code, key)) {
            is PreviewCode.Grant.Valid -> {
                println("Valid.")
                println("Features: ${grant.features.joinToString(", ") { it.id }}")
                println("Expiry: ${grant.expiry?.toString() ?: "never"}")
                println("Serial: ${grant.serial}")
            }
            is PreviewCode.Grant.Expired -> println("Expired on ${grant.lastValidDate}.")
            is PreviewCode.Grant.Revoked -> println("Revoked (per the local revoked-serials.txt, if present).")
            is PreviewCode.Grant.Invalid -> println("Invalid: malformed, tampered, or signed with a different key.")
        }
    }

    private fun loadKey(): ByteArray {
        if (!keyPath.exists()) {
            println("No key found at $keyPath - run `previewCode keygen` first.")
            exitProcess(1)
        }
        return Base64.getDecoder().decode(keyPath.readText().trim())
    }

    private fun parseOptions(args: List<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val key = args[i]
            val value = args.getOrNull(i + 1) ?: run {
                println("Missing value for $key")
                exitProcess(1)
            }
            map[key] = value
            i += 2
        }
        return map
    }
}
