package `in`.kkkev.jjidea.diffedit

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.lang.UrlClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

private val log = Logger.getInstance("in.kkkev.jjidea.diffedit.DiffEditTool")

/**
 * Tools for driving `jj split --tool` with a non-interactive diff editor.
 *
 * The diff-editor protocol: jj writes the base tree to `$left` and the revision tree to `$right`,
 * invokes the configured editor, then reads `$right` back as the first-commit content.
 *
 * Strategy:
 * 1. [buildStagingTree] writes the pre-computed first-commit content into a temp directory.
 * 2. [diffEditConfigArgs] produces `--config` flags that register a one-shot diff editor
 *    ([HunkApplyMain]) which will copy the staging tree into `$right` when invoked.
 * 3. The staging directory must be deleted by the caller after the jj command completes.
 */
object DiffEditTool {
    /**
     * Build a temporary staging directory containing the desired first-commit state of each
     * changed file.
     *
     * @param perFileContent Map of repo-relative POSIX path → desired content.
     *   - Non-null value: write this content to `stagingDir/<path>` (file goes to first commit).
     *   - Null value: omit from stagingDir (file absent from first commit → goes to second commit).
     * @return Path to the staging directory. The caller is responsible for deleting it.
     */
    fun buildStagingTree(perFileContent: Map<String, String?>): Path {
        val stagingDir = Files.createTempDirectory("jj-idea-split-staging-")
        for ((relPath, content) in perFileContent) {
            if (content == null) continue
            val target = stagingDir.resolve(relPath)
            target.parent?.toFile()?.mkdirs()
            target.writeText(content)
        }
        log.debug("Built staging tree at $stagingDir with ${perFileContent.values.count { it != null }} files")
        return stagingDir
    }

    /**
     * Produce `--config NAME=VALUE` argument pairs that register a one-shot diff editor
     * pointing at [HunkApplyMain] with [stagingDir] as the pre-computed source.
     *
     * The returned list has the form `["NAME1=VALUE1", "NAME2=VALUE2", ...]`.
     * Each element should be prefixed with `--config` when passed to jj.
     *
     * @param toolName An ephemeral tool name (e.g. `"jj-idea-hunk-apply"`).
     * @param stagingDir Path to the staging directory built by [buildStagingTree].
     */
    fun diffEditConfigArgs(toolName: String, stagingDir: Path): List<String> {
        val java = discoverJavaExecutable()
        val classpath = discoverClasspath()
        val mainClass = "in.kkkev.jjidea.diffedit.HunkApplyMain"
        val stagingPath = stagingDir.absolutePathString()

        // jj runs: program edit-args, i.e. `java [edit-args]` — so edit-args must NOT repeat java.
        // jj substitutes $left and $right into edit-args; we capture them as args[1] and args[2].
        // HunkApplyMain signature: <stagingDir> <leftDir> <rightDir>
        val editArgs = tomlArray(listOf("-cp", classpath, mainClass, stagingPath, "\$left", "\$right"))

        return listOf(
            "merge-tools.$toolName.program=$java",
            "merge-tools.$toolName.edit-args=$editArgs",
            "ui.diff-editor=$toolName"
        )
    }

    /** The TOML name for the ephemeral diff editor registered per split invocation. */
    const val TOOL_NAME = "jj-idea-hunk-apply"

    // ---- classpath / JRE discovery ----

    /**
     * Find the `java` executable from the running JVM.
     * Falls back to `java` (on PATH) if the JVM home is unavailable.
     */
    internal fun discoverJavaExecutable(): String {
        val javaHome = System.getProperty("java.home") ?: return "java"
        val javaExe = if (System.getProperty("os.name", "").lowercase().contains("win")) {
            "java.exe"
        } else {
            "java"
        }
        val candidate = java.io.File(javaHome, "bin/$javaExe")
        return if (candidate.exists()) candidate.absolutePath else "java"
    }

    /**
     * Find the classpath needed to run [HunkApplyMain] in a fresh JVM.
     *
     * [HunkApplyMain] needs two things on its `-cp`: this plugin's own compiled classes, and
     * `kotlin-stdlib` (this project doesn't bundle its own copy — `kotlin.stdlib.default.dependency
     * =false` in `gradle.properties` — so it's whatever the target IDE supplies at runtime,
     * and *where* that lives is not something we can hardcode: it varies by IDE build and isn't
     * necessarily a plain jar under this plugin's own `lib/`).
     *
     * Strategy: introspect the classloader that actually resolves each needed class, at the
     * point we need it — this works regardless of *how* the platform wires kotlin-stdlib to
     * plugins, because we're asking the live, already-working resolution mechanism directly
     * rather than assuming a directory layout:
     * - [DiffEditTool]'s own classloader files (this plugin's own classes).
     * - `kotlin.jvm.internal.Intrinsics`'s own classloader files (kotlin-stdlib — same
     *   classloader as above in some IDE builds, a separate one in others).
     *
     * Regression: a previous version only checked [DiffEditTool]'s own classloader. Under a
     * real installed plugin, that classloader's `getFiles()` didn't include `kotlin-stdlib`,
     * so the subprocess launched without it and failed with
     * `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`.
     *
     * Falls back to `java.class.path` if neither classloader yields anything (e.g. the
     * Gradle/JUnit classloader in unit tests, where the helper is never actually launched).
     *
     * Note: `codeSource.location` **cannot** be used as the primary signal. `PluginClassLoader`
     * defines classes with a null `ProtectionDomain` (`UrlClassLoader.consumeClassData` passes
     * `null` as the domain argument), so `codeSource` is null for plugin-defined classes. This
     * is structural, not incidental — `UrlClassLoader.getFiles()` is the reliable source.
     */
    internal fun discoverClasspath(): String {
        val entries = LinkedHashSet<String>()
        entries += classloaderFiles(DiffEditTool::class.java)
        entries += classloaderFiles(Class.forName("kotlin.jvm.internal.Intrinsics"))

        if (entries.isNotEmpty()) {
            return entries.joinToString(java.io.File.pathSeparator)
        }

        log.warn(
            "discoverClasspath: no classpath entries resolved from either classloader; " +
                "falling back to java.class.path"
        )
        return System.getProperty("java.class.path").orEmpty()
    }

    /**
     * The jar/directory `Path`s [clazz]'s own classloader was constructed with, or empty if
     * that classloader isn't a [UrlClassLoader] (e.g. a non-plugin test classloader).
     * No URL parsing — OS-independent, no %20/!/drive-letter hazards.
     */
    private fun classloaderFiles(clazz: Class<*>): List<String> {
        val loader = clazz.classLoader
        return if (loader is UrlClassLoader) {
            loader.files.map { it.toAbsolutePath().toString() }
        } else {
            emptyList()
        }
    }

    /** Encode a list of strings as a TOML inline array (e.g. `["a","b","c"]`). */
    internal fun tomlArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",") {
            "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
}
