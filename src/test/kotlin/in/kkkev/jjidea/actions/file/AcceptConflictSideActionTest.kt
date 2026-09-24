package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vcs.merge.MergeSessionEx
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.vcs.merge.JujutsuMergeProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test

/**
 * Covers [acceptConflictSide], the bulk accept-a-side gesture behind
 * [AcceptConflictCurrentSideAction]/[AcceptConflictLastSideAction] (GitHub #66): it must reuse
 * [in.kkkev.jjidea.vcs.merge.JujutsuMergeProvider]'s [MergeSessionEx] contract - the exact same
 * per-file `:ours`/`:theirs` orientation and modify/delete-safe write-back the platform's own
 * `MultipleFileMergeDialog` buttons use - rather than reimplementing it, and must not touch the
 * EDT.
 */
class AcceptConflictSideActionTest {
    private val project = mockk<Project>()
    private val files = listOf(mockk<VirtualFile>(), mockk<VirtualFile>())

    @Test
    fun `accepts revisions then marks files resolved, via the merge session, off the EDT`() {
        val session = mockk<MergeSessionEx>(relaxed = true)
        val mergeProvider = mockk<JujutsuMergeProvider> { every { createMergeSession(files) } returns session }
        var ranInBackground = false

        acceptConflictSide(
            project,
            files,
            MergeSession.Resolution.AcceptedYours,
            mergeProviderFor = { mergeProvider },
            runInBackground = {
                ranInBackground = true
                it()
            }
        )

        ranInBackground shouldBe true
        verifyOrder {
            session.acceptFilesRevisions(files, MergeSession.Resolution.AcceptedYours)
            session.conflictResolvedForFiles(files, MergeSession.Resolution.AcceptedYours)
        }
    }

    @Test
    fun `empty file list is a no-op - never looks up a merge provider`() {
        var providerLookedUp = false

        acceptConflictSide(
            project,
            emptyList(),
            MergeSession.Resolution.AcceptedTheirs,
            mergeProviderFor = {
                providerLookedUp = true
                null
            }
        )

        providerLookedUp shouldBe false
    }

    @Test
    fun `no jj merge provider - no-op, no background work scheduled`() {
        var ranInBackground = false

        acceptConflictSide(
            project,
            files,
            MergeSession.Resolution.AcceptedYours,
            mergeProviderFor = { null },
            runInBackground = {
                ranInBackground = true
                it()
            }
        )

        ranInBackground shouldBe false
    }
}
