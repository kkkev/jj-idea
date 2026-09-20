@file:JvmName("MergeApplyMain")

package `in`.kkkev.jjidea.diffedit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Standalone JVM entry point invoked by `jj resolve --tool` as a non-interactive 3-way merge
 * tool, prototyping the interactive write-back path for jj-idea-cf2c (S4 spike, see
 * `docs/design/jj-idea-n6fz-native-conflict-ux.md` § S4 findings).
 *
 * Same protocol family as [HunkApplyMain] (diff-editor) but for `merge-tools.<name>.merge-args`:
 * jj substitutes `$base`/`$left`/`$right`/`$output` and invokes the configured tool; after it
 * exits, jj reads `$output` back as the resolved file content. This prototype doesn't need
 * `$base`/`$left`/`$right` - the caller has already computed the resolved bytes (e.g. from an
 * in-IDE merge editor) and staged them to a file, so this just copies that file to `$output`.
 *
 * Called via `merge-tools.<tool>.merge-args` as:
 *   `<java> -cp <classpath> in.kkkev.jjidea.diffedit.MergeApplyMain <stagedFile> <output>`
 */
fun main(args: Array<String>) {
    require(args.size >= 2) {
        "Usage: MergeApplyMain <stagedFile> <output>"
    }
    val stagedFile = Path.of(args[0])
    val output = Path.of(args[1])
    output.parent?.toFile()?.mkdirs()
    Files.copy(stagedFile, output, StandardCopyOption.REPLACE_EXISTING)
}
