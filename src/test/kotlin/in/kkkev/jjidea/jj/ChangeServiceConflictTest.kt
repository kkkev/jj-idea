package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.conflict.conflictRegistry
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Coverage for [ChangeService.conflictInfosFor] (reached via [ChangeService.loadChanges]): it must
 * reuse [in.kkkev.jjidea.jj.conflict.ConflictInfoParser] rather than the whitespace-splitting hand
 * parser it replaced (jj-idea-85h9), and populate [in.kkkev.jjidea.jj.conflict.JujutsuConflictRegistry]
 * for the revision being processed.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class ChangeServiceConflictTest {
    private val project = projectFixture()

    private fun virtualFile(path: String) = mockk<VirtualFile> { every { this@mockk.path } returns path }

    private fun entry(repo: JujutsuRepository, hasConflict: Boolean = true) = LogEntry(
        repo = repo,
        id = ChangeId("rev1"),
        commitId = CommitId(""),
        underlyingDescription = "",
        hasConflict = hasConflict
    )

    private fun repoStubbedFor(resolveListStdout: String?): JujutsuRepository {
        val repo = mockRepo(project.get())
        val directory = virtualFile("/repo")
        every { repo.directory } returns directory
        every { repo.logService.getFileChanges(any()) } returns Result.success(emptyList())
        every { repo.createContentRevision(any(), any<ContentLocator>()) } answers {
            mockk<ContentRevision>()
        }
        val result = if (resolveListStdout != null) {
            commandResult(0, resolveListStdout)
        } else {
            commandResult(1, "", "boom")
        }
        every { repo.commandExecutor.resolveList(any()) } returns result
        return repo
    }

    @Test
    fun `conflicted path containing a space is tagged MERGED_WITH_CONFLICTS`() {
        val repo = repoStubbedFor("path with space.txt 2-sided conflict")

        val changes = ChangeService.loadChanges(entry(repo))

        changes.map { it.fileStatus } shouldBe listOf(FileStatus.MERGED_WITH_CONFLICTS)
    }

    @Test
    fun `registry is populated for the revision with full shape`() {
        val repo = repoStubbedFor("foo.txt 2-sided conflict including 1 deletion")
        val entry = entry(repo)

        ChangeService.loadChanges(entry)

        val info = project.get().conflictRegistry.conflicts(repo.directory, entry.id)["foo.txt"]
        info?.isModifyDelete shouldBe true
    }

    @Test
    fun `failed resolveList leaves the registry untouched and yields no conflicted changes`() {
        val repo = repoStubbedFor(null)
        val entry = entry(repo)

        val changes = ChangeService.loadChanges(entry)

        changes shouldBe emptyList()
        project.get().conflictRegistry.conflicts(repo.directory, entry.id) shouldBe emptyMap()
    }
}
