package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/** The state machine behind jj-idea-ehwm.3's paged filter results; the jj queries themselves are in the loader. */
class FilterPageCursorTest {
    private val repo = mockk<JujutsuRepository>()

    private fun id(n: Int) = ChangeId("c$n", "c$n")

    private fun row(n: Int) = LogEntry(
        repo = repo,
        id = id(n),
        commitId = CommitId("f$n"),
        underlyingDescription = "",
        parentIds = emptyList()
    )

    private fun cursor() = FilterPageCursor(Expression("(all()) & (mine())"), pageSize = 2)

    @Test
    fun `is not exhausted before it is seeded`() {
        cursor().isExhausted shouldBe false
    }

    @Test
    fun `seeding with no heads means there is nothing to fetch`() {
        val cursor = cursor().apply { seed(emptyList()) }

        cursor.isExhausted shouldBe true
        cursor.entries shouldBe emptyList()
    }

    @Test
    fun `the page is the first rows of the matches at or below the frontier`() {
        val cursor = cursor().apply { seed(listOf(id(1), id(2))) }

        cursor.pageRevset() shouldBe Expression("((all()) & (mine())) & ::(present(c1) | present(c2))")
    }

    @Test
    fun `the remainder removes the page by commit id and its heads become the frontier`() {
        val cursor = cursor().apply { seed(listOf(id(1))) }
        val page = listOf(row(1), row(2))

        cursor.remainderRevset(page) shouldBe
            Expression("(((all()) & (mine())) & ::(present(c1))) ~ (present(f1) | present(f2))")
        cursor.recordPage(page, listOf(id(3)))

        cursor.isExhausted shouldBe false
        cursor.pageCount shouldBe 1
        cursor.pageRevset() shouldBe Expression("((all()) & (mine())) & ::(present(c3))")
        cursor.entries shouldBe page
    }

    @Test
    fun `no remaining heads exhausts the cursor, and entries accumulate across pages`() {
        val cursor = cursor().apply { seed(listOf(id(1))) }
        cursor.recordPage(listOf(row(1), row(2)), listOf(id(3)))

        cursor.recordLastPage(listOf(row(3)))

        cursor.isExhausted shouldBe true
        cursor.entries.map { it.id } shouldBe listOf(id(1), id(2), id(3))
        cursor.lastPage shouldBe listOf(row(3))
    }

    @Test
    fun `asking for a page before seeding, or after the end, is a programming error`() {
        shouldThrow<IllegalStateException> { cursor().pageRevset() }
        shouldThrow<IllegalStateException> { cursor().apply { seed(emptyList()) }.pageRevset() }
    }

    @Test
    fun `reports a frontier wider than the cap`() {
        val cursor = cursor().apply { seed((1..5).map(::id)) }

        cursor.frontierTooWide(4) shouldBe true
        cursor.frontierTooWide(5) shouldBe false
    }

    @Test
    fun `the head queries behind a filter run quietly`() {
        val logService = mockk<LogService>()
        val revset = Expression("(all()) & (mine())")
        every { logService.getLogHeads(revset, any()) } returns Result.success(emptyList())

        cursor().fetchNextPage(logService)

        verify { logService.getLogHeads(revset, true) }
        verify(exactly = 0) { logService.getLogHeads(revset, false) }
    }
}
