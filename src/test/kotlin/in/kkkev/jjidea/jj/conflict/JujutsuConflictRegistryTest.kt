package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuConflictRegistryTest {
    private val project = projectFixture()

    private fun virtualFile(path: String) = mockk<VirtualFile> { every { this@mockk.path } returns path }

    private fun conflictInfo(path: String, sides: Int = 2, deletions: Int = 0) =
        ConflictInfo(path, sides, deletions, "$sides-sided conflict")

    @Test
    fun `working copy replace and get round-trip unchanged`() {
        val registry = project.get().conflictRegistry
        val repoDir = virtualFile("/repo")
        val file = virtualFile("/repo/foo.txt")

        registry.replace(repoDir, listOf(conflictInfo("foo.txt")))

        registry.get(file) shouldBe conflictInfo("foo.txt")
    }

    @Test
    fun `per-revision replace is readable and does not disturb the working copy slot`() {
        val registry = project.get().conflictRegistry
        val repoDir = virtualFile("/repo")
        val fooFile = virtualFile("/repo/foo.txt")
        val barFile = virtualFile("/repo/bar.txt")
        val revision = ChangeId("abc123")

        registry.replace(repoDir, listOf(conflictInfo("bar.txt", deletions = 1)))
        registry.replace(repoDir, listOf(conflictInfo("foo.txt")), revision)

        registry.get(repoDir, fooFile, revision) shouldBe conflictInfo("foo.txt")
        registry.get(barFile) shouldBe conflictInfo("bar.txt", deletions = 1)
        registry.get(repoDir, barFile, WorkingCopy) shouldBe conflictInfo("bar.txt", deletions = 1)
        registry.get(repoDir, fooFile, WorkingCopy) shouldBe null
    }

    @Test
    fun `two revisions coexist and re-replace only clears the touched revision`() {
        val registry = project.get().conflictRegistry
        val repoDir = virtualFile("/repo")
        val revisionA = ChangeId("aaa")
        val revisionB = ChangeId("bbb")

        registry.replace(repoDir, listOf(conflictInfo("a.txt")), revisionA)
        registry.replace(repoDir, listOf(conflictInfo("b.txt")), revisionB)
        registry.replace(repoDir, listOf(conflictInfo("a2.txt")), revisionA)

        registry.conflicts(repoDir, revisionA) shouldBe mapOf("a2.txt" to conflictInfo("a2.txt"))
        registry.conflicts(repoDir, revisionB) shouldBe mapOf("b.txt" to conflictInfo("b.txt"))
    }

    @Test
    fun `revision cache is bounded and evicts least-recently-touched entries`() {
        val registry = project.get().conflictRegistry
        val repoDir = virtualFile("/repo")

        registry.replace(repoDir, listOf(conflictInfo("wc.txt")))
        repeat(200) { i ->
            registry.replace(repoDir, listOf(conflictInfo("f$i.txt")), ChangeId("rev$i"))
        }

        registry.cachedRevisionCount shouldBe 32
        registry.conflicts(repoDir, ChangeId("rev0")) shouldBe emptyMap()
        registry.conflicts(repoDir, ChangeId("rev199")) shouldBe mapOf("f199.txt" to conflictInfo("f199.txt"))
        registry.get(virtualFile("/repo/wc.txt")) shouldBe conflictInfo("wc.txt")
    }
}
