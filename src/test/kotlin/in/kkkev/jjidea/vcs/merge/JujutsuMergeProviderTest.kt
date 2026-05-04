package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuMergeProviderTest {
    private val project = mockk<Project>()
    private val provider = JujutsuMergeProvider(project)

    @Test
    fun `loadRevisions - conflict content - returns correct MergeData`() {
        val content = """
            |<<<<<<< Conflict 1 of 1
            |+++++++ Contents of side #1
            |ours
            |------- Base
            |base
            |+++++++ Contents of side #2
            |theirs
            |>>>>>>> Conflict 1 of 1 ends
        """.trimMargin()

        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns content.toByteArray(Charsets.UTF_8)

        val result = provider.loadRevisions(file)

        result.CURRENT.toString(Charsets.UTF_8) shouldBe "ours"
        result.ORIGINAL.toString(Charsets.UTF_8) shouldBe "base"
        result.LAST.toString(Charsets.UTF_8) shouldBe "theirs"
    }

    @Test
    fun `loadRevisions - no conflict markers - throws VcsException`() {
        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns "no conflicts here".toByteArray()
        every { file.name } returns "test.txt"

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
