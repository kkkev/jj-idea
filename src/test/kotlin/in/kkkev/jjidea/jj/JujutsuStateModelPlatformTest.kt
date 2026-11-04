package `in`.kkkev.jjidea.jj

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuStateModelPlatformTest {
    private val project = projectFixture()

    // stateModel.init fires fire-and-forget pooled-thread loaders that capture this fixture's
    // project (see PlatformTestSupport.drainBackgroundLoads); drain them before projectFixture
    // disposes the project, to avoid a flaky LeakHunter retained-Project report (jj-idea-q49j).
    @AfterEach
    fun drainStateModelLoads() = drainBackgroundLoads()

    @Test
    fun `logRefresh notifier fires connected listener`() {
        val stateModel = project.get().stateModel
        val disposable = Disposer.newDisposable()
        try {
            var fired = false
            stateModel.logRefresh.connect(disposable) { fired = true }

            stateModel.logRefresh.notify(Unit)
            UIUtil.dispatchAllInvocationEvents()

            fired shouldBe true
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `invalidate fires logRefresh`() {
        val stateModel = project.get().stateModel
        val disposable = Disposer.newDisposable()
        try {
            var fired = false
            stateModel.logRefresh.connect(disposable) { fired = true }

            val repo = mockk<JujutsuRepository> {
                every { project } returns this@JujutsuStateModelPlatformTest.project.get()
                every { logCache } returns mockk(relaxed = true)
            }
            repo.invalidate()
            UIUtil.dispatchAllInvocationEvents()

            fired shouldBe true
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `invalidate with select fires both logRefresh and changeSelection`() {
        val stateModel = project.get().stateModel
        val disposable = Disposer.newDisposable()
        try {
            var logRefreshFired = false
            var selectionKey: ChangeKey? = null
            stateModel.logRefresh.connect(disposable) { logRefreshFired = true }
            stateModel.changeSelection.connect(disposable) { selectionKey = it }

            val repo = mockk<JujutsuRepository> {
                every { project } returns this@JujutsuStateModelPlatformTest.project.get()
                every { logCache } returns mockk(relaxed = true)
            }
            repo.invalidate(select = WorkingCopy)
            UIUtil.dispatchAllInvocationEvents()

            logRefreshFired shouldBe true
            selectionKey shouldBe ChangeKey(repo, WorkingCopy)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `applyScheduledRepositoryRefresh clears logCache and fires logRefresh`() {
        val stateModel = project.get().stateModel
        val disposable = Disposer.newDisposable()
        try {
            var logRefreshFired = false
            stateModel.logRefresh.connect(disposable) { logRefreshFired = true }

            val cache = mockk<LogCache>(relaxed = true)
            val repo = mockk<JujutsuRepository> {
                every { directory } returns mockk<VirtualFile> {
                    every { path } returns "/nonexistent/scheduled-refresh-test"
                }
                every { logCache } returns cache
            }

            stateModel.applyScheduledRepositoryRefresh(listOf(repo))
            UIUtil.dispatchAllInvocationEvents()

            io.mockk.verify(exactly = 1) { cache.clear() }
            logRefreshFired shouldBe true
        } finally {
            Disposer.dispose(disposable)
        }
    }

    // Pins the actual production dispatch behind refreshVfsInBackground's default `dispatch`
    // argument, which the unit-level InvalidateVfsScaleTest can't observe since it injects its
    // own seam. Regresses jj-idea-nuk0 / GitHub #113: markDirtyAndRefresh's recursive walk used
    // to run synchronously on the EDT caller here.
    @Test
    fun `refreshVfsInBackground's default dispatch runs markDirty off the EDT`() {
        val edtThread = Thread.currentThread()
        var markDirtyThread: Thread? = null
        val latch = CountDownLatch(1)

        refreshVfsInBackground(
            directory = mockk<VirtualFile>(),
            markDirty = {
                markDirtyThread = Thread.currentThread()
                latch.countDown()
            }
        )
        latch.await(5, TimeUnit.SECONDS) shouldBe true

        markDirtyThread shouldNotBe null
        markDirtyThread shouldNotBe edtThread
    }

    @Test
    fun `checkAllOperationHeadsOnActivation's default dispatch runs probe off the EDT`() {
        val stateModel = project.get().stateModel
        val edtThread = Thread.currentThread()
        var probeThread: Thread? = null
        val latch = CountDownLatch(1)
        val repo = mockk<JujutsuRepository> {
            every { directory } returns mockk<VirtualFile> {
                every { path } returns "/nonexistent/activation-edt-test"
            }
        }

        stateModel.checkAllOperationHeadsOnActivation(
            repos = listOf(repo),
            probe = {
                probeThread = Thread.currentThread()
                latch.countDown()
            }
        )
        latch.await(5, TimeUnit.SECONDS) shouldBe true

        probeThread shouldNotBe null
        probeThread shouldNotBe edtThread
    }

    @Test
    fun `checkAllOperationHeadsOnActivation skips polled busy unhealthy and cooldown repos`() {
        val stateModel = project.get().stateModel

        fun repoAt(repoPath: String) = mockk<JujutsuRepository> {
            every { directory } returns mockk<VirtualFile> {
                every { path } returns repoPath
            }
        }

        val idleRepo = repoAt("/activation/idle")
        val polledRepo = repoAt("/activation/polled")
        val busyRepo = repoAt("/activation/busy")
        val unhealthyRepo = repoAt("/activation/unhealthy")
        val probed = mutableListOf<String>()
        val pollOwner = Any()
        OpHeadIds.setPolled(pollOwner, listOf("/activation/polled"))
        JujutsuRepositoryHealth.mark("/activation/unhealthy", RepositoryHealth.Unreadable("broken"))
        try {
            RepoActivity.track("/activation/busy") {
                stateModel.checkAllOperationHeadsOnActivation(
                    repos = listOf(idleRepo, polledRepo, busyRepo, unhealthyRepo),
                    nowNanos = 1_000_000_000L,
                    minIntervalNanos = 500_000_000L,
                    probe = { probed += it.directory.path },
                    dispatch = { it() }
                )
            }
            probed shouldBe listOf("/activation/idle")

            // Within the cooldown window, the same repo is skipped; once it elapses, it probes again.
            stateModel.checkAllOperationHeadsOnActivation(
                repos = listOf(idleRepo),
                nowNanos = 1_200_000_000L,
                minIntervalNanos = 500_000_000L,
                probe = { probed += it.directory.path },
                dispatch = { it() }
            )
            probed shouldBe listOf("/activation/idle")

            stateModel.checkAllOperationHeadsOnActivation(
                repos = listOf(idleRepo),
                nowNanos = 1_600_000_000L,
                minIntervalNanos = 500_000_000L,
                probe = { probed += it.directory.path },
                dispatch = { it() }
            )
            probed shouldBe listOf("/activation/idle", "/activation/idle")
        } finally {
            OpHeadIds.setPolled(pollOwner, emptySet())
            JujutsuRepositoryHealth.markReadable("/activation/unhealthy")
        }
    }

    @Test
    fun `filterToReference notifier fires connected listener with the reference name`() {
        val stateModel = project.get().stateModel
        val disposable = Disposer.newDisposable()
        try {
            var notified: String? = null
            stateModel.filterToReference.connect(disposable) { notified = it }

            stateModel.filterToReference.notify("main")
            UIUtil.dispatchAllInvocationEvents()

            notified shouldBe "main"
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `invalidateRepositoryState loads closestBookmarks`() {
        // Regression for jj-idea-zjbp: closestBookmarks used to be invalidated only from
        // repo.invalidate() (a plugin-initiated VCS operation), so the bookmark widget's
        // "name +N" fallback never loaded on IDE start or after an external jj operation —
        // it just showed a blank value. invalidateRepositoryState() is now the single place
        // every repo-refresh path invalidates references/workingCopies/closestBookmarks
        // together, so this must load them all.
        val stateModel = project.get().stateModel

        stateModel.closestBookmarks.hasLoaded shouldBe false

        stateModel.invalidateRepositoryState()
        drainBackgroundLoads()

        stateModel.closestBookmarks.hasLoaded shouldBe true
    }

    @Test
    fun `filterByAuthor notifier fires connected listener with the author email`() {
        val stateModel = project.get().stateModel
        val disposable = Disposer.newDisposable()
        try {
            var notified: String? = null
            stateModel.filterByAuthor.connect(disposable) { notified = it }

            stateModel.filterByAuthor.notify("alice@example.com")
            UIUtil.dispatchAllInvocationEvents()

            notified shouldBe "alice@example.com"
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
