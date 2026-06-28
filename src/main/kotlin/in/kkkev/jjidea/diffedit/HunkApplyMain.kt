@file:JvmName("HunkApplyMain")

package `in`.kkkev.jjidea.diffedit

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

/**
 * Standalone JVM entry point invoked by jj as a non-interactive diff editor.
 *
 * Called via the `merge-tools.<tool>.edit-args` config as:
 *   `<java> -cp <classpath> in.kkkev.jjidea.diffedit.HunkApplyMain <stagingDir> <leftDir> <rightDir>`
 *
 * jj substitutes `$left` and `$right` into the edit-args before invoking.
 * [leftDir] is the parent/base tree; it is used to restore excluded files so that the first
 * commit leaves them unchanged and their changes land in the second commit.
 *
 * After this exits, jj reads [rightDir] as the first-commit content.
 */
fun main(args: Array<String>) {
    require(args.size >= 3) {
        "Usage: HunkApplyMain <stagingDir> <leftDir> <rightDir>"
    }
    val stagingDir = Path.of(args[0])
    val leftDir = Path.of(args[1])
    val rightDir = Path.of(args[2])
    mirrorTree(stagingDir, leftDir, rightDir)
}

/**
 * Makes [rightDir] reflect the desired first-commit state:
 * - Files in [stagingDir]: copied to [rightDir] (created or overwritten).
 * - Files in [leftDir] but NOT in [stagingDir]: copied from [leftDir] to [rightDir],
 *   restoring them to the parent state so their changes land in the second commit.
 * - Files in [rightDir] but NOT in [stagingDir] AND NOT in [leftDir]: deleted,
 *   because they were newly added in the revision and excluded from the first commit.
 *
 * Purely in terms of java.nio.file; no platform dependencies — directly unit-testable.
 */
fun mirrorTree(stagingDir: Path, leftDir: Path, rightDir: Path) {
    val stagingFiles = collectRelativePaths(stagingDir)
    val leftFiles = collectRelativePaths(leftDir)
    val rightFiles = collectRelativePaths(rightDir)

    // Delete files in right that belong to neither staging nor left.
    // (Added in the revision but excluded from the first commit.)
    for (rel in rightFiles - stagingFiles - leftFiles) {
        rightDir.resolve(rel).toFile().delete()
    }

    // Restore files from left that are not going to the first commit.
    // (Modified or deleted in the revision but excluded → first commit leaves them at base state.)
    for (rel in leftFiles - stagingFiles) {
        val src = leftDir.resolve(rel)
        val dst = rightDir.resolve(rel)
        dst.parent?.toFile()?.mkdirs()
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
    }

    // Copy (create/overwrite) files from staging to right.
    for (rel in stagingFiles) {
        val src = stagingDir.resolve(rel)
        val dst = rightDir.resolve(rel)
        dst.parent?.toFile()?.mkdirs()
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
    }

    // Clean up empty directories left behind in right.
    pruneEmptyDirs(rightDir)
}

/** Recursively collect all file paths relative to [base], as strings. */
private fun collectRelativePaths(base: Path): Set<String> {
    if (!base.exists()) return emptySet()
    return Files.walk(base)
        .filter { !it.isDirectory() }
        .map { it.relativeTo(base).toString().replace(File.separatorChar, '/') }
        .toList()
        .toSet()
}

/** Remove empty directories inside [root] (bottom-up). */
private fun pruneEmptyDirs(root: Path) {
    Files.walk(root)
        .sorted(Comparator.reverseOrder())
        .filter { it != root && it.isDirectory() }
        .filter { dir -> Files.list(dir).use { it.findAny().isEmpty } }
        .forEach { Files.deleteIfExists(it) }
}
