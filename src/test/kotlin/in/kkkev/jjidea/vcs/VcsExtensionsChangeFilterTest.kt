package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression test for jj-idea-t0zo: the "Working copy" tool window (and the resolve-conflict
 * actions) read [com.intellij.openapi.vcs.changes.ChangeListManager.allChanges], which is a
 * project-wide, VCS-agnostic aggregate of every registered change provider - Jujutsu's *and*
 * e.g. Git4Idea's in a colocated repo. [Iterable.filterInJujutsuRepo] is the scoping fix;
 * this test verifies it keeps only changes whose file resolves to an initialised jj repo.
 */
class VcsExtensionsChangeFilterTest {
    private val project = mockk<Project>()
    private val repo = mockk<JujutsuRepository>()

    @BeforeEach
    fun setup() {
        // Mocking the whole file's static class also mocks filterInJujutsuRepo itself
        // (the function under test), since it lives in the same file as
        // possibleJujutsuRepositoryFor. Restore its real body so only the repository lookup
        // it delegates to is stubbed per-input below.
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        every { any<Iterable<Change>>().filterInJujutsuRepo(any()) } answers { callOriginal() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun change(path: String, inJujutsuRepo: Boolean): Change {
        val filePath = LocalFilePath(path, false)
        val revision = mockk<ContentRevision> {
            every { file } returns filePath
        }
        every { project.possibleJujutsuRepositoryFor(filePath) } returns if (inJujutsuRepo) repo else null
        return Change(null, revision)
    }

    @Test
    fun `keeps only changes owned by an initialised jj repository`() {
        val jjOwned = change("jj-repo/src/Main.kt", inJujutsuRepo = true)
        val gitOwned = change("git-repo/src/Other.kt", inJujutsuRepo = false)

        val result = listOf(jjOwned, gitOwned).filterInJujutsuRepo(project)

        result shouldBe listOf(jjOwned)
    }

    @Test
    fun `drops all changes when none belong to a jj repository`() {
        val gitOwnedA = change("git-repo/a.kt", inJujutsuRepo = false)
        val gitOwnedB = change("git-repo/b.kt", inJujutsuRepo = false)

        listOf(gitOwnedA, gitOwnedB).filterInJujutsuRepo(project) shouldBe emptyList()
    }

    @Test
    fun `keeps all changes when all belong to a jj repository`() {
        val a = change("jj-repo/a.kt", inJujutsuRepo = true)
        val b = change("jj-repo/b.kt", inJujutsuRepo = true)

        listOf(a, b).filterInJujutsuRepo(project) shouldBe listOf(a, b)
    }

    @Test
    fun `empty input yields empty output`() {
        emptyList<Change>().filterInJujutsuRepo(project) shouldBe emptyList()
    }
}
