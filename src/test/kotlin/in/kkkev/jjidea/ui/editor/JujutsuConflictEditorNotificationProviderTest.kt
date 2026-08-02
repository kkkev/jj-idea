package `in`.kkkev.jjidea.ui.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [JujutsuConflictEditorNotificationProvider] should show its banner exactly when a file is both
 * jj-tracked and [FileStatus.MERGED_WITH_CONFLICTS] - never for a foreign-VCS conflict (e.g. a
 * co-located Git root), and never for a jj file that merely has other, non-conflict changes.
 */
class JujutsuConflictEditorNotificationProviderTest {
    private val project = mockk<Project>()
    private val file = mockk<VirtualFile>()
    private val repo = mockk<JujutsuRepository>()
    private val changeListManager = mockk<ChangeListManager>()

    @BeforeEach
    fun setup() {
        mockkStatic(ChangeListManager::class)
        every { ChangeListManager.getInstance(project) } returns changeListManager
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun change(status: FileStatus): Change = mockk { every { fileStatus } returns status }

    @Test
    fun `shows the banner for a conflicted jj-tracked file`() {
        every { project.possibleJujutsuRepositoryFor(file) } returns repo
        every { changeListManager.getChange(file) } returns change(FileStatus.MERGED_WITH_CONFLICTS)

        JujutsuConflictEditorNotificationProvider().collectNotificationData(project, file).shouldNotBeNull()
    }

    @Test
    fun `no banner for a jj-tracked file that is merely modified`() {
        every { project.possibleJujutsuRepositoryFor(file) } returns repo
        every { changeListManager.getChange(file) } returns change(FileStatus.MODIFIED)

        JujutsuConflictEditorNotificationProvider().collectNotificationData(project, file).shouldBeNull()
    }

    @Test
    fun `no banner for a jj-tracked file with no change at all`() {
        every { project.possibleJujutsuRepositoryFor(file) } returns repo
        every { changeListManager.getChange(file) } returns null

        JujutsuConflictEditorNotificationProvider().collectNotificationData(project, file).shouldBeNull()
    }

    @Test
    fun `no banner for a conflicted file outside any jj repo`() {
        every { project.possibleJujutsuRepositoryFor(file) } returns null

        JujutsuConflictEditorNotificationProvider().collectNotificationData(project, file).shouldBeNull()
    }
}
