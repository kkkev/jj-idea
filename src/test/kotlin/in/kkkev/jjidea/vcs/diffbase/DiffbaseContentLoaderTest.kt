package `in`.kkkev.jjidea.vcs.diffbase

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.impl.LineStatusTrackerContentLoader.ContentInfo
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.JujutsuVirtualFile
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [DiffbaseContentLoader] — the `LocalLineStatusTrackerProvider` that overrides
 * the gutter-marker base content when a custom diff base is configured (jj-idea-fwea / GitHub
 * #43). [isTrackedFile] runs on the EDT (`LineStatusTrackerManager.switchTracker`), so those
 * tests pin it to never call [DiffbaseService.resolve] (the jj-shelling-out path) — only
 * [DiffbaseService.isActive], a settings lookup.
 */
class DiffbaseContentLoaderTest {
    private val project = mockk<Project>()
    private val changeListManager = mockk<ChangeListManager>()
    private val repo = mockk<JujutsuRepository>()
    private val diffbaseService = mockk<DiffbaseService>()
    private val loader = DiffbaseContentLoader()

    @BeforeEach
    fun setup() {
        every { project.isDisposed } returns false
        mockkStatic(ChangeListManager::class)
        every { ChangeListManager.getInstance(project) } returns changeListManager
        mockkStatic(DiffbaseService::class)
        every { DiffbaseService.getInstance(project) } returns diffbaseService
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    // ── isTrackedFile ────────────────────────────────────────────────────────

    @Test
    fun `isTrackedFile is false for an ignored file, without consulting DiffbaseService`() {
        val file = MockVirtualFile("ignored.txt")
        every { changeListManager.getStatus(file) } returns FileStatus.IGNORED

        loader.isTrackedFile(project, file) shouldBe false
    }

    @Test
    fun `isTrackedFile is false for an unversioned file`() {
        val file = MockVirtualFile("unknown.txt")
        every { changeListManager.getStatus(file) } returns FileStatus.UNKNOWN

        loader.isTrackedFile(project, file) shouldBe false
    }

    @Test
    fun `isTrackedFile is false for a historical (JujutsuVirtualFile) version`() {
        val file = mockk<JujutsuVirtualFile>()

        loader.isTrackedFile(project, file) shouldBe false
    }

    @Test
    fun `isTrackedFile is false when the project is disposed`() {
        every { project.isDisposed } returns true
        val file = MockVirtualFile("file.txt")

        loader.isTrackedFile(project, file) shouldBe false
    }

    @Test
    fun `isTrackedFile delegates to DiffbaseService isActive for an ordinary tracked file`() {
        val file = MockVirtualFile("file.txt")
        every { changeListManager.getStatus(file) } returns FileStatus.MODIFIED
        every { diffbaseService.isActive(file) } returns true

        loader.isTrackedFile(project, file) shouldBe true
    }

    @Test
    fun `isTrackedFile is false when DiffbaseService reports no active diff base`() {
        val file = MockVirtualFile("file.txt")
        every { changeListManager.getStatus(file) } returns FileStatus.MODIFIED
        every { diffbaseService.isActive(file) } returns false

        loader.isTrackedFile(project, file) shouldBe false
    }

    // ── getContentInfo / shouldBeUpdated ─────────────────────────────────────

    private fun contentInfo(
        revisionNumber: VcsRevisionNumber,
        charset: Charset = StandardCharsets.UTF_8,
        base: ChangeId = ChangeId("base", "base")
    ): ContentInfo {
        val filePath: FilePath = LocalFilePath("/repo/file.txt", false)
        val file = mockk<VirtualFile> {
            every { this@mockk.charset } returns charset
        }
        every { file.filePath } returns filePath
        every { project.possibleJujutsuRepositoryFor(file) } returns repo
        every { diffbaseService.resolve(repo) } returns base
        val contentRevision = mockk<ContentRevision> {
            every { this@mockk.revisionNumber } returns revisionNumber
        }
        every { repo.createContentRevision(filePath, base) } returns contentRevision
        return loader.getContentInfo(project, file)!!
    }

    @Test
    fun `shouldBeUpdated is true when old is not a DiffbaseContentInfo`() {
        val new = contentInfo(revisionNumber = mockk())
        loader.shouldBeUpdated(null, new) shouldBe true
    }

    @Test
    fun `shouldBeUpdated is false when revision number and charset are unchanged`() {
        val number = mockk<VcsRevisionNumber>()
        val old = contentInfo(revisionNumber = number)
        val new = contentInfo(revisionNumber = number)
        loader.shouldBeUpdated(old, new) shouldBe false
    }

    @Test
    fun `shouldBeUpdated is true when the revision number changed`() {
        val old = contentInfo(revisionNumber = mockk())
        val new = contentInfo(revisionNumber = mockk())
        loader.shouldBeUpdated(old, new) shouldBe true
    }

    @Test
    fun `shouldBeUpdated is true when the old revision number is NULL`() {
        val old = contentInfo(revisionNumber = VcsRevisionNumber.NULL)
        val new = contentInfo(revisionNumber = VcsRevisionNumber.NULL)
        loader.shouldBeUpdated(old, new) shouldBe true
    }

    @Test
    fun `shouldBeUpdated is true when the charset changed`() {
        val number = mockk<VcsRevisionNumber>()
        val old = contentInfo(revisionNumber = number, charset = StandardCharsets.UTF_8)
        val new = contentInfo(revisionNumber = number, charset = StandardCharsets.ISO_8859_1)
        loader.shouldBeUpdated(old, new) shouldBe true
    }
}
