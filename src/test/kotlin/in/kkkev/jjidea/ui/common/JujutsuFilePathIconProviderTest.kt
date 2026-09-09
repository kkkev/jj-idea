package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.JujutsuStateModel
import `in`.kkkev.jjidea.util.NotifiableState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * jj-idea-b65g: [JujutsuFilePathIconProvider] used to resolve the repo via
 * `possibleJujutsuRepositoryFor`, which calls `VcsUtil.getVcsRootFor` - a blocking read action -
 * from this class's `getIcon`, called by `ChangesBrowserNodeRenderer` on the EDT for every node
 * painted (a 58s EDT freeze was observed in exactly this call chain). It now matches a repo root
 * by exact path against the already-cached repo list instead, so these tests pin both the
 * behaviour and the "no VcsUtil-style repo lookup" property by never stubbing one.
 */
class JujutsuFilePathIconProviderTest {
    private val provider = JujutsuFilePathIconProvider()

    private fun projectWith(vararg repos: JujutsuRepository): Project {
        val initialisedRepositories = mockk<NotifiableState<Map<VirtualFile, JujutsuRepository>>> {
            every { value } returns repos.associateBy { it.directory }
        }
        val stateModel = mockk<JujutsuStateModel> {
            every { this@mockk.initialisedRepositories } returns initialisedRepositories
        }
        return mockk { every { getService(JujutsuStateModel::class.java) } returns stateModel }
    }

    private fun repoAt(path: String): JujutsuRepository {
        val directory = mockk<VirtualFile> { every { this@mockk.path } returns path }
        return mockk {
            every { this@mockk.directory } returns directory
            every { this@mockk.project } returns mockk()
        }
    }

    @Test
    fun `returns the repo's icon for an exact repository root match`() {
        val repo = repoAt("/repos/one")
        val project = projectWith(repo)

        provider.getIcon(LocalFilePath("/repos/one", true), project) shouldBe RepositoryIcons[repo]
    }

    @Test
    fun `returns null for a path that isn't a repository root, even inside one`() {
        val repo = repoAt("/repos/one")
        val project = projectWith(repo)

        provider.getIcon(LocalFilePath("/repos/one/src/Main.kt", false), project) shouldBe null
    }

    @Test
    fun `returns null when project is null`() {
        provider.getIcon(LocalFilePath("/repos/one", true), null) shouldBe null
    }

    @Test
    fun `never touches VcsUtil-style root resolution - only reads the cached repository list`() {
        val repo = repoAt("/repos/one")
        val initialisedRepositories = mockk<NotifiableState<Map<VirtualFile, JujutsuRepository>>> {
            every { value } returns mapOf(repo.directory to repo)
        }
        val stateModel = mockk<JujutsuStateModel> {
            every { this@mockk.initialisedRepositories } returns
                initialisedRepositories
        }
        val project = mockk<Project> { every { getService(JujutsuStateModel::class.java) } returns stateModel }

        provider.getIcon(LocalFilePath("/repos/one", true), project)

        // .value is the non-blocking cached read (jj-idea-b65g); called exactly once per getIcon.
        verify(exactly = 1) { initialisedRepositories.value }
    }
}
