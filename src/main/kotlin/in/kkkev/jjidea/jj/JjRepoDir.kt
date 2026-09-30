package `in`.kkkev.jjidea.jj

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import `in`.kkkev.jjidea.vcs.JujutsuVcsBase.Companion.DOT_JJ
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val log = Logger.getInstance("in.kkkev.jjidea.jj.JjRepoDir")

/**
 * The real jj repo directory for a workspace root. In a default repo `<root>/.jj/repo` is a
 * directory. In a secondary workspace (`jj workspace add`) it is a regular *file* whose contents
 * are the shared repo's path, relative to `<root>/.jj/` (older jj versions wrote absolute paths) —
 * so `op_heads/` etc. live there, not under the workspace root (jj-idea-xhnw).
 */
internal fun resolveJjRepoDir(workspaceRoot: Path): Path {
    val dotJj = workspaceRoot.resolve(DOT_JJ)
    val repo = dotJj.resolve("repo")
    if (!Files.isRegularFile(repo)) return repo
    return try {
        val target = Files.readString(repo).trim()
        if (target.isEmpty()) {
            log.warn("$repo is an empty pointer file; falling back to it as the repo directory")
            repo
        } else {
            dotJj.resolve(target).normalize()
        }
    } catch (e: IOException) {
        log.warn("Could not read repo pointer file $repo", e)
        repo
    }
}

/** [resolveJjRepoDir] as a system-independent path string, as VFS paths are. */
internal fun jjRepoDirPath(workspaceRoot: Path): String =
    FileUtil.toSystemIndependentName(resolveJjRepoDir(workspaceRoot).toString())

/** True if [path] equals or is under any of [dirs], respecting `/` boundaries (`op_heads2` is not under `op_heads`). */
internal fun isUnderAnyDir(path: String, dirs: Collection<String>): Boolean =
    dirs.any { path == it || path.startsWith("$it/") }
