package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogCache
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RepositoryReferences
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.util.SimpleNotifiableState
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * jj-idea-dii4 (GitHub #128): a Refresh reloads log rows and
 * [in.kkkev.jjidea.jj.JujutsuStateModel.references] in parallel, and the loader bakes whatever
 * `references` held at merge time into each row's bookmark ahead/behind. When the rows won that
 * race, their ↑n/↓m stayed at the previous load's numbers until a second Refresh.
 * [UnifiedJujutsuLogDataLoader.reapplyBookmarkCorrections] (called whenever `references`
 * publishes) must re-merge with the fresh numbers - and must not re-merge when nothing changed.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class BookmarkCorrectionsReapplyTest {
    private val projectFx = projectFixture()

    @AfterEach
    fun cleanup() = drainBackgroundLoads(1_000)

    @Suppress("UNCHECKED_CAST")
    private val references
        get() = projectFx.get().stateModel.references
            as SimpleNotifiableState<Map<JujutsuRepository, RepositoryReferences>>

    private fun setReferences(repo: JujutsuRepository, behind: Int) {
        references.value = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(BookmarkItem(Bookmark("main", behindCount = behind), ChangeId("a-0", "a-0")))
            )
        )
    }

    private fun fakeRepo(): JujutsuRepository {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.displayName } returns "a"
        every { repo.directory } returns mockk<VirtualFile>(relaxed = true) { every { path } returns "/fake/a" }
        val entry = LogEntry(
            repo = repo,
            id = ChangeId("a-0", "a-0"),
            commitId = CommitId("commit-a-0"),
            underlyingDescription = "desc",
            bookmarks = listOf(Bookmark("main"))
        )
        val stored = CopyOnWriteArrayList<List<LogEntry>>()
        every { repo.logCache } returns mockk<LogCache>(relaxed = true) {
            every { reload() } returns listOf(entry)
            every { store(any()) } answers { stored.add(firstArg<List<LogEntry>>()) }
            every { all } answers { stored.lastOrNull().orEmpty() }
        }
        return repo
    }

    @Test
    fun `fresh references re-merge the loaded rows with the new ahead-behind`() {
        val repo = fakeRepo()
        // Mark references loaded (the test project has no repos, so this loads empty) before
        // seeding it, so immediateValue returns the seeded value rather than re-running the loader.
        references.invalidate()
        drainBackgroundLoads(500)
        setReferences(repo, behind = 1)

        val published = CopyOnWriteArrayList<UnifiedJujutsuLogDataLoader.Data>()
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true) {
            every { onDataLoaded(any()) } answers { published.add(firstArg()) }
        }
        val loader = UnifiedJujutsuLogDataLoader(projectFx.get(), { listOf(repo) }, panel)
        loader.loadCommits()
        drainBackgroundLoads(1_000)
        published.last().mainBehind() shouldBe 1

        // Unchanged references: no extra merge.
        val before = published.size
        loader.reapplyBookmarkCorrections()
        drainBackgroundLoads(500)
        published.size shouldBe before

        // references finishes loading after the rows did (the Refresh race): one re-merge, fresh count.
        setReferences(repo, behind = 3)
        loader.reapplyBookmarkCorrections()
        drainBackgroundLoads(500)
        published.size shouldBe before + 1
        published.last().mainBehind() shouldBe 3
    }

    private fun UnifiedJujutsuLogDataLoader.Data.mainBehind() =
        entries.single().bookmarks.single { it.name.name == "main" }.behindCount
}
