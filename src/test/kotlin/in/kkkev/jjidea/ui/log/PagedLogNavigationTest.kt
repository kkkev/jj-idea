package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogCache
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * jj-idea-7rxm (GitHub #140): a row loaded by an annotation click's expansion
 * ([UnifiedJujutsuLogDataLoader.loadExpanding]) must survive every later load, or the log scrolls
 * back to a more recent change once the target row disappears from under the viewport.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class PagedLogNavigationTest {
    private val projectFx = projectFixture()
    private val pageSize = 10
    private val length = 100
    private val target = 60 // well past the first page, so only the expansion can supply it

    @AfterEach
    fun cleanup() {
        drainBackgroundLoads(2_000)
    }

    private class Fake(val repo: JujutsuRepository, val chain: List<LogEntry>, val getLogCalls: AtomicInteger)

    /** An in-memory cache with the real one's contract: [loadContext] stores what it fetches. */
    private class FakeLogCache(private val chain: List<LogEntry>) : LogCache {
        private val stored = LinkedHashMap<ChangeId, LogEntry>()

        override val all: List<LogEntry> get() = stored.values.toList()

        override fun get(revision: Revision): LogEntry = stored.getValue(revision as ChangeId)

        override fun loadContext(id: ChangeId, window: Int): List<LogEntry> {
            if (stored.containsKey(id)) return all
            val at = chain.indexOfFirst { it.id == id }
            if (at < 0) return emptyList()
            store(chain.subList((at - window).coerceAtLeast(0), (at + window + 1).coerceAtMost(chain.size)))
            return all
        }

        override fun reload() = all

        override fun store(entries: List<LogEntry>) {
            entries.forEach { stored[it.id] = it }
        }

        override fun clear() = stored.clear()
    }

    private class FakeLogService(private val chain: List<LogEntry>, private val calls: AtomicInteger) :
        LogService by mockk(relaxed = true) {
        override fun getLogHeads(revset: Revset, quiet: Boolean): Result<List<ChangeId>> = Result.success(
            listOf(chain.first().id)
        )

        override fun getLog(
            revset: Revset,
            filePaths: List<FilePath>,
            limit: Int?,
            quiet: Boolean
        ): Result<List<LogEntry>> {
            calls.incrementAndGet()
            val ids = Regex("""present\(([^)]+)\)""").findAll(revset.toString()).map { it.groupValues[1] }.toSet()
            val start = ids.mapNotNull { id -> chain.indexOfFirst { it.id.full == id }.takeIf { it >= 0 } }
                .minOrNull() ?: return Result.success(emptyList())
            return Result.success(chain.drop(start).take(limit ?: chain.size))
        }
    }

    private fun fakeRepo(): Fake {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.displayName } returns "a"
        every { repo.directory } returns mockk<VirtualFile>(relaxed = true) { every { path } returns "/fake/a" }
        val chain = (0 until length).map { i ->
            LogEntry(
                repo = repo,
                id = ChangeId("a-$i", "a-$i"),
                commitId = CommitId("commit-a-$i"),
                underlyingDescription = "desc $i",
                parentIds = if (i == length - 1) emptyList() else listOf(ChangeId("a-${i + 1}", "a-${i + 1}"))
            )
        }
        val calls = AtomicInteger()
        every { repo.logService } returns FakeLogService(chain, calls)
        every { repo.logCache } returns FakeLogCache(chain)
        return Fake(repo, chain, calls)
    }

    private class Session(
        val loader: UnifiedJujutsuLogDataLoader,
        private val published: AtomicReference<List<ChangeKey>>
    ) {
        /** Keys of the rows most recently handed to the panel. */
        val keys get() = published.get()
    }

    /** Loads page 1, then expands around [target], the way an annotation click does. */
    private fun navigated(fake: Fake): Session {
        val published = AtomicReference<List<ChangeKey>>(emptyList())
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true)
        every { panel.onDataLoaded(any()) } answers {
            published.set(firstArg<UnifiedJujutsuLogDataLoader.Data>().entries.map { it.key })
        }
        val loader = UnifiedJujutsuLogDataLoader(projectFx.get(), { listOf(fake.repo) }, panel)
        loader.trickleEnabled = false
        loader.pageRows = pageSize
        loader.maxPageRows = pageSize
        loader.loadCommits()
        drainBackgroundLoads(1_000)
        loader.loadExpanding(fake.repo, fake.chain[target].id)
        drainBackgroundLoads(1_000)
        return Session(loader, published).also { it.keys shouldContainTarget fake }
    }

    private infix fun List<ChangeKey>.shouldContainTarget(fake: Fake) {
        (ChangeKey(fake.repo, fake.chain[target].id) in this) shouldBe true
    }

    @Test
    fun `the expanded target survives a trickle page`() {
        val fake = fakeRepo()
        val session = navigated(fake)

        session.loader.trickleTick()
        drainBackgroundLoads(1_000)

        session.keys shouldContainTarget fake
    }

    @Test
    fun `the expanded target survives a paged refresh`() {
        val fake = fakeRepo()
        val session = navigated(fake)

        session.loader.refresh()
        drainBackgroundLoads(1_000)

        session.keys shouldContainTarget fake
    }

    @Test
    fun `the expanded target survives a full reload`() {
        // refresh()/forceRefresh() fall back to loadCommits() when paging is not viable (an unpaged
        // revset, or a frontier too wide to page), and a freshly opened tab's own first load
        // can finish after the expansion it was opened to show.
        val fake = fakeRepo()
        val session = navigated(fake)

        session.loader.loadCommits()
        drainBackgroundLoads(1_000)

        session.keys shouldContainTarget fake
    }

    @Test
    fun `an explicit Refresh drops the expansion`() {
        val fake = fakeRepo()
        val session = navigated(fake)

        session.loader.clearExpansions()
        session.loader.forceRefresh()
        drainBackgroundLoads(1_000)

        (ChangeKey(fake.repo, fake.chain[target].id) in session.keys) shouldBe false
    }

    @Test
    fun `keeping the expansion across a full reload costs no extra jj calls`() {
        val fake = fakeRepo()
        val session = navigated(fake)
        val before = fake.getLogCalls.get()

        session.loader.loadCommits()
        drainBackgroundLoads(1_000)

        // One seed-and-page-1 fetch, exactly what a reload without any expansion costs.
        fake.getLogCalls.get() - before shouldBe 1
    }
}
