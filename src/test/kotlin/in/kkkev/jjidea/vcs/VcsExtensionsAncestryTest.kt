package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.JujutsuStateModel
import `in`.kkkev.jjidea.util.NotifiableState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * [Project.jujutsuRepositoryByAncestry] (jj-idea-82fo follow-up) - exercises the real
 * [com.intellij.openapi.vfs.VfsUtilCore.isAncestor] parent-chain walk against mocked
 * [VirtualFile]s. That walk is pure structure (`.parent`/`.fileSystem`, no live `Application`
 * needed), which is exactly why this function leans on it instead of
 * [possibleJujutsuRepositoryFor]'s `VcsUtil.getVcsRootFor` - found in manual testing to return
 * null for a file under a repo [in.kkkev.jjidea.jj.JujutsuStateModel.initialisedRepositories]
 * already listed as initialised.
 */
class VcsExtensionsAncestryTest {
    private val fileSystem = mockk<VirtualFileSystem>()
    private val project = mockk<Project>()
    private val repo = mockk<JujutsuRepository>()

    private fun virtualFile(parent: VirtualFile?): VirtualFile {
        val file = mockk<VirtualFile>(relaxed = true)
        every { file.parent } returns parent
        every { file.fileSystem } returns fileSystem
        return file
    }

    private fun stubKnownRepos(repos: List<JujutsuRepository>) {
        val stateModel = mockk<JujutsuStateModel>()
        val initialisedRepositories = mockk<NotifiableState<Map<VirtualFile, JujutsuRepository>>>()
        every { initialisedRepositories.value } returns repos.associateBy { it.directory }
        every { stateModel.initialisedRepositories } returns initialisedRepositories
        every { project.getService(JujutsuStateModel::class.java) } returns stateModel
    }

    @Test
    fun `a file directly inside a known repo's directory resolves to that repo`() {
        val repoDir = virtualFile(parent = null)
        every { repo.directory } returns repoDir
        stubKnownRepos(listOf(repo))
        val file = virtualFile(parent = repoDir)

        project.jujutsuRepositoryByAncestry(file) shouldBe repo
    }

    @Test
    fun `a file several directories deep still resolves to the ancestor repo`() {
        val repoDir = virtualFile(parent = null)
        every { repo.directory } returns repoDir
        stubKnownRepos(listOf(repo))
        val subDir = virtualFile(parent = repoDir)
        val file = virtualFile(parent = subDir)

        project.jujutsuRepositoryByAncestry(file) shouldBe repo
    }

    @Test
    fun `a file outside every known repo resolves to null`() {
        val repoDir = virtualFile(parent = null)
        every { repo.directory } returns repoDir
        stubKnownRepos(listOf(repo))
        val unrelatedDir = virtualFile(parent = null)
        val file = virtualFile(parent = unrelatedDir)

        project.jujutsuRepositoryByAncestry(file) shouldBe null
    }

    @Test
    fun `no known repos at all resolves to null`() {
        stubKnownRepos(emptyList())
        val file = virtualFile(parent = virtualFile(parent = null))

        project.jujutsuRepositoryByAncestry(file) shouldBe null
    }
}
