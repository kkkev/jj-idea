package `in`.kkkev.jjidea.vcs.diffbase

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.VcsAnnotationLocalChangesListener
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.JujutsuStateModel
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.commandResult
import `in`.kkkev.jjidea.settings.DiffbaseStrategy
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.JujutsuSettingsState
import `in`.kkkev.jjidea.settings.RepositoryConfig
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DiffbaseService], the shared strategy→revision resolver that
 * [DiffbaseContentLoader] and [in.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider] both
 * consult (jj-idea-fwea / GitHub #43). [project] is a relaxed mock, but `getService` for
 * [JujutsuStateModel] is stubbed explicitly (rather than left to the relaxed default): that
 * lookup goes through the reified `service()` extension, which casts the raw result to
 * [JujutsuStateModel] — a relaxed mock's generic-erased default answer fails that cast.
 * `revset` arguments are never matched with `any()` below: `Revset` is implemented by
 * `@JvmInline value class`es (`Expression`), which mockk's matcher can't build an `any()` for
 * (see [in.kkkev.jjidea.jj.RepoLogCacheScaleTest], which avoids it the same way).
 */
class DiffbaseServiceTest {
    private lateinit var project: Project
    private lateinit var logService: LogService
    private lateinit var repo: JujutsuRepository
    private lateinit var service: DiffbaseService

    @BeforeEach
    fun setup() {
        project = mockk(relaxed = true)
        every { project.getService(JujutsuStateModel::class.java) } returns mockk(relaxed = true)
        every { project.getService(FileStatusManager::class.java) } returns mockk(relaxed = true)
        every { project.getService(ProjectLevelVcsManager::class.java) } returns mockk(relaxed = true)
        logService = mockk()
        val dir = mockk<VirtualFile>()
        every { dir.path } returns "/repo"
        repo = mockk {
            every { directory } returns dir
            every { logService } returns this@DiffbaseServiceTest.logService
        }
        service = DiffbaseService(project)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun stubSettings(
        strategy: DiffbaseStrategy = DiffbaseStrategy.WORKING_COPY_PARENT,
        customRevset: String = "",
        repoOverride: RepositoryConfig? = null
    ) {
        val settings = JujutsuSettings()
        settings.loadState(
            JujutsuSettingsState(
                diffbaseStrategy = strategy,
                customDiffbaseRevset = customRevset,
                repositoryOverrides = repoOverride?.let { mutableMapOf("/repo" to it) } ?: mutableMapOf()
            )
        )
        every { project.getService(JujutsuSettings::class.java) } returns settings
    }

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc"
    )

    @Test
    fun `isActive is false for WORKING_COPY_PARENT`() {
        stubSettings(strategy = DiffbaseStrategy.WORKING_COPY_PARENT)
        service.isActive(repo) shouldBe false
    }

    @Test
    fun `isActive is true for IMMUTABLE_ANCESTOR`() {
        stubSettings(strategy = DiffbaseStrategy.IMMUTABLE_ANCESTOR)
        service.isActive(repo) shouldBe true
    }

    @Test
    fun `isActive is false for CUSTOM_REVSET with a blank revset`() {
        stubSettings(strategy = DiffbaseStrategy.CUSTOM_REVSET, customRevset = "  ")
        service.isActive(repo) shouldBe false
    }

    @Test
    fun `isActive is true for CUSTOM_REVSET with a non-blank revset`() {
        stubSettings(strategy = DiffbaseStrategy.CUSTOM_REVSET, customRevset = "trunk()")
        service.isActive(repo) shouldBe true
    }

    @Test
    fun `isActive never calls the log service`() {
        stubSettings(strategy = DiffbaseStrategy.IMMUTABLE_ANCESTOR)
        service.isActive(repo)
        verify { logService wasNot Called }
    }

    @Test
    fun `resolve returns null for WORKING_COPY_PARENT without calling the log service`() {
        stubSettings(strategy = DiffbaseStrategy.WORKING_COPY_PARENT)
        service.resolve(repo) shouldBe null
        verify { logService wasNot Called }
    }

    @Test
    fun `resolve returns the first log entry's id for a resolving revset`() {
        stubSettings(strategy = DiffbaseStrategy.IMMUTABLE_ANCESTOR)
        every {
            logService.getLog(revset = Expression(DiffbaseStrategy.IMMUTABLE_ANCESTOR_REVSET), limit = 2, quiet = true)
        } returns Result.success(listOf(entry("abc")))

        service.resolve(repo) shouldBe ChangeId("abc", "abc", null)
    }

    @Test
    fun `resolve returns null when the revset resolves to nothing`() {
        stubSettings(strategy = DiffbaseStrategy.IMMUTABLE_ANCESTOR)
        every {
            logService.getLog(revset = Expression(DiffbaseStrategy.IMMUTABLE_ANCESTOR_REVSET), limit = 2, quiet = true)
        } returns Result.success(emptyList())

        service.resolve(repo) shouldBe null
    }

    @Test
    fun `resolve returns null when the revset resolves ambiguously (more than one revision)`() {
        // A diff base needs exactly one revision, unlike the log view's revset — mirrors jj's
        // own single-revision commands (`jj edit`, `jj file annotate -r`), which refuse an
        // ambiguous revset rather than picking one arbitrarily.
        stubSettings(strategy = DiffbaseStrategy.CUSTOM_REVSET, customRevset = "heads(mutable())")
        every {
            logService.getLog(revset = Expression("heads(mutable())"), limit = 2, quiet = true)
        } returns Result.success(listOf(entry("abc"), entry("def")))

        service.resolve(repo) shouldBe null
    }

    @Test
    fun `resolve does not cache an ambiguous result`() {
        stubSettings(strategy = DiffbaseStrategy.CUSTOM_REVSET, customRevset = "heads(mutable())")
        every {
            logService.getLog(revset = Expression("heads(mutable())"), limit = 2, quiet = true)
        } returns Result.success(listOf(entry("abc"), entry("def")))

        service.resolve(repo)
        service.resolve(repo)

        verify(exactly = 2) { logService.getLog(revset = Expression("heads(mutable())"), limit = 2, quiet = true) }
    }

    @Test
    fun `resolve returns null when the log command fails`() {
        stubSettings(strategy = DiffbaseStrategy.CUSTOM_REVSET, customRevset = "zz(")
        every {
            logService.getLog(revset = Expression("zz("), limit = 2, quiet = true)
        } returns Result.failure(RuntimeException("bad revset"))

        service.resolve(repo) shouldBe null
    }

    @Test
    fun `resolve caches the resolution, issuing exactly one log call across repeated requests`() {
        stubSettings(strategy = DiffbaseStrategy.IMMUTABLE_ANCESTOR)
        every {
            logService.getLog(revset = Expression(DiffbaseStrategy.IMMUTABLE_ANCESTOR_REVSET), limit = 2, quiet = true)
        } returns Result.success(listOf(entry("abc")))

        repeat(50) { service.resolve(repo) }

        verify(exactly = 1) {
            logService.getLog(revset = Expression(DiffbaseStrategy.IMMUTABLE_ANCESTOR_REVSET), limit = 2, quiet = true)
        }
    }

    @Test
    fun `notifyDiffbaseChanged clears the cache so the next resolve re-queries`() {
        stubSettings(strategy = DiffbaseStrategy.IMMUTABLE_ANCESTOR)
        every {
            logService.getLog(revset = Expression(DiffbaseStrategy.IMMUTABLE_ANCESTOR_REVSET), limit = 2, quiet = true)
        } returns Result.success(listOf(entry("abc")))

        service.resolve(repo)
        service.notifyDiffbaseChanged()
        service.resolve(repo)

        verify(exactly = 2) {
            logService.getLog(revset = Expression(DiffbaseStrategy.IMMUTABLE_ANCESTOR_REVSET), limit = 2, quiet = true)
        }
    }

    // jj-idea-bia2: open annotations are closed by JujutsuAnnotationProvider (diffbaseChanged), not
    // reloaded here — a reload of an annotation for a file absent at the new base leaves the old gutter
    // and an error banner behind. See notifyDiffbaseChanged's doc comment.
    @Test
    fun `notifyDiffbaseChanged notifies diffbaseChanged and does not reload annotations`() {
        val annotationListener = mockk<VcsAnnotationLocalChangesListener>(relaxed = true)
        val vcsManager = mockk<ProjectLevelVcsManager> {
            every { annotationLocalChangesListener } returns annotationListener
        }
        every { project.getService(ProjectLevelVcsManager::class.java) } returns vcsManager
        val stateModel = mockk<JujutsuStateModel>(relaxed = true)
        every { project.getService(JujutsuStateModel::class.java) } returns stateModel

        service.notifyDiffbaseChanged()

        verify(exactly = 1) { stateModel.diffbaseChanged.notify(Unit) }
        verify(exactly = 0) { annotationListener.reloadAnnotations() }
    }

    // ── isAbsentAtBase (jj-idea-bia2) ────────────────────────────────────────

    private val filePath: FilePath = LocalFilePath("/repo/foo.rs", false)

    private fun stubResolvesTo(id: String, executor: CommandExecutor) {
        stubSettings(strategy = DiffbaseStrategy.IMMUTABLE_ANCESTOR)
        every {
            logService.getLog(revset = Expression(DiffbaseStrategy.IMMUTABLE_ANCESTOR_REVSET), limit = 2, quiet = true)
        } returns Result.success(listOf(entry(id)))
        every { repo.commandExecutor } returns executor
    }

    @Test
    fun `isAbsentAtBase is false without a custom base and never lists files`() {
        stubSettings(strategy = DiffbaseStrategy.WORKING_COPY_PARENT)
        val executor = mockk<CommandExecutor>()
        every { repo.commandExecutor } returns executor

        service.isAbsentAtBase(repo, filePath) shouldBe false

        verify { executor wasNot Called }
    }

    @Test
    fun `isAbsentAtBase is true when file list is empty at the resolved base`() {
        val executor = mockk<CommandExecutor>()
        stubResolvesTo("abc", executor)
        every { executor.fileList(listOf(filePath), ChangeId("abc", "abc", null)) } returns commandResult(0, "")

        service.isAbsentAtBase(repo, filePath) shouldBe true
    }

    @Test
    fun `isAbsentAtBase is false when the file exists at the base`() {
        val executor = mockk<CommandExecutor>()
        stubResolvesTo("abc", executor)
        every { executor.fileList(listOf(filePath), ChangeId("abc", "abc", null)) } returns commandResult(0, "foo.rs\n")

        service.isAbsentAtBase(repo, filePath) shouldBe false
    }

    @Test
    fun `isAbsentAtBase caches per file so repeated menu builds run jj once`() {
        val executor = mockk<CommandExecutor>()
        stubResolvesTo("abc", executor)
        every { executor.fileList(any(), any()) } returns commandResult(0, "")

        repeat(5) { service.isAbsentAtBase(repo, filePath) }

        verify(exactly = 1) { executor.fileList(any(), any()) }
    }

    @Test
    fun `notifyDiffbaseChanged drops the cached absence answer`() {
        val executor = mockk<CommandExecutor>()
        stubResolvesTo("abc", executor)
        every { executor.fileList(any(), any()) } returns commandResult(0, "")
        service.isAbsentAtBase(repo, filePath)

        service.notifyDiffbaseChanged()
        service.isAbsentAtBase(repo, filePath)

        verify(exactly = 2) { executor.fileList(any(), any()) }
    }
}
