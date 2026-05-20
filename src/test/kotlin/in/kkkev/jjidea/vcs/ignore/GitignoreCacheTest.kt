package `in`.kkkev.jjidea.vcs.ignore

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GitignoreCacheTest {
    @TempDir
    lateinit var root: File

    private val cache get() = GitignoreCache(root)

    private fun rootGitignore(vararg lines: String) = File(root, ".gitignore").writeText(lines.joinToString("\n"))
    private fun subGitignore(dir: String, vararg lines: String) {
        File(root, dir).mkdirs()
        File(root, "$dir/.gitignore").writeText(lines.joinToString("\n"))
    }

    @Nested
    inner class `simple patterns` {
        @Test
        fun `exact file name match`() {
            rootGitignore("*.log")
            cache.isIgnored("debug.log", false) shouldBe true
            cache.isIgnored("debug.txt", false) shouldBe false
        }

        @Test
        fun `directory pattern only matches directories`() {
            rootGitignore("build/")
            cache.isIgnored("build", true) shouldBe true
            cache.isIgnored("build", false) shouldBe false
        }

        @Test
        fun `exact path pattern`() {
            rootGitignore("src/generated/")
            cache.isIgnored("src/generated", true) shouldBe true
            cache.isIgnored("src/other", true) shouldBe false
        }

        @Test
        fun `blank lines and comments are skipped`() {
            rootGitignore("# This is a comment", "", "*.log", "# another comment")
            cache.isIgnored("error.log", false) shouldBe true
            cache.isIgnored("readme.md", false) shouldBe false
        }
    }

    @Nested
    inner class `parent directory propagation` {
        @Test
        fun `file inside ignored directory is also ignored`() {
            rootGitignore("build/")
            cache.isIgnored("build/classes/Main.class", false) shouldBe true
        }

        @Test
        fun `deeply nested file inside ignored directory`() {
            rootGitignore("build/")
            cache.isIgnored("build/intermediates/classes/debug/Main.class", false) shouldBe true
        }

        @Test
        fun `sibling directory not affected`() {
            rootGitignore("build/")
            cache.isIgnored("src/Main.kt", false) shouldBe false
        }
    }

    @Nested
    inner class `negation` {
        @Test
        fun `negation re-includes after ignore`() {
            rootGitignore("*.log", "!important.log")
            cache.isIgnored("debug.log", false) shouldBe true
            cache.isIgnored("important.log", false) shouldBe false
        }
    }

    @Nested
    inner class `nested gitignore hierarchy` {
        @Test
        fun `subdirectory gitignore overrides root`() {
            rootGitignore("*.class")
            subGitignore("src", "!*.class")
            cache.isIgnored("Main.class", false) shouldBe true
            cache.isIgnored("src/Main.class", false) shouldBe false
        }

        @Test
        fun `subdirectory pattern scoped to subtree`() {
            subGitignore("src", "*.tmp")
            cache.isIgnored("src/gen/Foo.tmp", false) shouldBe true
            cache.isIgnored("Foo.tmp", false) shouldBe false // not in root .gitignore
        }

        @Test
        fun `root pattern does not bleed into subdir gitignore scope`() {
            subGitignore("lib", "*.jar")
            cache.isIgnored("some.jar", false) shouldBe false // lib/.gitignore only covers lib/
            cache.isIgnored("lib/some.jar", false) shouldBe true
        }
    }

    @Nested
    inner class `glob patterns` {
        @Test
        fun `double star wildcard matches any depth`() {
            rootGitignore("**/generated/")
            cache.isIgnored("generated", true) shouldBe true
            cache.isIgnored("src/generated", true) shouldBe true
            cache.isIgnored("src/main/generated", true) shouldBe true
        }

        @Test
        fun `single star in path matches one directory segment`() {
            rootGitignore("src/*/generated/")
            cache.isIgnored("src/main/generated", true) shouldBe true
            cache.isIgnored("src/test/generated", true) shouldBe true
            cache.isIgnored("src/generated", true) shouldBe false
        }
    }

    @Nested
    inner class `git info exclude` {
        @Test
        fun `exclude file patterns apply at repo root`() {
            File(root, ".git/info").mkdirs()
            File(root, ".git/info/exclude").writeText("*.bak")
            cache.isIgnored("backup.bak", false) shouldBe true
        }

        @Test
        fun `exclude patterns merge with root gitignore`() {
            rootGitignore("*.log")
            File(root, ".git/info").mkdirs()
            File(root, ".git/info/exclude").writeText("*.bak")
            cache.isIgnored("debug.log", false) shouldBe true
            cache.isIgnored("backup.bak", false) shouldBe true
        }
    }

    @Nested
    inner class `invalidation` {
        @Test
        fun `after invalidate new patterns take effect`() {
            rootGitignore("*.log")
            val c = GitignoreCache(root)
            c.isIgnored("debug.log", false) shouldBe true

            File(root, ".gitignore").writeText("") // remove pattern
            c.isIgnored("debug.log", false) shouldBe true // still cached

            c.invalidate()
            c.isIgnored("debug.log", false) shouldBe false // re-reads from disk
        }
    }
}
