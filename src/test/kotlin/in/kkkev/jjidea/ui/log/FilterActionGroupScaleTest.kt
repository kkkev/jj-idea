package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.replaceService
import `in`.kkkev.jjidea.actions.ListActionGroup
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RepositoryReferences
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.TagItem
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.util.SimpleNotifiableState
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Tag as JupiterTag

/**
 * Operation-count scale test for the Reference and Author filter popups (jj-idea-bok6, GitHub
 * #136). Building them via `DefaultActionGroup.add` resolved action ids for every existing child
 * on each add - O(N^2) `ActionManager.getId` calls, a 17s EDT freeze for the reporter. The
 * groups are now precomputed lists, so the build must make zero `ActionManager.getId` calls and
 * must not be a `DefaultActionGroup` at all. Asserts on work performed, never wall-clock time.
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class FilterActionGroupScaleTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>()
    private val n = 3_000

    @AfterEach
    fun drainStateModelLoads() = drainBackgroundLoads()

    private fun entry(changeId: String, authorEmail: String? = null) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit $changeId",
        author = authorEmail?.let { VcsUserImpl("Author", it) }
    )

    /** Swaps in an [ActionManager] that counts `getId` calls; returns the counter. */
    private fun countGetIdCalls(disposable: Disposable): AtomicInteger {
        val calls = AtomicInteger()
        val manager = mockk<ActionManager>(relaxed = true)
        every { manager.getId(any()) } answers {
            calls.incrementAndGet()
            null
        }
        ApplicationManager.getApplication().replaceService(ActionManager::class.java, manager, disposable)
        return calls
    }

    @Test
    fun `reference popup build is linear - no per-child action id resolution`() {
        (project.get().stateModel.references as SimpleNotifiableState).value = mapOf(
            repo to RepositoryReferences(
                bookmarks = (0 until n).map { BookmarkItem(Bookmark("bm$it"), emptyList()) },
                tags = (0 until n).map { TagItem(Tag("tag$it"), emptyList()) }
            )
        )
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries(listOf(entry("aaa111")))
        val disposable = Disposer.newDisposable()
        try {
            val comp = JujutsuReferenceFilterComponent(tableModel, project.get(), disposable).apply {
                initUi()
                initialize()
            }
            val calls = countGetIdCalls(disposable)

            val group = comp.createActionGroup()

            calls.get() shouldBe 0
            group.shouldBeInstanceOf<ListActionGroup>()
            group.shouldNotBeInstanceOf<DefaultActionGroup>()
            // +1: the "Loading..." placeholder, since seeding the state directly leaves hasLoaded false.
            group.getChildren(null).count { it !is Separator } shouldBe 2 * n + 1
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `author popup build is linear - no per-child action id resolution`() {
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries((0 until n).map { entry("c$it", "user$it@example.com") })
        val disposable = Disposer.newDisposable()
        try {
            val comp = JujutsuAuthorFilterComponent(tableModel, project.get()).apply {
                initUi()
                initialize()
            }
            val calls = countGetIdCalls(disposable)

            val group = comp.createActionGroup()

            calls.get() shouldBe 0
            group.shouldBeInstanceOf<ListActionGroup>()
            group.getChildren(null).count { it !is Separator } shouldBe n
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
