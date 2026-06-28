package `in`.kkkev.jjidea.diffedit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Tests for [mirrorTree]: the core sync function invoked by jj's diff-editor protocol.
 *
 * jj calls this with:
 *   stagingDir = pre-computed first-commit content (one file per included/partial file)
 *   leftDir    = parent/base tree (all files before the revision's changes)
 *   rightDir   = revision tree (all files after the revision's changes); jj reads this back
 *
 * Expected outcomes per file:
 *   - In staging              → copy staging content to right (goes to first commit)
 *   - In left, NOT in staging → restore from left to right (deferred to second commit)
 *   - In right, NOT in left, NOT in staging → deleted from right (added-then-excluded)
 */
class HunkApplyMainTest {
    @TempDir
    lateinit var tempDir: Path

    private fun staging() = tempDir.resolve("staging").also { Files.createDirectories(it) }
    private fun left() = tempDir.resolve("left").also { Files.createDirectories(it) }
    private fun right() = tempDir.resolve("right").also { Files.createDirectories(it) }

    @Test
    fun `mirrorTree copies staging files to right`() {
        val staging = staging()
        val right = right()

        staging.resolve("file.txt").writeText("hello")

        mirrorTree(staging, left(), right)

        right.resolve("file.txt").readText() shouldBe "hello"
    }

    @Test
    fun `mirrorTree overwrites existing file in right`() {
        val staging = staging()
        val right = right()

        staging.resolve("file.txt").writeText("new content")
        right.resolve("file.txt").writeText("old content")

        mirrorTree(staging, left(), right)

        right.resolve("file.txt").readText() shouldBe "new content"
    }

    @Test
    fun `mirrorTree restores excluded modified file from left`() {
        // fileA is included (in staging), fileB is excluded but present in left (modified in revision).
        // Expected: fileB should be restored to its left content, NOT deleted.
        val staging = staging()
        val left = left()
        val right = right()

        staging.resolve("fileA.txt").writeText("A-first-commit")
        left.resolve("fileA.txt").writeText("A-base")
        left.resolve("fileB.txt").writeText("B-base")
        right.resolve("fileA.txt").writeText("A-revision")
        right.resolve("fileB.txt").writeText("B-revision — should be restored to left")

        mirrorTree(staging, left, right)

        right.resolve("fileA.txt").readText() shouldBe "A-first-commit"
        right.resolve("fileB.txt").readText() shouldBe "B-base"
    }

    @Test
    fun `mirrorTree restores deleted-in-revision file from left`() {
        // fileA was deleted in the revision (absent from right) but excluded from first commit.
        // Expected: restored from left so the deletion goes to the second commit.
        val staging = staging()
        val left = left()
        val right = right()

        left.resolve("fileA.txt").writeText("original content")
        // right has no fileA (it was deleted in the revision)

        mirrorTree(staging, left, right)

        right.resolve("fileA.txt").readText() shouldBe "original content"
    }

    @Test
    fun `mirrorTree deletes added file absent from staging and left`() {
        // fileA was added in the revision (not in left) and excluded from the first commit.
        // Expected: deleted from right so the addition goes to the second commit.
        val staging = staging()
        val right = right()

        right.resolve("fileA.txt").writeText("new file — should be excluded")

        mirrorTree(staging, left(), right)

        Files.exists(right.resolve("fileA.txt")) shouldBe false
    }

    @Test
    fun `mirrorTree staging takes priority over left`() {
        // fileA is in both staging and left; staging content wins.
        val staging = staging()
        val left = left()
        val right = right()

        staging.resolve("fileA.txt").writeText("staged content")
        left.resolve("fileA.txt").writeText("base content")
        right.resolve("fileA.txt").writeText("revision content")

        mirrorTree(staging, left, right)

        right.resolve("fileA.txt").readText() shouldBe "staged content"
    }

    @Test
    fun `mirrorTree creates nested directories`() {
        val staging = staging()
        val right = right()

        Files.createDirectories(staging.resolve("src/main/kotlin"))
        staging.resolve("src/main/kotlin/Foo.kt").writeText("class Foo")

        mirrorTree(staging, left(), right)

        right.resolve("src/main/kotlin/Foo.kt").readText() shouldBe "class Foo"
    }

    @Test
    fun `mirrorTree with empty staging and left deletes all right files`() {
        // Nothing in staging or left — all right files were added and excluded.
        val right = right()

        right.resolve("file.txt").writeText("added-and-excluded")

        mirrorTree(staging(), left(), right)

        Files.exists(right.resolve("file.txt")) shouldBe false
    }

    @Test
    fun `mirrorTree with empty staging restores all left files`() {
        // Nothing staged, but left has files — all revision changes are excluded; left is restored.
        val left = left()
        val right = right()

        left.resolve("fileA.txt").writeText("base-A")
        left.resolve("fileB.txt").writeText("base-B")
        right.resolve("fileA.txt").writeText("revision-A")
        right.resolve("fileB.txt").writeText("revision-B")

        mirrorTree(staging(), left, right)

        right.resolve("fileA.txt").readText() shouldBe "base-A"
        right.resolve("fileB.txt").readText() shouldBe "base-B"
    }

    @Test
    fun `mirrorTree preserves file content byte-for-byte`() {
        val staging = staging()
        val right = right()

        val content = "line1\nline2\nno trailing newline"
        staging.resolve("file.txt").writeText(content)

        mirrorTree(staging, left(), right)

        right.resolve("file.txt").readText() shouldBe content
    }
}
