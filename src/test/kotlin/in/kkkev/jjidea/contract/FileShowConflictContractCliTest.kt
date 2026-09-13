package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractor
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins the assumption GitHub #119 / jj-idea-ct7e's fix rests on:
 * [in.kkkev.jjidea.vcs.diff.JujutsuConflictDiffRequestProvider] reads a conflicted commit's
 * content via `jj file show -r <rev>` on that commit's own id, not `@`. This verifies against a
 * real `jj` that doing so for a **non-working-copy** conflicted commit actually returns markers
 * [JjMarkerConflictExtractor] can parse into three non-empty sides - [JjStub] has no real conflict
 * materialisation to fake this against, so it's CLI-only (see [ScopedIdentityContractCliTest] for
 * the same pattern).
 */
@Tag("contract")
@RequiresJj
class FileShowConflictContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
    }

    @Test
    fun `file show on a non-working-copy conflicted commit returns extractable markers`() {
        // Mirrors scripts/fixtures/fx-conflict.sh's topology: change-a rebased onto change-b
        // conflicts; @ ends up on change-b (the sibling.sh) - a clean commit unrelated to the
        // conflict, the exact position GitHub #119 was filed from.
        jj.createFile("file.txt", "line 1\nshared line\nline 3\n")
        jj.describe("initial")
        jj.newChange("change A")
        jj.createFile("file.txt", "line 1\nchanged by A\nline 3\n")
        jj.bookmarkCreate("change-a")
        val result1 = jj.run("new", "-r", "change-a-", "-m", "change B")
        check(result1.isSuccess) { "jj new failed: ${result1.stderr}" }
        jj.createFile("file.txt", "line 1\nchanged by B\nline 3\n")
        jj.bookmarkCreate("change-b")
        val rebase = jj.run("rebase", "-r", "change-a", "-d", "change-b")
        check(rebase.isSuccess) { "jj rebase failed: ${rebase.stderr}" }
        // Leave @ on change-b: a clean sibling of the now-conflicted change-a, never `jj edit`ing
        // onto the conflict.

        val show = jj.run("file", "show", "-r", "change-a", "file.txt")

        show.isSuccess shouldBe true
        val conflict = JjMarkerConflictExtractor().extract(show.stdout.toByteArray(Charsets.UTF_8))
        conflict.shouldNotBeNull()
        String(conflict.mergeData.CURRENT, Charsets.UTF_8).shouldNotBeBlank()
        String(conflict.mergeData.ORIGINAL, Charsets.UTF_8).shouldNotBeBlank()
        String(conflict.mergeData.LAST, Charsets.UTF_8).shouldNotBeBlank()
    }
}
