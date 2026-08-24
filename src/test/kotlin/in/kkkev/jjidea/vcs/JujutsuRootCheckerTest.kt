package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepositoryHealth
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * jj-idea-9ife: [JujutsuRootChecker.validateRoot] backs the red-text "invalid mapping" rendering
 * in Settings > Version Control > Directory Mappings, so a repo [JujutsuStateModel][in.kkkev.jjidea.jj.JujutsuStateModel]
 * has found unreadable should show there too, not just in the Working Copy tool window.
 */
class JujutsuRootCheckerTest {
    private val repoPath = "/repo"
    private val checker = JujutsuRootChecker()
    private val file = mockk<VirtualFile> { every { path } returns repoPath }

    @AfterEach
    fun clearHealthCache() = JujutsuRepositoryHealth.markReadable(repoPath)

    @Test
    fun `a repo not known to be unreadable validates`() {
        checker.validateRoot(file).shouldBeTrue()
    }

    @Test
    fun `a repo JujutsuRepositoryHealth has marked unreadable fails validation`() {
        JujutsuRepositoryHealth.markUnreadable(repoPath, "broken store")

        checker.validateRoot(file).shouldBeFalse()
    }

    @Test
    fun `validation recovers once the repo is marked readable again`() {
        JujutsuRepositoryHealth.markUnreadable(repoPath, "broken store")
        JujutsuRepositoryHealth.markReadable(repoPath)

        checker.validateRoot(file).shouldBeTrue()
    }
}
