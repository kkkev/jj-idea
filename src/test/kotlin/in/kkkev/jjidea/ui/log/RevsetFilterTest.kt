package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revset
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit coverage for jj-idea-vqpn (GitHub #116)'s custom revset log filter: the pure query-building
 * ([revsetFilterQuery]) and resolve ([resolveRevsetFilter]) logic behind
 * [JujutsuRevsetFilterComponent]. See [RevsetFilter.kt][revsetFilterQuery]'s own doc for the
 * design — resolved client-side, ANDed with the repo-level revset, never touching
 * [in.kkkev.jjidea.jj.LogCache] or what the data loader fetches.
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
     * [LogService.getChangeIds] via mockk's `every {}` matchers — [Revset] is a sealed interface
     * backed by an inline value class ([Expression]), which mockk's matcher machinery can't
     * handle (`IllegalStateException: null packRef`); the same workaround already used by
     * `ClosestBookmarksTest`/`PagedLogLoaderConcurrencyTest`'s `FakeLogService`s.
     */
    private class FakeLogService(private val result: Result<List<ChangeId>>) : LogService by mockk(relaxed = true) {
        val revsets = mutableListOf<Revset>()

        override fun getChangeIds(revset: Revset): Result<List<ChangeId>> {
            revsets.add(revset)
            return result
        }
    }

    @Nested
    inner class `resolveRevsetFilter` {
        private fun repoWithService(service: LogService): JujutsuRepository {
            val repo = mockk<JujutsuRepository>()
            every { repo.logService } returns service
            return repo
        }

        @Test
        fun `issues exactly one getChangeIds call per repo, passing the ANDed revset`() {
            val base = Expression("::@")
            val service = FakeLogService(Result.success(listOf(ChangeId("abc", "abc", null))))
            val repo = repoWithService(service)

            resolveRevsetFilter(listOf(repo), "foo()") { base }

            service.revsets shouldBe listOf(Expression("(::@) & (foo())"))
        }

        @Test
        fun `keys are repo-scoped - the same change id in two repos does not cross over`() {
            val id = ChangeId("abc", "abc", null)
            val serviceA = FakeLogService(Result.success(listOf(id)))
            val serviceB = FakeLogService(Result.success(listOf(id)))
            val repoA = repoWithService(serviceA)
            val repoB = repoWithService(serviceB)

            val result = resolveRevsetFilter(listOf(repoA, repoB), "foo()") { Expression("all()") }

            result.keys shouldBe setOf(ChangeKey(repoA, id), ChangeKey(repoB, id))
        }

        @Test
        fun `a failing repo is reported in errors with jj's message, other repos still resolve`() {
            val id = ChangeId("abc", "abc", null)
            val ok = repoWithService(FakeLogService(Result.success(listOf(id))))
            val failing =
                repoWithService(FakeLogService(Result.failure(RuntimeException("Error from jj log: bad revset"))))

            val result = resolveRevsetFilter(listOf(ok, failing), "foo()") { Expression("all()") }

            result.keys shouldBe setOf(ChangeKey(ok, id))
            result.errors shouldContainExactly mapOf(failing to "Error from jj log: bad revset")
        }
    }
}
