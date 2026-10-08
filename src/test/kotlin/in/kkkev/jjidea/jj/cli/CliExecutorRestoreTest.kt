package `in`.kkkev.jjidea.jj.cli

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class CliExecutorRestoreTest {
    private val root = mockk<VirtualFile> { every { path } returns "/repo" }

    private fun filePath(path: String) = mockk<FilePath> { every { this@mockk.path } returns path }

    @Test
    fun `restoreChangesInArgs - uses changes-in so merges work`() {
        val args = restoreChangesInArgs(listOf(filePath("/repo/foo.txt")), WorkingCopy, root)

        args.args shouldBe listOf("restore", "-c", "@", "cwd:\"foo.txt\"")
    }
}
