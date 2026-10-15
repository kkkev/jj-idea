package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revset
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit coverage for jj-idea-vqpn (GitHub #116)'s custom revset log filter: the pure query-building
 * ([revsetFilterQuery]) and validate ([validateRevsetFilter]) logic behind
 * [JujutsuRevsetFilterComponent]. See [RevsetFilter.kt][revsetFilterQuery]'s own doc for the
 * design — ANDed with the repo-level revset, never touching [in.kkkev.jjidea.jj.LogCache].
 */
class RevsetFilterTest {
    @Nested
    inner class `revsetFilterQuery` {
        @Test
        fun `ANDs the filter with an explicit repo-level base revset`() {
            revsetFilterQuery(Expression("::@"), "ancestors(@, 5)") shouldBe
                Expression("(::@) & (ancestors(@, 5))")
        }

        @Test
        fun `uses the filter alone when the repo-level revset is Default`() {
            revsetFilterQuery(Revset.Default, "ancestors(@, 5)") shouldBe Expression("(ancestors(@, 5))")
        }
    }

    /**
     * A real implementation (delegating everything else to a relaxed mockk) rather than stubbing
     * [LogService.getLog] via mockk's `every {}` matchers — [Revset] is a sealed interface
     * backed by an inline value class ([Expression]), which mockk's matcher machinery can't
     * handle (`IllegalStateException: null packRef`); the same workaround already used by
     * `ClosestBookmarksTest`/`PagedLogLoaderConcurrencyTest`'s `FakeLogService`s.
     */
    private class FakeLogService(private val result: Result<List<LogEntry>>) : LogService by mockk(relaxed = true) {
        val calls = mutableListOf<Pair<Revset, Int?>>()

        override fun getLog(
            revset: Revset,
            filePaths: List<FilePath>,
            limit: Int?,
            quiet: Boolean
        ): Result<List<LogEntry>> {
            calls.add(revset to limit)
            return result
        }
    }

    @Nested
    inner class `validateRevsetFilter` {
        private fun repoWithService(service: LogService): JujutsuRepository {
            val repo = mockk<JujutsuRepository>()
            every { repo.logService } returns service
            return repo
        }

        @Test
        fun `issues exactly one single-row getLog call per repo, passing the ANDed revset`() {
            val service = FakeLogService(Result.success(emptyList()))
            val repo = repoWithService(service)

            validateRevsetFilter(listOf(repo), "foo()") { Expression("::@") }.shouldBeEmpty()

            service.calls shouldBe listOf(Expression("(::@) & (foo())") to 1)
        }

        @Test
        fun `a failing repo is reported with jj's message, other repos still validate`() {
            val ok = FakeLogService(Result.success(emptyList()))
            val okRepo = repoWithService(ok)
            val failing =
                repoWithService(FakeLogService(Result.failure(RuntimeException("Error from jj log: bad revset"))))

            val errors = validateRevsetFilter(listOf(okRepo, failing), "foo()") { Expression("all()") }

            errors shouldContainExactly mapOf(failing to "Error from jj log: bad revset")
            ok.calls.size shouldBe 1
        }
    }
}
