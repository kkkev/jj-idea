package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogCache
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val PAGED_LOG_LOAD_PROPERTY = "jjidea.preview.pagedLogLoad"

/**
 * Regression coverage for jj-idea-5gof: [UnifiedJujutsuLogDataLoader]'s per-repo
 * [java.util.concurrent.locks.ReentrantLock] (`lockFor`), which serializes
 * [UnifiedJujutsuLogDataLoader.refresh] /
 * [UnifiedJujutsuLogDataLoader.forceRefresh] / [UnifiedJujutsuLogDataLoader.loadMore] against the
 * same repo's [PagedLogWindow] — fixing a real `ConcurrentModificationException` found via manual
 * `runIde` testing (rapid scroll events each spawning an independent background task racing on the
 * same window's backing list). See docs/design/jj-idea-2c8k-paged-log-loading.md, bug 9.
 *
 * Platform-tagged (not a plain unit test): constructing the loader needs a live `Application` for
 * [in.kkkev.jjidea.preview.PreviewEntitlement] (a field initializer) and for the real
 * `executeOnPooledThread`/`invokeLater` dispatch these methods use — the race lives in genuinely
 * concurrent pooled-thread work, so faking that dispatch would test nothing. [JujutsuSettings] is
 * used for real (its `logRevset` state defaults to `"all()"`, already non-blank, so paging engages
 * with no extra setup) rather than mocked, matching `JujutsuSettingsPlatformTest`'s convention of
 * mutating the real per-project instance. Everything else (repos, [LogService], [LogCache]) is
 * mockk, following `RepoLogCacheTest`'s pattern.
 *
 * An uncaught exception on a pooled thread (e.g. the very
 * `ConcurrentModificationException` this lock exists to prevent) is logged via
 * `Logger.error` in `in.kkkev.jjidea.util.Tasks.runInBackground`, which the platform test
 * framework's `TestLoggerFactory` fails the test on by default (see
 * `TasksBackgroundErrorTest` for the same mechanism demonstrated directly) — so no manual
 * exception plumbing is needed here for that to surface as a failure.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class PagedLogLoaderConcurrencyTest {
    private val projectFx = projectFixture()

    @BeforeEach
    fun enablePaging() {
        System.setProperty(PAGED_LOG_LOAD_PROPERTY, "true")
        // Small enough that a fake chain repo genuinely pages (the default 500 would swallow every
        // chain below in one page, exhausting the window before loadMore() ever got a second call).
        JujutsuSettings.getInstance(projectFx.get()).state.logChangeLimit = 10
    }

    @AfterEach
    fun cleanup() {
        System.clearProperty(PAGED_LOG_LOAD_PROPERTY)
        JujutsuSettings.getInstance(projectFx.get()).state.logChangeLimit = 500
        // The last drain before projectFixture disposes the project - generous on purpose (see
        // jj-idea-5gof): this class's fake repos do real concurrent pooled-thread work (unlike
        // most drainBackgroundLoads() callers, which only wait out JujutsuStateModel's init
        // loaders), so it needs more headroom to avoid racing LeakHunter under load.
        drainBackgroundLoads(2_000)
    }

    // ─── fake repo scaffolding ───────────────────────────────────────────────

    /** Counts fetches in flight against one fake repo's [LogService] — a direct proxy for "is the lock held". */
    private class FetchTracker {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val completed = AtomicInteger(0)
    }

    private class FakeRepo(val repo: JujutsuRepository, val logService: FakeLogService, val tracker: FetchTracker)

    /**
     * A real implementation (delegating everything else to a relaxed mockk) rather than stubbing
     * [getLog]/[getLogHeads] via mockk's `every {}` matchers — [Revset] is a sealed interface
     * backed by an inline value class ([in.kkkev.jjidea.jj.Expression]), which mockk's matcher
     * machinery can't handle (`IllegalStateException: null packRef`; the same workaround is
     * already used by `ClosestBookmarksTest`/`MoveBookmarkDirectionTest`).
     *
     * [getLog] answers by finding the frontier ids [PagedLogWindow.pageRevset] embeds as
     * `present(id)` terms in its [Revset] and returning the next chunk of [chain] from there —
     * real enough for [PagedLogWindow]'s actual frontier-walking logic to behave correctly (a
     * single lineage, so the frontier is always exactly one id), without a real jj process.
     * [delayMillis] mimics real CLI latency, long enough that two unlocked concurrent fetches for
     * the same repo really do overlap. [failing], once set, makes every subsequent [getLog] call
     * fail instead (jj-idea-5gof's fetch-failure race test).
     */
    private class FakeLogService(
        private val chain: List<LogEntry>,
        private val tracker: FetchTracker,
        private val delayMillis: Long
    ) : LogService by mockk(relaxed = true) {
        @Volatile
        var failing = false

        /**
         * When set, the next [getLog] call counts this down on entry, then blocks until
         * [releaseLatch] counts down — used to hold a repo's lock open long enough to observe
         * another repo's fetch proceeding independently, or a second call skip it.
         */
        @Volatile
        var enteredLatch: CountDownLatch? = null

        @Volatile
        var releaseLatch: CountDownLatch? = null

        override fun getLogHeads(revset: Revset): Result<List<ChangeId>> = Result.success(listOf(chain.first().id))

        override fun getLog(
            revset: Revset,
            filePaths: List<FilePath>,
            limit: Int?,
            quiet: Boolean
        ): Result<List<LogEntry>> {
            val n = tracker.inFlight.incrementAndGet()
            tracker.maxInFlight.updateAndGet { max -> maxOf(max, n) }
            try {
                enteredLatch?.countDown()
                releaseLatch?.await(5, TimeUnit.SECONDS)
                Thread.sleep(delayMillis)
                if (failing) return Result.failure(RuntimeException("simulated jj failure"))
                val ids = Regex("""present\(([^)]+)\)""").findAll(revset.toString()).map { it.groupValues[1] }.toSet()
                val startIndex = ids.mapNotNull { id ->
                    chain.indexOfFirst { it.id.full == id }.takeIf { it >= 0 }
                }.minOrNull() ?: return Result.success(emptyList())
                return Result.success(chain.drop(startIndex).take(limit ?: chain.size))
            } finally {
                tracker.inFlight.decrementAndGet()
                tracker.completed.incrementAndGet()
            }
        }
    }

    private fun fakeChainRepo(name: String, length: Int, delayMillis: Long = 8): FakeRepo {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.displayName } returns name
        every { repo.directory } returns mockk<VirtualFile>(relaxed = true) { every { path } returns "/fake/$name" }

        val chain = (0 until length).map { i ->
            LogEntry(
                repo = repo,
                id = ChangeId(full = "$name-$i", short = "$name-$i"),
                commitId = CommitId("commit-$name-$i"),
                underlyingDescription = "desc $i",
                parentIds = if (i ==
                    length - 1
                ) {
                    emptyList()
                } else {
                    listOf(ChangeId(full = "$name-${i + 1}", short = "$name-${i + 1}"))
                }
            )
        }

        val tracker = FetchTracker()
        val logService = FakeLogService(chain, tracker, delayMillis)
        every { repo.logService } returns logService
        every { repo.logCache } returns mockk<LogCache>(relaxed = true)
        return FakeRepo(repo, logService, tracker)
    }

    private fun loader(vararg repos: FakeRepo): UnifiedJujutsuLogDataLoader {
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true)
        return UnifiedJujutsuLogDataLoader(projectFx.get(), { repos.map { it.repo } }, panel)
    }

    // ─── tests ───────────────────────────────────────────────────────────────

    @Test
    fun `refresh and loadMore never fetch the same repo's window concurrently`() {
        val fake = fakeChainRepo("a", length = 400, delayMillis = 8)
        val log = loader(fake)
        log.loadCommits()
        drainBackgroundLoads(1_000)
        fake.tracker.completed.set(0) // ignore loadCommits' own initial page-1 fetch above

        // Rapid-fire refresh()/loadMore() calls, exactly like rapid scroll events each spawning an
        // independent runInBackground task — the shape that produced the original CME.
        repeat(40) { i -> if (i % 2 == 0) log.refresh() else log.loadMore() }
        drainBackgroundLoads(5_000)

        fake.tracker.inFlight.get() shouldBe 0 // every fetch finished cleanly
        (fake.tracker.completed.get() > 0) shouldBe true // sanity: fetches actually happened
        fake.tracker.maxInFlight.get() shouldBe 1 // the actual invariant: never two fetches in flight at once
    }

    @Test
    fun `two repos' windows are locked independently, not behind one global lock`() {
        val blocked = fakeChainRepo("blocked", length = 50)
        val free = fakeChainRepo("free", length = 50)
        val log = loader(blocked, free)
        log.loadCommits()
        drainBackgroundLoads(1_000)

        val releaseLatch = CountDownLatch(1)
        val blockedEntered = CountDownLatch(1)
        blocked.logService.enteredLatch = blockedEntered
        blocked.logService.releaseLatch = releaseLatch

        log.loadMore() // occupies blocked's lock for as long as releaseLatch is held
        blockedEntered.await(2, TimeUnit.SECONDS) shouldBe true

        val freeStored = CountDownLatch(1)
        every { free.repo.logCache.store(any()) } answers { freeStored.countDown() }
        log.loadMore() // free's repo has its own lock — must not wait behind blocked's

        freeStored.await(2, TimeUnit.SECONDS) shouldBe true // free's fetch completed while blocked was still held
        releaseLatch.countDown()
        drainBackgroundLoads(1_000)
    }

    @Test
    fun `loadMore skips a busy repo instead of queueing behind it`() {
        val fake = fakeChainRepo("busy", length = 50)
        val log = loader(fake)
        log.loadCommits()
        drainBackgroundLoads(1_000)

        val releaseLatch = CountDownLatch(1)
        val enteredLatch = CountDownLatch(1)
        fake.logService.enteredLatch = enteredLatch
        fake.logService.releaseLatch = releaseLatch
        fake.tracker.completed.set(0)

        log.loadMore() // takes the repo's lock, then blocks on releaseLatch inside getLog
        enteredLatch.await(2, TimeUnit.SECONDS) shouldBe true

        // Lock busy: tryLock() must fail, this call returning immediately (jj-idea-2c8k's deliberate skip-don't-block).
        log.loadMore()
        Thread.sleep(200)
        fake.tracker.inFlight.get() shouldBe 1 // only the first call's fetch is in flight, the second never started one

        releaseLatch.countDown()
        drainBackgroundLoads(1_000)
    }

    @Test
    fun `a fetch failure that drops the window mid-flight is handled cleanly by a racing loadMore`() {
        val fake = fakeChainRepo("flaky", length = 50)
        val log = loader(fake)
        log.loadCommits()
        drainBackgroundLoads(1_000)

        // Every subsequent fetch fails, so refresh()'s window-removal-on-failure path
        // (refreshOneRepoLocked/loadMoreOneRepoLocked both remove pagedWindowByRepo[repo] on a
        // null fetchOnePage result) races against loadMore()'s own optimistic, unlocked
        // pagedWindowByRepo read — the exact staleness both *Locked bodies re-validate for.
        fake.logService.failing = true

        repeat(20) {
            log.refresh()
            log.loadMore()
        }
        drainBackgroundLoads(2_000)

        // No direct assertion needed beyond reaching here: an NPE or CME from operating on a
        // removed/replaced window would log via Tasks.kt's runInBackground wrapper and fail the
        // test through TestLoggerFactory, per this class's header.
    }
}
