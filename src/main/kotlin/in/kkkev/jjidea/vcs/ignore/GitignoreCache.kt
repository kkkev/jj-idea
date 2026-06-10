package `in`.kkkev.jjidea.vcs.ignore

import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-repository cache of parsed .gitignore rules.
 *
 * Implements gitignore matching semantics without depending on JGit:
 * - Deepest .gitignore wins over ancestor .gitignores.
 * - Within one file, the last matching rule wins.
 * - If a parent directory is itself ignored, all children are implicitly ignored.
 * - .git/info/exclude is merged with the root .gitignore (for colocated repos).
 */
class GitignoreCache(private val repoRoot: File) {
    // key = directory path relative to repoRoot (empty = root); empty Optional = no .gitignore there
    private val nodes = ConcurrentHashMap<String, Optional<PatternList>>()

    fun isIgnored(file: File): Boolean {
        val relPath = try {
            file.toRelativeString(repoRoot).replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (relPath.isEmpty()) return false
        return isIgnored(relPath, file.isDirectory)
    }

    fun isIgnored(repoRelPath: String, isDirectory: Boolean): Boolean {
        val parts = repoRelPath.split("/")
        // If any ancestor directory is itself ignored, the file is implicitly ignored too.
        for (i in 1 until parts.size) {
            if (isPathIgnored(parts.subList(0, i), isDirectory = true)) return true
        }
        return isPathIgnored(parts, isDirectory)
    }

    private fun isPathIgnored(parts: List<String>, isDirectory: Boolean): Boolean {
        // Check from deepest applicable .gitignore up to root (deepest wins).
        for (depth in parts.size - 1 downTo 0) {
            val node = getNode(parts.subList(0, depth)) ?: continue
            val entryPath = parts.subList(depth, parts.size).joinToString("/")
            when (node.match(entryPath, isDirectory)) {
                MatchResult.IGNORED -> return true
                MatchResult.NOT_IGNORED -> return false
                MatchResult.NO_MATCH -> continue
            }
        }
        return false
    }

    /**
     * Pruning scan: visits only non-ignored entries, reporting ignored directories as single
     * entries (never descending into them). O(non-ignored entries) work.
     *
     * The [root] abstraction allows injection of in-memory trees in tests.
     * [checkCanceled] is called once per directory entered; it should throw on cancellation.
     * [onIgnored] receives the repo-relative path and isDirectory for each ignored entry.
     */
    fun collectIgnored(
        root: IgnoreScanNode,
        checkCanceled: () -> Unit = {},
        onIgnored: (relPath: String, isDirectory: Boolean) -> Unit
    ) {
        fun recurse(node: IgnoreScanNode, dirParts: List<String>, stack: List<PatternList?>) {
            checkCanceled()
            for (child in node.children()) {
                if (child.isDirectory && (child.name == ".jj" || child.name == ".git")) continue
                val parts = dirParts + child.name
                if (matchesStack(stack, parts, child.isDirectory)) {
                    // Prune: report the dir itself; git semantics forbid re-including children
                    onIgnored(parts.joinToString("/"), child.isDirectory)
                } else if (child.isDirectory) {
                    recurse(child, parts, stack + getNode(parts))
                }
            }
        }
        recurse(root, emptyList(), listOf(getNode(emptyList())))
    }

    // stack[i] = PatternList from the .gitignore at depth i (root=0). Deepest wins.
    private fun matchesStack(stack: List<PatternList?>, parts: List<String>, isDir: Boolean): Boolean {
        for (depth in stack.indices.reversed()) {
            val node = stack[depth] ?: continue
            val entryPath = parts.subList(depth, parts.size).joinToString("/")
            when (node.match(entryPath, isDir)) {
                MatchResult.IGNORED -> return true
                MatchResult.NOT_IGNORED -> return false
                MatchResult.NO_MATCH -> {}
            }
        }
        return false
    }

    private fun getNode(dirParts: List<String>): PatternList? {
        val key = dirParts.joinToString("/")
        return nodes.computeIfAbsent(key) { k ->
            val dir = if (dirParts.isEmpty()) repoRoot else File(repoRoot, k)
            val lines = mutableListOf<String>()
            File(dir, ".gitignore").takeIf { it.isFile }?.readLines()?.let { lines += it }
            // .git/info/exclude is merged with root-level gitignore patterns for colocated repos
            if (dirParts.isEmpty()) {
                File(repoRoot, ".git/info/exclude").takeIf { it.isFile }?.readLines()?.let { lines += it }
            }
            val patterns = lines.mapNotNull { GitignorePattern.parse(it) }
            if (patterns.isEmpty()) Optional.empty() else Optional.of(PatternList(patterns))
        }.orElse(null)
    }

    fun invalidate() = nodes.clear()

    private enum class MatchResult { IGNORED, NOT_IGNORED, NO_MATCH }

    private class PatternList(private val patterns: List<GitignorePattern>) {
        // Last matching pattern in the file wins (git semantics).
        fun match(entryPath: String, isDirectory: Boolean): MatchResult {
            var result = MatchResult.NO_MATCH
            for (pattern in patterns) {
                if (pattern.matches(entryPath, isDirectory)) {
                    result = if (pattern.negation) MatchResult.NOT_IGNORED else MatchResult.IGNORED
                }
            }
            return result
        }
    }
}

/** Minimal tree abstraction so [GitignoreCache.collectIgnored] can be tested with in-memory trees. */
interface IgnoreScanNode {
    val name: String
    val isDirectory: Boolean
    fun children(): List<IgnoreScanNode>
}

/** Production [IgnoreScanNode] backed by a real [File]. */
internal class FileScanNode(private val file: File) : IgnoreScanNode {
    override val name get() = file.name
    override val isDirectory get() = file.isDirectory
    override fun children() = file.listFiles()?.map { FileScanNode(it) } ?: emptyList()
}

private class GitignorePattern(
    val negation: Boolean,
    private val directoryOnly: Boolean,
    private val regex: Regex
) {
    fun matches(entryPath: String, isDirectory: Boolean): Boolean {
        if (directoryOnly && !isDirectory) return false
        return regex.matches(entryPath)
    }

    companion object {
        fun parse(rawLine: String): GitignorePattern? {
            var line = rawLine.trimEnd()
            if (line.isEmpty() || line.startsWith("#")) return null
            val negation = line.startsWith("!")
            if (negation) line = line.substring(1)
            if (line.isEmpty()) return null
            val directoryOnly = line.endsWith("/")
            if (directoryOnly) line = line.dropLast(1)
            // A pattern is anchored (matches relative to gitignore's dir, not any depth) when
            // it contains a slash that is not a leading slash.
            val anchored = line.contains("/") && !line.startsWith("/")
            if (line.startsWith("/")) line = line.substring(1)
            return GitignorePattern(negation, directoryOnly, buildRegex(line, anchored))
        }

        private fun buildRegex(pattern: String, anchored: Boolean): Regex {
            val sb = StringBuilder("^")
            if (!anchored) sb.append("(?:.+/)?") // non-anchored: match at any path depth
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern.startsWith("**", i) -> {
                        val slashBefore = i == 0 || pattern[i - 1] == '/'
                        val slashAfter = i + 2 < pattern.length && pattern[i + 2] == '/'
                        if (slashBefore && slashAfter) {
                            sb.append("(?:.+/)?")
                            i += 3
                        } else {
                            sb.append(".*")
                            i += 2
                        }
                    }
                    pattern[i] == '*' -> {
                        sb.append("[^/]*")
                        i++
                    }
                    pattern[i] == '?' -> {
                        sb.append("[^/]")
                        i++
                    }
                    pattern[i] == '[' -> {
                        val end = pattern.indexOf(']', i + 1)
                        if (end > i) {
                            sb.append(pattern.substring(i, end + 1))
                            i = end + 1
                        } else {
                            sb.append("\\[")
                            i++
                        }
                    }
                    else -> {
                        sb.append(Regex.escape(pattern[i].toString()))
                        i++
                    }
                }
            }
            sb.append("$")
            return Regex(sb.toString())
        }
    }
}
