package `in`.kkkev.jjidea.vcs.ignore

import com.intellij.openapi.progress.ProcessCanceledException
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [GitignoreCache.collectIgnored] — the pruning scan added to fix GitHub #35.
 *
 * Three concerns:
 * 1. Output equivalence: same ignored-file set as the existing [GitignoreCache.isIgnored] per-file check.
 * 2. Operation-count: ignored dirs are never descended into (O(non-ignored) work).
 * 3. Cancellation: checkCanceled is honoured with prompt abort.
 */
class GitignoreScanTest {
    @TempDir
    lateinit var root: File

    private fun rootGitignore(vararg lines: String) = File(root, ".gitignore").writeText(lines.joinToString("\n"))
    private fun subGitignore(dir: String, vararg lines: String) {
        File(root, dir).mkdirs()
        File(root, "$dir/.gitignore").writeText(lines.joinToString("\n"))
    }

    /** Expand collectIgnored results: for each reported path, if it's a dir on disk expand to leaves; return flat file set. */
    private fun collectIgnoredLeaves(cache: GitignoreCache): Set<String> {
        val result = mutableSetOf<String>()
        cache.collectIgnored(FileScanNode(root)) { relPath, isDir ->
            if (isDir) {
                File(root, relPath).walk().filter { it.isFile }.forEach {
                    result.add(it.toRelativeString(root).replace('\\', '/'))
                }
            } else {
                result.add(relPath)
            }
        }
        return result
    }

    /** Brute-force baseline using the proven isIgnored per-file method. */
    private fun bruteForceIgnored(cache: GitignoreCache): Set<String> {
        val result = mutableSetOf<String>()
        root.walk().filter { it.isFile }.forEach { file ->
            val relPath = file.toRelativeString(root).replace('\\', '/')
            if (!relPath.startsWith(".gitignore") && cache.isIgnored(relPath, false)) {
                result.add(relPath)
            }
        }
        return result
    }

    private fun mkFile(path: String) = File(root, path).also {
        it.parentFile.mkdirs()
        it.createNewFile()
    }

    // ─── Output equivalence (regression anchor) ───────────────────────────────

    @Nested
    inner class `output equivalence with isIgnored` {
        @Test
        fun `simple pattern — same leaves`() {
            rootGitignore("*.log")
            mkFile("debug.log")
            mkFile("src/Main.kt")
            val cache = GitignoreCache(root)
            collectIgnoredLeaves(cache) shouldBe bruteForceIgnored(cache)
        }

        @Test
        fun `entire directory ignored — same leaves`() {
            rootGitignore("build/")
            mkFile("build/classes/Main.class")
            mkFile("build/intermediates/debug/Foo.class")
            mkFile("src/Main.kt")
            val cache = GitignoreCache(root)
            collectIgnoredLeaves(cache) shouldBe bruteForceIgnored(cache)
        }

        @Test
        fun `negation — re-included file not in either set`() {
            rootGitignore("*.log", "!important.log")
            mkFile("debug.log")
            mkFile("important.log")
            val cache = GitignoreCache(root)
            collectIgnoredLeaves(cache) shouldBe bruteForceIgnored(cache)
        }

        @Test
        fun `nested gitignore override — subdirectory re-includes`() {
            rootGitignore("*.class")
            subGitignore("src", "!*.class")
            mkFile("Main.class")
            mkFile("src/Main.class")
            val cache = GitignoreCache(root)
            collectIgnoredLeaves(cache) shouldBe bruteForceIgnored(cache)
        }

        @Test
        fun `glob double-star — same leaves`() {
            rootGitignore("**/generated/")
            mkFile("src/generated/Foo.kt")
            mkFile("src/main/generated/Bar.kt")
            mkFile("src/Main.kt")
            val cache = GitignoreCache(root)
            collectIgnoredLeaves(cache) shouldBe bruteForceIgnored(cache)
        }
    }

    // ─── Operation-count (scale) ───────────────────────────────────────────────

    /** Minimal in-memory node that counts children() calls. */
    private class TrackingNode(
        override val name: String,
        override val isDirectory: Boolean,
        private val childList: List<TrackingNode>,
        val childrenCalls: AtomicInteger = AtomicInteger()
    ) : IgnoreScanNode {
        override fun children(): List<IgnoreScanNode> {
            childrenCalls.incrementAndGet()
            return childList
        }

        companion object {
            fun file(name: String) = TrackingNode(name, false, emptyList())
            fun dir(name: String, children: List<TrackingNode> = emptyList()) =
                TrackingNode(name, true, children)
        }
    }

    @Nested
    inner class `operation count` {
        @Test
        fun `ignored directory is never descended into`() {
            rootGitignore("node_modules/")
            val nmChildren = (1..100).map { TrackingNode.file("pkg_$it.js") }
            val nodeModules = TrackingNode.dir("node_modules", nmChildren)
            val srcMain = TrackingNode.file("Main.kt")
            val root = TrackingNode.dir("root", listOf(srcMain, nodeModules))

            val cache = GitignoreCache(this@GitignoreScanTest.root)
            val reported = mutableListOf<String>()
            val stats = cache.collectIgnored(root) { relPath, _ -> reported.add(relPath) }

            reported shouldContainExactlyInAnyOrder listOf("node_modules")
            nodeModules.childrenCalls.get() shouldBe 0
            // ScanStats: 2 children iterated at root level (srcMain + nodeModules); 1 ignored
            stats.visited shouldBe 2L
            stats.ignored shouldBe 1L
        }

        @Test
        fun `visit count is O(non-ignored) independent of ignored-dir size`() {
            rootGitignore("node_modules/")
            // 100k synthetic children — would be unacceptably slow without pruning
            val bigChildren = (1..100_000).map { TrackingNode.file("mod_$it.js") }
            val nodeModules = TrackingNode.dir("node_modules", bigChildren)
            val src = TrackingNode.dir("src", listOf(TrackingNode.file("Main.kt")))
            val rootNode = TrackingNode.dir("root", listOf(src, nodeModules))

            val cache = GitignoreCache(this@GitignoreScanTest.root)
            val reported = mutableListOf<String>()
            val stats = cache.collectIgnored(rootNode) { relPath, _ -> reported.add(relPath) }

            reported shouldContainExactlyInAnyOrder listOf("node_modules")
            // root + src each had children() called exactly once; node_modules never
            nodeModules.childrenCalls.get() shouldBe 0
            rootNode.childrenCalls.get() shouldBe 1
            src.childrenCalls.get() shouldBe 1
            // visited: 2 at root (src + nodeModules) + 1 at src (Main.kt) = 3; ignored: 1 (nodeModules)
            stats.visited shouldBe 3L
            stats.ignored shouldBe 1L
        }

        @Test
        fun `non-ignored files under non-ignored dirs are all visited`() {
            rootGitignore("*.log")
            val logs = (1..5).map { TrackingNode.file("run$it.log") }
            val srcs = (1..3).map { TrackingNode.file("File$it.kt") }
            val rootNode = TrackingNode.dir("root", logs + srcs)

            val cache = GitignoreCache(this@GitignoreScanTest.root)
            val reported = mutableListOf<String>()
            cache.collectIgnored(rootNode) { relPath, _ -> reported.add(relPath) }

            reported shouldContainExactlyInAnyOrder (1..5).map { "run$it.log" }
        }
    }

    // ─── Cancellation ─────────────────────────────────────────────────────────

    @Nested
    inner class `cancellation` {
        @Test
        fun `ProcessCanceledException from checkCanceled propagates immediately`() {
            rootGitignore("*.log")
            var calls = 0
            val checkCanceled: () -> Unit = {
                calls++
                if (calls >= 2) throw ProcessCanceledException()
            }
            // Deep tree: root → a → b → c (checkCanceled on entering a or b should abort)
            val deepTree = TrackingNode.dir(
                "root",
                listOf(
                    TrackingNode.dir("a", listOf(TrackingNode.dir("b", listOf(TrackingNode.file("c.txt")))))
                )
            )

            val cache = GitignoreCache(this@GitignoreScanTest.root)
            var threw = false
            try {
                cache.collectIgnored(deepTree, checkCanceled) { _, _ -> }
            } catch (_: ProcessCanceledException) {
                threw = true
            }
            threw shouldBe true
            // Must have aborted before exhausting the tree
            calls shouldBe 2
        }

        @Test
        fun `checkCanceled is throttled within a single flat directory, not just once per directory`() {
            // Regression test for GitHub #35: a directory with many children but no
            // subdirectories must still yield to checkCanceled periodically, not only once
            // on entry to the (single) directory.
            val childCount = (IGNORE_SCAN_CHECK_INTERVAL * 5).toInt()
            val flatChildren = (1..childCount).map { TrackingNode.file("f$it.txt") }
            val flatDir = TrackingNode.dir("root", flatChildren)

            val cache = GitignoreCache(this@GitignoreScanTest.root)
            var calls = 0
            cache.collectIgnored(flatDir, { calls++ }) { _, _ -> }

            // 1 call on directory entry + 1 per IGNORE_SCAN_CHECK_INTERVAL visited children
            calls shouldBe (1 + childCount / IGNORE_SCAN_CHECK_INTERVAL.toInt())
        }

        @Test
        fun `watchdog throwing partway through a flat directory aborts before visiting all children`() {
            val childCount = (IGNORE_SCAN_CHECK_INTERVAL * 5).toInt()
            val flatChildren = (1..childCount).map { TrackingNode.file("f$it.txt") }
            val flatDir = TrackingNode.dir("root", flatChildren)

            val cache = GitignoreCache(this@GitignoreScanTest.root)
            var calls = 0
            val checkCanceled: () -> Unit = {
                calls++
                if (calls >= 3) throw ProcessCanceledException()
            }

            var threw = false
            val reported = mutableListOf<String>()
            try {
                cache.collectIgnored(flatDir, checkCanceled) { relPath, _ -> reported.add(relPath) }
            } catch (_: ProcessCanceledException) {
                threw = true
            }
            threw shouldBe true
            // Aborted well before the directory's child list was exhausted
            calls shouldBe 3
        }
    }
}
