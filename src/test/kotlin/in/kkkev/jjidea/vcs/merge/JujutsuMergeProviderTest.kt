package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuMergeProviderTest {
    private val project = mockk<Project>()
    private val extractor = mockk<ConflictExtractor>()
    private val provider = JujutsuMergeProvider(project, extractor)

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
}
