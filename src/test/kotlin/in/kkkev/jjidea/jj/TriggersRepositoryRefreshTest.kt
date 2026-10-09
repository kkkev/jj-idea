package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vfs.VirtualFile
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** jj-idea-2570.12: ignored/working-copy file events must not trigger a log refresh; op_heads changes must. */
class TriggersRepositoryRefreshTest {
    private val opHead = mockk<VirtualFile>()
    private val repoRoot = mockk<VirtualFile>()

    private fun files(n: Int) = List(n) { mockk<VirtualFile>() }

    private fun triggers(files: List<VirtualFile?>, probes: IntArray = IntArray(1)) = triggersRepositoryRefresh(
        files,
        isOpHeadsChange = {
            probes[0]++
            it === opHead
        },
        isRepoRootChange = { it === repoRoot }
    )

    @Test
    fun `many working-copy file events trigger no refresh and cost one probe each`() {
        val probes = IntArray(1)
        triggers(files(1_000), probes) shouldBe false
        probes[0] shouldBe 1_000
    }

    @Test
    fun `an op_heads change among working-copy events triggers one refresh decision`() {
        triggers(files(50) + opHead + files(50)) shouldBe true
    }

    @Test
    fun `a repo root repair triggers a refresh`() {
        triggers(listOf(repoRoot)) shouldBe true
    }

    @Test
    fun `events without a file trigger nothing`() {
        triggers(listOf(null, null)) shouldBe false
    }
}
