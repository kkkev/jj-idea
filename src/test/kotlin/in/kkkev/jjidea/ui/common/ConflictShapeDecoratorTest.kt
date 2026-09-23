package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.SimpleColoredComponent
import `in`.kkkev.jjidea.jj.conflict.ConflictInfo
import `in`.kkkev.jjidea.jj.conflict.conflictRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Covers the GitHub #66 shape-text row decoration: a conflicted row in the "Merge Conflicts" node
 * shows jj's own `jj resolve --list` shape text (e.g. "2-sided conflict including 1 deletion"),
 * so a user picking which file to resolve next can see modify/delete conflicts at a glance.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class ConflictShapeDecoratorTest {
    private val project = projectFixture()

    @Test
    fun `appends jj's shape text when the registry knows about the file`() {
        val repoDir = virtualFile("/repo")
        val file = virtualFile("/repo/foo.txt")
        project.get().conflictRegistry.replace(
            repoDir,
            listOf(
                ConflictInfo("foo.txt", sides = 2, deletions = 1, description = "2-sided conflict including 1 deletion")
            )
        )
        val component = SimpleColoredComponent()

        ConflictShapeDecorator(project.get()).decorate(change(file), component, false)

        component.toString() shouldContain "2-sided conflict including 1 deletion"
    }

    @Test
    fun `appends nothing when the registry has no entry for the file`() {
        val file = virtualFile("/repo/untracked.txt")
        val component = SimpleColoredComponent()

        ConflictShapeDecorator(project.get()).decorate(change(file), component, false)

        component.fragmentCount shouldBe 0
    }

    private fun virtualFile(path: String) = mockk<VirtualFile> { every { this@mockk.path } returns path }

    private fun change(virtualFile: VirtualFile): Change {
        val filePath = mockk<FilePath> { every { this@mockk.virtualFile } returns virtualFile }
        val revision = mockk<ContentRevision> { every { file } returns filePath }
        return mockk<Change> {
            every { afterRevision } returns revision
            every { beforeRevision } returns null
        }
    }
}
