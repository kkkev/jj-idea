package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JujutsuMergeProviderTest {
    private val project = mockk<Project>()
    private val extractor = mockk<ConflictExtractor>()
    private val provider = JujutsuMergeProvider(project, extractor, repoFor = { null })

    @Test
    fun `loadRevisions - conflict content - returns correct MergeData`() {
        val bytes = "content".toByteArray()
        val mergeData = MergeData().also {
            it.CURRENT = "ours".toByteArray(Charsets.UTF_8)
            it.ORIGINAL = "base".toByteArray(Charsets.UTF_8)
            it.LAST = "theirs".toByteArray(Charsets.UTF_8)
        }

        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns bytes
        every { extractor.extract(bytes) } returns mergeData

        val result = provider.loadRevisions(file)

        result.CURRENT.toString(Charsets.UTF_8) shouldBe "ours"
        result.ORIGINAL.toString(Charsets.UTF_8) shouldBe "base"
        result.LAST.toString(Charsets.UTF_8) shouldBe "theirs"
    }

    @Test
    fun `loadRevisions - no conflict markers - throws VcsException`() {
        val bytes = "no conflicts here".toByteArray()
        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns bytes
        every { file.name } returns "test.txt"
        every { extractor.extract(bytes) } returns null

        shouldThrow<VcsException> { provider.loadRevisions(file) }
    }

    @Test
    fun `isBinary - binary file type - returns true`() {
        val file = mockk<VirtualFile>()
        every { file.fileType } returns mockk { every { isBinary } returns true }

        provider.isBinary(file) shouldBe true
    }

    @Test
    fun `isBinary - text file type - returns false`() {
        val file = mockk<VirtualFile>()
        every { file.fileType } returns mockk { every { isBinary } returns false }

        provider.isBinary(file) shouldBe false
    }

    // -------------------------------------------------------------------------
    // Refresh-after-resolve tests
    // -------------------------------------------------------------------------

    private val dirtyScopeManager = mockk<VcsDirtyScopeManager>(relaxed = true)

    @BeforeEach
    fun setUpDirtyManager() {
        mockkStatic(VcsDirtyScopeManager::class)
        every { VcsDirtyScopeManager.getInstance(project) } returns dirtyScopeManager
    }

    @AfterEach
    fun tearDownDirtyManager() = unmockkStatic(VcsDirtyScopeManager::class)

    private fun refreshProvider(
        repoFor: (VirtualFile) -> JujutsuRepository?,
        refreshAfterResolve: (JujutsuRepository) -> Unit
    ) = JujutsuMergeProvider(project, extractor, repoFor, refreshAfterResolve)

    @Test
    fun `conflictResolvedForFile - known repo - calls refreshAfterResolve once`() {
        val repo = mockk<JujutsuRepository>()
        val file = mockk<VirtualFile>()
        val refreshed = mutableListOf<JujutsuRepository>()
        val p = refreshProvider(repoFor = { repo }, refreshAfterResolve = { refreshed += it })

        p.conflictResolvedForFile(file)

        refreshed shouldBe listOf(repo)
        verify { dirtyScopeManager.fileDirty(file) }
    }

    @Test
    fun `conflictResolvedForFile - no repo - skips refresh`() {
        val file = mockk<VirtualFile>()
        val refreshed = mutableListOf<JujutsuRepository>()
        val p = refreshProvider(repoFor = { null }, refreshAfterResolve = { refreshed += it })

        p.conflictResolvedForFile(file)

        refreshed shouldBe emptyList()
        verify { dirtyScopeManager.fileDirty(file) }
    }

    @Test
    fun `conflictResolvedForFiles - two files same repo - refreshes repo once`() {
        val repo = mockk<JujutsuRepository>()
        val file1 = mockk<VirtualFile>()
        val file2 = mockk<VirtualFile>()
        val refreshed = mutableListOf<JujutsuRepository>()
        val p = refreshProvider(repoFor = { repo }, refreshAfterResolve = { refreshed += it })

        p.createMergeSession(listOf(file1, file2))
            .let { it as com.intellij.openapi.vcs.merge.MergeSessionEx }
            .conflictResolvedForFiles(listOf(file1, file2), MergeSession.Resolution.Merged)

        refreshed shouldBe listOf(repo)
    }

    @Test
    fun `conflictResolvedForFiles - files from distinct repos - refreshes each repo once`() {
        val repo1 = mockk<JujutsuRepository>()
        val repo2 = mockk<JujutsuRepository>()
        val file1 = mockk<VirtualFile>()
        val file2 = mockk<VirtualFile>()
        val refreshed = mutableListOf<JujutsuRepository>()
        val p = refreshProvider(
            repoFor = { f -> if (f === file1) repo1 else repo2 },
            refreshAfterResolve = { refreshed += it }
        )

        p.createMergeSession(listOf(file1, file2))
            .let { it as com.intellij.openapi.vcs.merge.MergeSessionEx }
            .conflictResolvedForFiles(listOf(file1, file2), MergeSession.Resolution.Merged)

        refreshed.toSet() shouldBe setOf(repo1, repo2)
        refreshed.size shouldBe 2
    }
}
