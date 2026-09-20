package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.jj.closestBookmarks
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Real-jj regression test for [closestBookmarks]' distance calculation on a merge commit
 * (jj-idea-lig7 follow-up): when the working copy is a merge of a bookmarked branch and an
 * unrelated, unbookmarked branch, the distance must count only the commits actually on the path
 * back to the bookmark - not the entire unrelated branch's length, which is what the plain `X..Y`
 * revset (without the `descendants(X)` restriction) counted before this fix.
 */
@Tag("contract")
@RequiresJj
class ClosestBookmarksContractTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli
    private lateinit var logService: CliLogService

    private fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
        val root = mockk<VirtualFile> { every { path } returns tempDir.toString() }
        val repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns CliExecutor(root)
            every { directory } returns root
        }
        logService = CliLogService(repo)
    }

    @Test
    fun `merge of a bookmarked branch and a long unrelated branch reports distance 1`() {
        setUp()
        jj.run("new", "root()", "-m", "root")
        jj.bookmarkCreate("deep-branch")
        repeat(5) {
            jj.newChange("deep-$it")
            jj.run("bookmark", "set", "deep-branch", "-r", "@")
        }
        val txuq = jj.run("log", "-r", "@", "--no-graph", "-T", "change_id.short()").stdout.trim()

        jj.run("new", "root()", "-m", "svn-root")
        repeat(20) { jj.newChange("svn-$it") }
        val svn = jj.run("log", "-r", "@", "--no-graph", "-T", "change_id.short()").stdout.trim()

        jj.run("new", txuq, svn, "-m", "merge")

        val result = logService.closestBookmarks(WorkingCopy)

        result?.names?.map { it.name } shouldBe listOf("deep-branch")
        result?.distance shouldBe 1
        result?.distanceCapped shouldBe false
    }
}
