package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.CliLogService
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Real-jj coverage for [CliLogService.getChangeIds] — the ids-only query jj-idea-vqpn's revset log
 * filter chip (GitHub #116, see [in.kkkev.jjidea.ui.log.resolveRevsetFilter]) resolves against.
 * Same fixture/harness pattern as [PagedLogWindowContractTest].
 */
@Tag("contract")
@RequiresJj
class RevsetFilterContractTest {
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
    fun `getChangeIds returns exactly the matching change ids, no other metadata fetched`() {
        setUp()
        repeat(5) { jj.newChange("commit $it") }
        val expected = logService.getLog(revset = Expression("ancestors(@, 3)")).getOrThrow().map { it.id.full }

        val ids = logService.getChangeIds(Expression("ancestors(@, 3)")).getOrThrow()

        ids.map { it.full } shouldContainExactlyInAnyOrder expected
    }

    @Test
    fun `an invalid revset fails with jj's own parse error`() {
        setUp()

        val result = logService.getChangeIds(Expression("not a valid revset((("))

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message.orEmpty() shouldContain "Error from jj log"
    }
}
