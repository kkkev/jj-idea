package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [String.toFileset] and [String.escapeJjString] — the path-escaping helpers that
 * prevent jj from parsing repo-relative file paths as fileset/template expressions. See
 * GitHub #73 (paths with `()[]` broke `jj file show` and friends).
 */
class JjFilesetTest {
    @Test
    fun `toFileset - plain path`() {
        "foo.txt".toFileset() shouldBe "cwd:\"foo.txt\""
    }

    @Test
    fun `toFileset - nested path`() {
        "src/main/foo.kt".toFileset() shouldBe "cwd:\"src/main/foo.kt\""
    }

    @Test
    fun `toFileset - path with parens and brackets`() {
        "frontend/src/app/(app)/users/[id]/settings.tsx".toFileset() shouldBe
            "cwd:\"frontend/src/app/(app)/users/[id]/settings.tsx\""
    }

    @Test
    fun `toFileset - path with other fileset meta-characters`() {
        "a~b|c&d.txt".toFileset() shouldBe "cwd:\"a~b|c&d.txt\""
    }

    @Test
    fun `toFileset - path with embedded double quote is escaped`() {
        "weird\"file.txt".toFileset() shouldBe "cwd:\"weird\\\"file.txt\""
    }

    @Test
    fun `toFileset - path with embedded backslash is escaped`() {
        "weird\\file.txt".toFileset() shouldBe "cwd:\"weird\\\\file.txt\""
    }

    @Test
    fun `toFileset - path with space`() {
        "weird dir/file.txt".toFileset() shouldBe "cwd:\"weird dir/file.txt\""
    }

    @Test
    fun `escapeJjString - no special characters`() {
        "plain".escapeJjString() shouldBe "plain"
    }

    @Test
    fun `escapeJjString - backslash escaped before quote`() {
        // Order matters: backslashes must be escaped first, or a path ending in `\"` would
        // double-escape incorrectly.
        "a\\\"b".escapeJjString() shouldBe "a\\\\\\\"b"
    }

    @Test
    fun `escapeJjString - apostrophe passes through unescaped`() {
        "it's a file.txt".escapeJjString() shouldBe "it's a file.txt"
    }
}
