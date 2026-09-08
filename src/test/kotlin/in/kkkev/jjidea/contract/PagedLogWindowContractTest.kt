package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.ui.log.PagedLogWindow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Real-jj regression test for jj-idea-2c8k's frontier-cursor mechanism (GitHub #69) — permanent
 * form of the ad-hoc scratch validation recorded in
 * docs/design/jj-idea-2c8k-paged-log-loading.md. Small fixtures here (a few dozen commits) are
 * enough to exercise the same code path the design doc validated at scale (up to 50,000 commits
 * / 8,000 heads) — this test's job is to catch a regression in the mechanism itself, not to
 * re-prove the scale numbers, which aren't practical to reproduce in every CI run.
 */
@Tag("contract")
@RequiresJj
class PagedLogWindowContractTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli
    private lateinit var logService: CliLogService

    private fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
        val root = mockk<VirtualFile> { every { path } returns tempDir.toString() }
        val repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns CliExecutor(root)
            every { directory } returns root
        }
        logService = CliLogService(repo)
    }

    private fun trueTotalCommitCount(): Int =
        logService.getLog(revset = Expression.ALL).getOrThrow().size

    /** Pages [window] to exhaustion via the real [logService], returning the total pages walked. */
    private fun pageToExhaustion(window: PagedLogWindow): Int {
        window.seed(logService.getLogHeads(window.baseRevset).getOrThrow())
        var pages = 0
        while (!window.isExhausted) {
            window.recordPage(logService.getLog(revset = window.pageRevset(), limit = window.pageSize).getOrThrow())
            pages++
        }
        return pages
    }

    @Test
    fun `linear trunk pages to exhaustion with no missing or duplicate commits`() {
        setUp()
        repeat(20) { jj.newChange("commit $it") }

        val window = PagedLogWindow(Expression.ALL, pageSize = 5)
        val pages = pageToExhaustion(window)

        pages shouldBe 5 // ceil(21 commits [20 + root's initial @] / 5)
        window.entries.size shouldBe trueTotalCommitCount()
        window.entries.map { it.id.full }.distinct().size shouldBe window.entries.size // no duplicates
    }

    @Test
    fun `many concurrent branches page to exhaustion with no missing or duplicate commits`() {
        setUp()
        repeat(5) { jj.newChange("trunk $it") }
        val trunkTip = jj.run("log", "-r", "@", "--no-graph", "-T", "change_id.short(12)").stdout.trim()
        repeat(10) { n ->
            jj.run("new", trunkTip)
            repeat(3) { jj.newChange("branch$n commit $it") }
            jj.bookmarkCreate("branch$n")
        }

        val window = PagedLogWindow(Expression.ALL, pageSize = 7)
        pageToExhaustion(window)

        window.entries.size shouldBe trueTotalCommitCount()
        window.entries.map { it.id.full }.distinct().size shouldBe window.entries.size
    }

    @Test
    fun `paging composes correctly with a restrictive custom revset`() {
        setUp()
        repeat(5) { jj.newChange("trunk $it") }
        jj.bookmarkCreate("main")
        repeat(5) { jj.newChange("more $it") } // past the bookmark, not matched by bookmarks()

        val window = PagedLogWindow(Expression("bookmarks()"), pageSize = 2)
        pageToExhaustion(window)

        val trueBookmarkedCount = logService.getLog(revset = Expression("bookmarks()")).getOrThrow().size
        window.entries.size shouldBe trueBookmarkedCount
    }
}
