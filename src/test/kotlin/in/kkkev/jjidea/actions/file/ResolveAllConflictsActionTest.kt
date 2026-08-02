package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.actions.change.hasWorkingCopyConflicts
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.filterInJujutsuRepo
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [in.kkkev.jjidea.actions.change.hasWorkingCopyConflicts] backs the working-copy toolbar's
 * "Resolve All Conflicts" button (GitHub #56); it must stay a pure function of whether the
 * working copy has conflicted files - unlike the tree's selection-scoped
 * `Jujutsu.ResolveSelectedConflicts`, it must NOT depend on tree/editor selection, since
 * [ResolveAllConflictsAction] is bound to the toolbar (`targetComponent = changesTree`) and a
 * non-conflicted selection would otherwise hide it.
 */
class ResolveAllConflictsActionTest {
    private val project = mockk<Project>()
    private val repo = mockk<JujutsuRepository>()
    private val changeListManager = mockk<ChangeListManager>()
    private val vcs = mockk<JujutsuVcs>()

    @BeforeEach
    fun setup() {
        mockkStatic(ChangeListManager::class)
        every { ChangeListManager.getInstance(project) } returns changeListManager

        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        every { any<Iterable<Change>>().filterInJujutsuRepo(any()) } answers { callOriginal() }
        every { project.possibleJujutsuVcs } returns vcs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun conflictedChange(): Change {
        val virtualFile = mockk<VirtualFile>()
        val filePath = mockk<FilePath> {
            every { getVirtualFile() } returns virtualFile
        }
        val revision = mockk<ContentRevision> {
            every { file } returns filePath
        }
        every { project.possibleJujutsuRepositoryFor(filePath) } returns repo
        return Change(null, revision, FileStatus.MERGED_WITH_CONFLICTS)
    }

    @Test
    fun `false when the working copy has no conflicts`() {
        every { changeListManager.allChanges } returns emptyList()

        hasWorkingCopyConflicts(project) shouldBe false
    }

    @Test
    fun `true when the working copy has conflicts`() {
        every { changeListManager.allChanges } returns listOf(conflictedChange())

        hasWorkingCopyConflicts(project) shouldBe true
    }

    @Test
    fun `false when there's no jj vcs, even with conflicted-looking changes`() {
        every { project.possibleJujutsuVcs } returns null
        every { changeListManager.allChanges } returns listOf(conflictedChange())

        hasWorkingCopyConflicts(project) shouldBe false
    }
}
