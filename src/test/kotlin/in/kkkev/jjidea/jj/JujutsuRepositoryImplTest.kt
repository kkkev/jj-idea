package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.util.NotifiableState
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * [JujutsuRepositoryImpl.createContentRevision]'s [ChangeId] branch (jj-idea-q6vn): the working
 * copy's own change id is used as the "after" locator for `@` (see
 * `CliLogService.getFileChanges`), but its content is live, not a fixed `jj file show` snapshot —
 * it must resolve to [CurrentContentRevision] rather than [ContentLogEntryImpl], both so the diff
 * viewer reflects background edits and so [CurrentContentRevision]'s platform-provided path-based
 * equality is what the diff request cache keys on for `@` (vs. relying on [ContentLogEntryImpl]'s
 * value equality never being asked to match a genuinely stale snapshot).
 */
class JujutsuRepositoryImplTest {
    private val directoryPath = "/repo"
    private val filePath = LocalFilePath("/repo/src/Main.kt", false)

    private fun repoWithWorkingCopy(workingCopyId: ChangeId?): JujutsuRepositoryImpl {
        val directory = mockk<VirtualFile> {
            every { path } returns directoryPath
        }
        val workingCopies = mockk<NotifiableState<Map<String, LogEntry>>> {
            every { value } returns workingCopyId?.let {
                mapOf(
                    directoryPath to LogEntry(
                        repo = mockRepo,
                        id = it,
                        commitId = CommitId("commit-${it.full}"),
                        underlyingDescription = "",
                        isWorkingCopy = true
                    )
                )
            }.orEmpty()
        }
        val stateModel = mockk<JujutsuStateModel> {
            every { this@mockk.workingCopies } returns workingCopies
        }
        val project = mockk<Project> {
            every { getService(JujutsuStateModel::class.java) } returns stateModel
        }
        return JujutsuRepositoryImpl(project, directory, "test-repo")
    }

    @Test
    fun `working copy's own change id resolves to CurrentContentRevision`() {
        val workingCopyId = ChangeId("wc", "wc", null)
        val repo = repoWithWorkingCopy(workingCopyId)

        val revision = repo.createContentRevision(filePath, workingCopyId)

        revision.shouldBeInstanceOf<CurrentContentRevision>()
    }

    @Test
    fun `a historical change id resolves to ContentLogEntryImpl`() {
        val workingCopyId = ChangeId("wc", "wc", null)
        val repo = repoWithWorkingCopy(workingCopyId)

        val revision = repo.createContentRevision(filePath, ChangeId("historical", "historical", null))

        revision.shouldBeInstanceOf<ContentLogEntryImpl>()
    }

    @Test
    fun `a change id resolves to ContentLogEntryImpl when the working copy is not yet known`() {
        val repo = repoWithWorkingCopy(workingCopyId = null)

        val revision = repo.createContentRevision(filePath, ChangeId("anything", "anything", null))

        revision.shouldBeInstanceOf<ContentLogEntryImpl>()
    }
}
