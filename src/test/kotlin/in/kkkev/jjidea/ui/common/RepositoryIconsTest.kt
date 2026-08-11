package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * jj-idea-o46e: [RepositoryIcons] is an application-level object, so its cache must not be keyed
 * by anything that retains a [Project] - a [JujutsuRepository] key would pin every project it's
 * ever asked about for the life of the process, which LeakHunter caught as a leaked ProjectImpl
 * at platform-test teardown. The cache is keyed by directory path instead; these tests pin that
 * behaviour down by asserting on repository identity (`===`) rather than just value equality, so
 * a regression back to a repository-keyed cache would be visible even if it "looked" equal.
 */
class RepositoryIconsTest {
    private fun repoAt(path: String, project: Project = mockk()): JujutsuRepository {
        val directory = mockk<VirtualFile> { every { this@mockk.path } returns path }
        return mockk {
            every { this@mockk.project } returns project
            every { this@mockk.directory } returns directory
        }
    }

    @Test
    fun `repositories with the same directory path share the same cached icon instance`() {
        val a = repoAt("/repos/one", project = mockk())
        val b = repoAt("/repos/one", project = mockk())

        RepositoryIcons[a] shouldNotBe null
        RepositoryIcons[a] shouldBe RepositoryIcons[b]
    }

    @Test
    fun `repositories with different directory paths get different icons`() {
        val a = repoAt("/repos/two")
        val b = repoAt("/repos/three")

        RepositoryIcons[a] shouldNotBe RepositoryIcons[b]
    }
}
