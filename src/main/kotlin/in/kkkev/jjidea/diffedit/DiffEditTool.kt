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
     * Under the IntelliJ platform, classes are loaded by a `PluginClassLoader`, which extends
     * [UrlClassLoader]. That classloader's [UrlClassLoader.getFiles] method returns the list
     * of jar `Path` objects that make up this plugin's runtime classpath — typically the
     * plugin jar plus bundled dependencies such as `kotlin-stdlib`. These are joined with
     * [java.io.File.pathSeparator] to form the `-cp` argument.
     *
     * Note: `codeSource.location` **cannot** be used here. `PluginClassLoader` defines classes
     * with a null `ProtectionDomain` (`UrlClassLoader.consumeClassData` passes `null` as the
     * domain argument), so `codeSource` is always null for plugin classes. This is structural,
     * not incidental.
     *
     * Falls back to `java.class.path` for non-plugin classloaders (e.g. the Gradle/JUnit
     * classloader in unit tests, where the helper is never actually launched).
     */
    internal fun discoverClasspath(): String {
        val loader = DiffEditTool::class.java.classLoader
        // PluginClassLoader extends UrlClassLoader; getFiles() returns the plugin's own
        // classpath jars (plugin jar + bundled kotlin-stdlib etc.) as native Path objects.
        // No URL parsing — OS-independent, no %20/!/drive-letter hazards.
        if (loader is UrlClassLoader) {
            val files = loader.files
            if (files.isNotEmpty()) {
                return files.joinToString(java.io.File.pathSeparator) { it.toAbsolutePath().toString() }
            }
        }
        // Fallback: non-plugin classloader (unit tests). The helper is never launched here
        // so any non-empty classpath is sufficient to pass the args-sanity tests.
        log.warn(
            "discoverClasspath: classloader is not a UrlClassLoader " +
                "(${loader?.javaClass?.name}); falling back to java.class.path"
        )
        return System.getProperty("java.class.path").orEmpty()
    }

    /** Encode a list of strings as a TOML inline array (e.g. `["a","b","c"]`). */
    internal fun tomlArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",") {
            "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
}
