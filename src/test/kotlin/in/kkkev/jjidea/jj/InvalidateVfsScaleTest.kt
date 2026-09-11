package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for the VFS mark-dirty walk on [invalidate]'s refresh path.
 *
 * `VfsUtil.markDirtyAndRefresh`'s `async` flag only defers the refresh *session* - the
 * recursive mark-dirty walk itself used to run synchronously on the caller's thread, which
 * is the EDT for every mutating jj command (jj-idea-nuk0, GitHub #113). [refreshVfsInBackground]
 * moves that walk onto a dispatched (production: pooled-thread) closure instead.
 *
 * Injects a counting `markDirty` fake and a capturing `dispatch` seam - see
 * contributing.md's "Writing a scale test" and [RepoLogCacheScaleTest] for the pattern this
 * follows: assert on operation counts, not wall-clock time, with a synthetic in-memory
 * "tree" sized so a reintroduced O(repo-size) walk on the caller's thread is unmistakable.
 */
class InvalidateVfsScaleTest {
    private val directory = mockk<VirtualFile>()

    @Test
    fun `mark-dirty walk does not run on the caller's thread`() {
        val n = 50_000
        var opsOnCallerThread = 0L
        var deferredAction: (() -> Unit)? = null

        refreshVfsInBackground(
            directory = directory,
            dispatch = { deferredAction = it },
            markDirty = { opsOnCallerThread += n }
        )

        // The regression this guards: an inline (non-dispatched) walk would have already
        // run markDirty and incremented this by n before we return here.
        opsOnCallerThread shouldBe 0L
        deferredAction shouldNotBe null // captured for later dispatch, not run inline
    }

    @Test
    fun `dispatched walk performs exactly one mark-dirty call, not one per file`() {
        var walkCalls = 0
        var opsPerformed = 0L
        var deferredAction: (() -> Unit)? = null

        refreshVfsInBackground(
            directory = directory,
            dispatch = { deferredAction = it },
            markDirty = {
                walkCalls++
                opsPerformed += 50_000 // synthetic "files under the repo root" count
            }
        )
        deferredAction!!.invoke()

        walkCalls shouldBe 1
        opsPerformed shouldBe 50_000L
    }

    @Test
    fun `dispatch is invoked exactly once with the repo directory`() {
        var dispatchCalls = 0

        refreshVfsInBackground(
            directory = directory,
            dispatch = { dispatchCalls++ }
        )

        dispatchCalls shouldBe 1
    }

    /**
     * Stubs `project.getService(JujutsuStateModel::class.java)` directly rather than relying on
     * a relaxed [Project] mock: [invalidate] reaches `project.stateModel`, an inline `service()`
     * call whose relaxed-mock return value otherwise fails a downstream cast to
     * [JujutsuStateModel].
     */
    private fun projectWithRelaxedStateModel(): Project = mockk<Project>(relaxed = true).also { project ->
        every { project.getService(JujutsuStateModel::class.java) } returns mockk(relaxed = true)
    }

    @Test
    fun `invalidate with vfsChanged true calls refreshVfs with the repo directory`() {
        val project = projectWithRelaxedStateModel()
        val repo = mockk<JujutsuRepository>()
        every { repo.project } returns project
        every { repo.directory } returns directory
        every { repo.logCache } returns mockk(relaxed = true)
        var received: VirtualFile? = null

        repo.invalidate(vfsChanged = true, refreshVfs = { received = it })

        received shouldBe directory
    }

    @Test
    fun `invalidate with vfsChanged false never calls refreshVfs`() {
        // directory is deliberately not stubbed - mirrors JujutsuStateModelPlatformTest's
        // repo mocks, and pins that the vfsChanged = false path never evaluates it.
        val project = projectWithRelaxedStateModel()
        val repo = mockk<JujutsuRepository> {
            every { this@mockk.project } returns project
            every { logCache } returns mockk(relaxed = true)
        }
        var calls = 0

        repo.invalidate(vfsChanged = false, refreshVfs = { calls++ })

        calls shouldBe 0
        verify(exactly = 0) { repo.directory }
    }
}
