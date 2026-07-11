package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.filterInJujutsuRepo
import `in`.kkkev.jjidea.vcs.possibleVirtualFileFor
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the background-safe file-resolver helpers in ActionEventExtensions.
 *
 * These helpers exist to prevent `jj log` from running on the EDT.  [Project.filesFor]
 * and [Project.jujutsuFilesFor] MUST be called off the EDT; the key behaviour verified
 * here is that they delegate to [possibleVirtualFileFor] only when a raw file array is
 * not already available (i.e., when the event originated from a changes selection rather
 * than a VIRTUAL_FILE_ARRAY data key).
 */
class ActionEventExtensionsTest {
    private val project = mockk<Project>(relaxed = true)

    @BeforeEach
    fun setup() {
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt") // filter extension lives here too
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun virtualFile(name: String) = mockk<VirtualFile>(relaxed = true) {
        every { this@mockk.name } returns name
    }

    private fun fileAtVersion(name: String): FileAtVersion {
        val filePath = LocalFilePath("/project/$name", false)
        return FileAtVersion(filePath, ChangeId("abc123abc123", "abc1"))
    }

    private fun addedChange(name: String): FileChange = FileChange.Added(fileAtVersion(name))

    private fun deletedChange(name: String): FileChange = FileChange.Deleted(fileAtVersion(name))

    // ── filesFor ──────────────────────────────────────────────────────────────

    @Nested
    inner class `filesFor` {
        @Test
        fun `returns fileList as-is when provided — no jj log call`() {
            val vf1 = virtualFile("A.kt")
            val vf2 = virtualFile("B.kt")
            val result = project.filesFor(listOf(vf1, vf2), emptyList())
            result shouldBe listOf(vf1, vf2)
            // possibleVirtualFileFor must never be called on the EDT
            verify(exactly = 0) { project.possibleVirtualFileFor(any()) }
        }

        @Test
        fun `returns empty list when both fileList and changes are empty`() {
            val result = project.filesFor(null, emptyList())
            result shouldBe emptyList()
        }

        @Test
        fun `resolves from changes via possibleVirtualFileFor when fileList is null`() {
            val vf = virtualFile("Foo.kt")
            val change = addedChange("Foo.kt")
            every { project.possibleVirtualFileFor(change.after!!) } returns vf

            val result = project.filesFor(null, listOf(change))
            result shouldBe listOf(vf)
            verify(exactly = 1) { project.possibleVirtualFileFor(change.after!!) }
        }

        @Test
        fun `skips changes whose after is null (deleted files)`() {
            val deleted = deletedChange("Gone.kt")
            val result = project.filesFor(null, listOf(deleted))
            result shouldBe emptyList()
        }

        @Test
        fun `skips changes where possibleVirtualFileFor returns null`() {
            val change = addedChange("Missing.kt")
            every { project.possibleVirtualFileFor(change.after!!) } returns null

            val result = project.filesFor(null, listOf(change))
            result shouldBe emptyList()
        }

        @Test
        fun `fileList takes precedence over changes — changes not resolved`() {
            val vfFromArray = virtualFile("Array.kt")
            val change = addedChange("Change.kt")

            val result = project.filesFor(listOf(vfFromArray), listOf(change))
            result shouldBe listOf(vfFromArray)
            verify(exactly = 0) { project.possibleVirtualFileFor(any()) }
        }
    }

    // ── jujutsuFilesFor ───────────────────────────────────────────────────────

    @Nested
    inner class `jujutsuFilesFor` {
        @Test
        fun `returns files that pass filterInJujutsuRepo`() {
            val vf = virtualFile("Main.kt")
            // filterInJujutsuRepo is a List<VirtualFile> extension — mock it as a static
            every { listOf(vf).filterInJujutsuRepo(project) } returns listOf(vf)

            val result = project.jujutsuFilesFor(listOf(vf), emptyList(), null)
            result shouldBe listOf(vf)
        }

        @Test
        fun `falls back to focusedFile when filtered list is empty`() {
            val vfArray = virtualFile("NotInJj.kt")
            val focused = virtualFile("Focused.kt")

            // primary list resolves to something outside a jj root
            every { listOf(vfArray).filterInJujutsuRepo(project) } returns emptyList()
            // fallback list is accepted
            every { listOf(focused).filterInJujutsuRepo(project) } returns listOf(focused)

            val result = project.jujutsuFilesFor(listOf(vfArray), emptyList(), focused)
            result shouldBe listOf(focused)
        }

        @Test
        fun `returns empty list when both primary and fallback resolve to nothing`() {
            val vf = virtualFile("NotInJj.kt")
            every { listOf(vf).filterInJujutsuRepo(project) } returns emptyList()
            every { emptyList<VirtualFile>().filterInJujutsuRepo(project) } returns emptyList()

            val result = project.jujutsuFilesFor(listOf(vf), emptyList(), null)
            result shouldBe emptyList()
        }
    }

    // ── AnActionEvent.changes ────────────────────────────────────────────────
    // Regression coverage for GitHub #50 / jj-idea-mdi4: a foreign-VCS change (e.g. Git4Idea
    // in a colocated repo, or an unrelated VCS backend such as Piper) selected alongside jj
    // changes must be dropped, not converted — converting it throws `Not a Jujutsu revision`.

    @Nested
    inner class `changes` {
        private val event = mockk<AnActionEvent>()

        private fun jujutsuChange(path: String): Change {
            val revision = mockk<ContentRevision> {
                every { file } returns LocalFilePath(path, false)
                every { revisionNumber } returns ChangeIdRevisionNumber(ChangeId("abc123abc123", "abc1"))
            }
            return Change(null, revision)
        }

        private fun workingCopyChange(path: String) =
            Change(null, CurrentContentRevision(LocalFilePath(path, false)))

        private fun foreignChange(path: String): Change {
            val revision = mockk<ContentRevision> {
                every { file } returns LocalFilePath(path, false)
                every { revisionNumber } returns mockk() // not a JujutsuRevisionNumber
            }
            return Change(null, revision)
        }

        @Test
        fun `converts a Jujutsu change without throwing`() {
            every { event.getData(VcsDataKeys.SELECTED_CHANGES) } returns arrayOf(jujutsuChange("a.kt"))
            every { event.getData(VcsDataKeys.CHANGES) } returns null

            event.changes.map { it.filePath.name } shouldBe listOf("a.kt")
        }

        @Test
        fun `drops a foreign-VCS change instead of throwing`() {
            every { event.getData(VcsDataKeys.SELECTED_CHANGES) } returns arrayOf(foreignChange("Piper.kt"))
            every { event.getData(VcsDataKeys.CHANGES) } returns null

            event.changes shouldBe emptyList()
        }

        @Test
        fun `keeps Jujutsu and working-copy changes while dropping a foreign-VCS change in the same selection`() {
            every { event.getData(VcsDataKeys.SELECTED_CHANGES) } returns arrayOf(
                jujutsuChange("a.kt"),
                foreignChange("Piper.kt"),
                workingCopyChange("b.kt")
            )
            every { event.getData(VcsDataKeys.CHANGES) } returns null

            event.changes.map { it.filePath.name } shouldBe listOf("a.kt", "b.kt")
        }
    }
}
