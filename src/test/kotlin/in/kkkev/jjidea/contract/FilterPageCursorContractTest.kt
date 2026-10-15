package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.ui.log.FilterPageCursor
import `in`.kkkev.jjidea.ui.log.fetchNextPage
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Real-jj proof of jj-idea-ehwm.3's paged filter results: a sparse filter (matches whose parents are
 * not matches) walked page by page returns exactly the matches jj reports in one query - no
 * duplicates, no skipped rows, in jj's own order. This is the case [in.kkkev.jjidea.ui.log.PagedLogWindow]
 * gets wrong (it re-fetches the first matches forever), which is why [FilterPageCursor] exists.
 */
@Tag("contract")
@RequiresJj
class FilterPageCursorContractTest {
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

    private fun walk(revset: String, pageSize: Int): Pair<List<String>, Int> {
        val cursor = FilterPageCursor(Expression(revset), pageSize)
        var pages = 0
        while (!cursor.isExhausted) {
            cursor.fetchNextPage(logService).getOrThrow()
            pages++
            check(pages < 100) { "paging did not terminate" }
        }
        return cursor.entries.map { it.commitId.full } to pages
    }

    private fun allAtOnce(revset: String) =
        logService.getLog(revset = Expression(revset)).getOrThrow().map { it.commitId.full }

    @Test
    fun `sparse matches on a linear history page to exhaustion in jj's order with nothing repeated`() {
        setUp()
        repeat(30) { jj.newChange(if (it % 4 == 0) "match $it" else "other $it") }
        val filter = "description(substring:\"match\")"

        val (walked, pages) = walk(filter, pageSize = 3)

        walked shouldBe allAtOnce(filter)
        walked.distinct().size shouldBe walked.size
        (pages > 1) shouldBe true
    }

    @Test
    fun `sparse matches across many branches page to exhaustion with nothing repeated or skipped`() {
        setUp()
        repeat(4) { jj.newChange("trunk $it") }
        val trunkTip = jj.run("log", "-r", "@", "--no-graph", "-T", "change_id.short(12)").stdout.trim()
        repeat(6) { n ->
            jj.run("new", trunkTip)
            repeat(5) { jj.newChange(if (it % 2 == 0) "match b$n $it" else "other b$n $it") }
            jj.bookmarkCreate("branch$n")
        }
        val filter = "description(substring:\"match\")"

        val (walked, _) = walk(filter, pageSize = 4)

        walked.sorted() shouldBe allAtOnce(filter).sorted()
        walked.distinct().size shouldBe walked.size
    }

    @Test
    fun `a filter matching nothing is exhausted by its seed query`() {
        setUp()
        repeat(5) { jj.newChange("commit $it") }

        val (walked, pages) = walk("description(substring:\"no such text\")", pageSize = 3)

        walked shouldBe emptyList()
        pages shouldBe 1 // the call that seeds, finds no heads, and so needs no page query
    }
}
