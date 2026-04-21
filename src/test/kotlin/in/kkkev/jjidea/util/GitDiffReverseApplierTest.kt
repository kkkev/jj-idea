package `in`.kkkev.jjidea.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GitDiffReverseApplierTest {
    @Test
    fun `no hunks returns afterContent unchanged`() {
        val after = "line1\nline2\nline3\n"
        val diff =
            """
            diff --git a/file.txt b/file.txt
            index abc..def 100644
            --- a/file.txt
            +++ b/file.txt
            """.trimIndent() + "\n"
        GitDiffReverseApplier.reverseApply(after, diff) shouldBe after
    }

    @Test
    fun `binary file returns null`() {
        val diff =
            """
            diff --git a/file.bin b/file.bin
            Binary files a/file.bin and b/file.bin differ
            """.trimIndent() + "\n"
        GitDiffReverseApplier.reverseApply("", diff) shouldBe null
    }

    @Test
    fun `added file returns empty string`() {
        val diff =
            """
            diff --git a/new.txt b/new.txt
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +line1
            +line2
            """.trimIndent() + "\n"
        GitDiffReverseApplier.reverseApply("line1\nline2\n", diff) shouldBe ""
    }

    @Test
    fun `single line removed restores it`() {
        val after = "line1\nline3\n"
        val diff =
            """
            diff --git a/file.txt b/file.txt
            --- a/file.txt
            +++ b/file.txt
            @@ -1,3 +1,2 @@
             line1
            -removed
             line3
            """.trimIndent() + "\n"
        GitDiffReverseApplier.reverseApply(after, diff) shouldBe "line1\nremoved\nline3\n"
    }

    @Test
    fun `single line added is stripped from result`() {
        val after = "line1\nadded\nline2\n"
        val diff =
            """
            diff --git a/file.txt b/file.txt
            --- a/file.txt
            +++ b/file.txt
            @@ -1,2 +1,3 @@
             line1
            +added
             line2
            """.trimIndent() + "\n"
        GitDiffReverseApplier.reverseApply(after, diff) shouldBe "line1\nline2\n"
    }

    @Test
    fun `line replaced restores original`() {
        val after = "intro\nafter-line\noutro\n"
        val diff =
            """
            diff --git a/file.txt b/file.txt
            --- a/file.txt
            +++ b/file.txt
            @@ -1,3 +1,3 @@
             intro
            -before-line
            +after-line
             outro
            """.trimIndent() + "\n"
        GitDiffReverseApplier.reverseApply(after, diff) shouldBe "intro\nbefore-line\noutro\n"
    }

    @Test
    fun `multiple hunks are all reverse-applied`() {
        val after = "AFTER1\nbbb\nccc\nddd\neee\nfff\nggg\nAFTER2\n"
        val diff =
            """
            diff --git a/file.txt b/file.txt
            --- a/file.txt
            +++ b/file.txt
            @@ -1,4 +1,4 @@
            -BEFORE1
            +AFTER1
             bbb
             ccc
             ddd
            @@ -5,4 +5,4 @@
             eee
             fff
             ggg
            -BEFORE2
            +AFTER2
            """.trimIndent() + "\n"
        GitDiffReverseApplier.reverseApply(after, diff) shouldBe "BEFORE1\nbbb\nccc\nddd\neee\nfff\nggg\nBEFORE2\n"
    }

    @Test
    fun `lines outside hunks are preserved unchanged`() {
        val after = "unchanged1\nchanged\nunchanged2\n"
        val diff =
            """
            diff --git a/file.txt b/file.txt
            --- a/file.txt
            +++ b/file.txt
            @@ -2,1 +2,1 @@
            -original
            +changed
            """.trimIndent() + "\n"
        GitDiffReverseApplier.reverseApply(after, diff) shouldBe "unchanged1\noriginal\nunchanged2\n"
    }

    @Test
    fun `no newline marker after plus line means before retains trailing newline`() {
        // Marker follows '+line2' so only after lacks trailing newline; before has it.
        val after = "line1\nline2"
        val diff = "diff --git a/file.txt b/file.txt\n" +
            "--- a/file.txt\n+++ b/file.txt\n" +
            "@@ -1,2 +1,2 @@\n line1\n-old\n+line2\n\\ No newline at end of file\n"
        GitDiffReverseApplier.reverseApply(after, diff) shouldBe "line1\nold\n"
    }

    @Test
    fun `no newline marker after minus line means before lacks trailing newline`() {
        // Marker follows '-old' so before lacks trailing newline.
        val after = "line1\nline2\n"
        val diff = "diff --git a/file.txt b/file.txt\n" +
            "--- a/file.txt\n+++ b/file.txt\n" +
            "@@ -1,2 +1,2 @@\n line1\n-old\n\\ No newline at end of file\n+line2\n"
        GitDiffReverseApplier.reverseApply(after, diff) shouldBe "line1\nold"
    }

    @Test
    fun `entirely deleted file reconstructed from diff`() {
        val after = ""
        val diff =
            """
            diff --git a/file.txt b/file.txt
            --- a/file.txt
            +++ /dev/null
            @@ -1,3 +0,0 @@
            -line1
            -line2
            -line3
            """.trimIndent() + "\n"
        GitDiffReverseApplier.reverseApply(after, diff) shouldBe "line1\nline2\nline3\n"
    }

    @Test
    fun `empty diff on empty content returns empty`() {
        GitDiffReverseApplier.reverseApply("", "") shouldBe ""
    }
}
