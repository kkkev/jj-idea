package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [withDerivedDivergence] — the single shared derivation behind both the bookmarks
 * panel and the log table's bookmark chips (jj-idea-ks5k, GitHub #110), replacing the panel-only
 * [withDivergenceFrom] call that used to live in `BookmarkTreeModel.buildRepoNodes` and the log
 * table's total lack of any derivation at all.
 */
class BookmarkDivergenceTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun item(
        name: String,
        tracked: Boolean = true,
        conflict: Boolean = false,
        aheadCount: Int = 0,
        behindCount: Int = 0,
        deleted: Boolean = false,
        targets: List<ChangeId> = if (deleted) emptyList() else listOf(ChangeId(name, name, null))
    ) = BookmarkItem(
        Bookmark(
            name,
            tracked = tracked,
            deleted = deleted,
            conflict = conflict,
            aheadCount = aheadCount,
            behindCount = behindCount
        ),
        targets
    )

    private fun bookmarkOf(items: List<BookmarkItem>, name: String) = items.single {
        it.bookmark.name.name == name
    }.bookmark

    /** No conflicted local bookmarks anywhere in [items] - every test that shouldn't issue a
     * `jj log` call at all uses this, asserting zero calls via [FailingLogService]. */
    private object FailingLogService : LogService by mockk(relaxed = true) {
        override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?) =
            throw AssertionError("no jj log call expected for a non-conflicted bookmark: $revset")
    }

    /** Answers [getLogBasic] calls in order - see `DanglingHeadsTest`'s identical helper. */
    private inner class FakeLogService(private val sizes: List<Int>) : LogService by mockk(relaxed = true) {
        var callCount = 0
            private set

        override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?): Result<List<LogEntry>> {
            val size = sizes[callCount]
            callCount++
            return Result.success((0 until size).map { entry("x$it") })
        }
    }

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId(id.padEnd(40, '0')),
        underlyingDescription = "desc"
    )

    @Test
    fun `non-conflicted local bookmark derives from its remote's counts, swapped`() {
        val items = listOf(item("main"), item("main@origin", behindCount = 1))

        val result = FailingLogService.withDerivedDivergence(items)

        bookmarkOf(result, "main").aheadCount shouldBe 1
        bookmarkOf(result, "main").behindCount shouldBe 0
    }

    @Test
    fun `divergence ignores the git pseudo-remote and untracked remotes`() {
        val items = listOf(
            item("main"),
            item("main@git", aheadCount = 5, behindCount = 5),
            item("main@origin", tracked = false, aheadCount = 9, behindCount = 9)
        )

        val result = FailingLogService.withDerivedDivergence(items)

        bookmarkOf(result, "main").aheadCount shouldBe 0
        bookmarkOf(result, "main").behindCount shouldBe 0
    }

    @Test
    fun `divergence takes the max across multiple tracked remotes, not the sum`() {
        val items = listOf(item("main"), item("main@origin", behindCount = 2), item("main@github", behindCount = 5))

        val result = FailingLogService.withDerivedDivergence(items)

        bookmarkOf(result, "main").aheadCount shouldBe 5
    }

    @Test
    fun `a remote row whose local is pending-deletion has its counts zeroed and is excluded from derivation`() {
        val items = listOf(item("foo", deleted = true), item("foo@origin", aheadCount = 1000))

        val result = FailingLogService.withDerivedDivergence(items)

        bookmarkOf(result, "foo@origin").aheadCount shouldBe 0
        bookmarkOf(result, "foo").aheadCount shouldBe 0
        bookmarkOf(result, "foo").behindCount shouldBe 0
    }

    @Test
    fun `a remote row absent on the remote has its counts zeroed (jj-idea-j58e)`() {
        // present=false -> BookmarkItem(bookmark.copy(deleted=true), emptyList()) per
        // bookmarkListTemplate.take() - the mirror image of the deleted-local case above.
        val items = listOf(item("foo"), item("foo@origin", tracked = true, aheadCount = 4000, deleted = true))

        val result = FailingLogService.withDerivedDivergence(items)

        bookmarkOf(result, "foo@origin").aheadCount shouldBe 0
        bookmarkOf(result, "foo@origin").behindCount shouldBe 0
        // No arrow on the local either - it must not inherit the absent remote's garbage count.
        bookmarkOf(result, "foo").aheadCount shouldBe 0
        bookmarkOf(result, "foo").behindCount shouldBe 0
    }

    @Test
    fun `a conflicted local bookmark gets exact bidirectional counts, not jj's one-directional hint`() {
        // Reproduces the GitHub #110 screenshot's shape: two sibling targets, each 2 changes past
        // their common ancestor. jj's own tracking_ahead_count/tracking_behind_count for the
        // matching remote row (confirmed empirically against jj 0.44.0) report only one direction
        // (ahead=0/behind=2 here) - exactDivergence recomputes both (ahead=2/behind=2).
        val localTarget = ChangeId("local2", "local2", null)
        val remoteTarget = ChangeId("remote2", "remote2", null)
        val local = BookmarkItem(
            Bookmark("foo", conflict = true),
            targets = listOf(localTarget, remoteTarget)
        )
        val remote = item("foo@origin", aheadCount = 0, behindCount = 2, targets = listOf(remoteTarget))
        val service = FakeLogService(sizes = listOf(2, 2)) // ahead query, then behind query

        val result = service.withDerivedDivergence(listOf(local, remote))

        bookmarkOf(result, "foo").aheadCount shouldBe 2
        bookmarkOf(result, "foo").behindCount shouldBe 2
        // The remote row is corrected to agree with the local, swapped.
        bookmarkOf(result, "foo@origin").aheadCount shouldBe 2
        bookmarkOf(result, "foo@origin").behindCount shouldBe 2
        service.callCount shouldBe 2
    }

    @Test
    fun `a conflicted bookmark with no tracked remote issues no jj log call`() {
        val local = BookmarkItem(Bookmark("foo", conflict = true), targets = listOf(ChangeId("a", "a", null)))

        val result = FailingLogService.withDerivedDivergence(listOf(local))

        bookmarkOf(result, "foo").aheadCount shouldBe 0
        bookmarkOf(result, "foo").behindCount shouldBe 0
    }

    @Test
    fun `a non-conflicted bookmark's own local aheadCount-behindCount is irrelevant - only the remote counts matter`() {
        val items = listOf(item("main", aheadCount = 99, behindCount = 99), item("main@origin", behindCount = 1))

        val result = FailingLogService.withDerivedDivergence(items)

        bookmarkOf(result, "main").aheadCount shouldBe 1
        bookmarkOf(result, "main").behindCount shouldBe 0
    }

    @Test
    fun `passes through unrelated bookmarks unchanged`() {
        val local = item("bar")
        val items = listOf(local)

        val result = FailingLogService.withDerivedDivergence(items)

        result shouldContainExactlyInAnyOrder items
    }
}
