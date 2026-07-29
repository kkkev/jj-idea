package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.fileAt
import `in`.kkkev.jjidea.jj.fileAtWorkingCopy
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class DiffRequestBuilderTest {
    private val repo = mockk<JujutsuRepository> {
        every { createContentRevision(any<FileAtVersion>()) } answers {
            val fileAtVersion = firstArg<FileAtVersion>()
            if (fileAtVersion.isWorkingCopy) {
                CurrentContentRevision(fileAtVersion.filePath)
            } else {
                fakeRevision(fileAtVersion)
            }
        }
    }

    private fun fakeRevision(fileAtVersion: FileAtVersion): ContentRevision = mockk {
        every { file } returns fileAtVersion.filePath
    }

    private fun path(relativePath: String) = LocalFilePath(relativePath, false)

    private val oldChange = ChangeId("old", "old", null)

    @Test
    fun `added change has no before revision and a resolved after revision`() {
        val after = path("src/New.kt").fileAtWorkingCopy
        val change = FileChange.Added(after)

        val result = change.toChange(repo)

        result.beforeRevision.shouldBeNull()
        result.afterRevision.shouldBeInstanceOf<CurrentContentRevision>()
        result.afterRevision?.file shouldBe after.filePath
    }

    @Test
    fun `deleted change has no after revision and a resolved before revision`() {
        val before = path("src/Old.kt").fileAt(oldChange)
        val change = FileChange.Deleted(before)

        val result = change.toChange(repo)

        result.afterRevision.shouldBeNull()
        result.beforeRevision?.file shouldBe before.filePath
    }

    @Test
    fun `modified change resolves both sides, working-copy side is editable`() {
        val before = path("src/Main.kt").fileAt(oldChange)
        val after = path("src/Main.kt").fileAtWorkingCopy
        val change = FileChange.Modified(before, after)

        val result = change.toChange(repo)

        result.beforeRevision?.file shouldBe before.filePath
        result.afterRevision.shouldBeInstanceOf<CurrentContentRevision>()
        result.afterRevision?.file shouldBe after.filePath
    }

    @Test
    fun `renamed change resolves distinct before and after paths`() {
        val before = path("src/OldName.kt").fileAt(oldChange)
        val after = path("src/NewName.kt").fileAtWorkingCopy
        val change = FileChange.Renamed(before, after)

        val result = change.toChange(repo)

        result.beforeRevision?.file shouldBe before.filePath
        result.afterRevision?.file shouldBe after.filePath
    }
}
