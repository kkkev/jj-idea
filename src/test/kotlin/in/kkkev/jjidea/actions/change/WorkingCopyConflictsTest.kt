package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.filterInJujutsuRepo
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [workingCopyConflicts] backs both the [in.kkkev.jjidea.ui.common.JujutsuConflictsNode]'s
 * "Resolve" link and the toolbar's `Jujutsu.ResolveAllConflicts` action (GitHub #56); it must
 * return exactly the changes ChangeListManager reports as [FileStatus.MERGED_WITH_CONFLICTS] and
 * belonging to a jj repo - nothing from a co-located Git root, nothing merely modified.
 */
class WorkingCopyConflictsTest {
    private val project = mockk<Project>()
    private val repo = mockk<JujutsuRepository>()
    private val changeListManager = mockk<ChangeListManager>()

    @BeforeEach
    fun setup() {
        mockkStatic(ChangeListManager::class)
        every { ChangeListManager.getInstance(project) } returns changeListManager

        // possibleJujutsuRepositoryFor lives in VcsExtensions.kt alongside filterInJujutsuRepo
        // (which workingCopyConflicts delegates to for repo scoping). Mocking the whole file lets
        // us stub the repository lookup directly, per VcsExtensionsChangeFilterTest's pattern;
        // filterInJujutsuRepo itself keeps its real body via callOriginal().
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        every { any<Iterable<Change>>().filterInJujutsuRepo(any()) } answers { callOriginal() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun change(path: String, status: FileStatus, inJujutsuRepo: Boolean): Pair<Change, VirtualFile> {
        val virtualFile = mockk<VirtualFile>()
        val filePath = mockk<FilePath> {
            every { getVirtualFile() } returns virtualFile
        }
        val revision = mockk<ContentRevision> {
            every { file } returns filePath
        }
        every { project.possibleJujutsuRepositoryFor(filePath) } returns if (inJujutsuRepo) repo else null
        return Change(null, revision, status) to virtualFile
    }

    @Test
    fun `returns only jj-owned conflicted changes' virtual files`() {
        val (conflicted, conflictedFile) = change(
            "jj-repo/Parser.kt",
            FileStatus.MERGED_WITH_CONFLICTS,
            inJujutsuRepo = true
        )
        val (cleanJj, _) = change("jj-repo/Utils.kt", FileStatus.MODIFIED, inJujutsuRepo = true)
        val (conflictedGit, _) = change("git-repo/Other.kt", FileStatus.MERGED_WITH_CONFLICTS, inJujutsuRepo = false)
        every { changeListManager.allChanges } returns listOf(conflicted, cleanJj, conflictedGit)

        val result = workingCopyConflicts(project)

        result shouldBe listOf(conflictedFile)
    }

    @Test
    fun `no conflicts yields empty list`() {
        val (cleanJj, _) = change("jj-repo/Utils.kt", FileStatus.MODIFIED, inJujutsuRepo = true)
        every { changeListManager.allChanges } returns listOf(cleanJj)

        workingCopyConflicts(project) shouldBe emptyList()
    }

    @Test
    fun `no changes at all yields empty list`() {
        every { changeListManager.allChanges } returns emptyList()

        workingCopyConflicts(project) shouldBe emptyList()
    }
}
