package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.merge.JujutsuMergeProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Covers [acceptConflictSide], the bulk accept-a-side gesture behind
 * [AcceptConflictCurrentSideAction]/[AcceptConflictLastSideAction] (GitHub #66): it must reuse
 * [in.kkkev.jjidea.vcs.merge.JujutsuMergeProvider]'s per-file tool orientation, batch files into
 * one undo-tracked `jj resolve` per (repo, tool) group (jj-idea-n6fz.2), and not touch the EDT.
 */
class AcceptConflictSideActionTest {
    private val project = mockk<Project>()
    private val files = listOf(mockk<VirtualFile>(), mockk<VirtualFile>(), mockk<VirtualFile>())

    private val repoA = mockk<JujutsuRepository>()
    private val repoB = mockk<JujutsuRepository>()
    private val ran = mockk<CommandExecutor.Command.WithRepo>(relaxed = true)

    private fun run(
        provider: JujutsuMergeProvider,
        input: List<VirtualFile>,
        calls: MutableList<Triple<JujutsuRepository, List<VirtualFile>, String>>
    ) = acceptConflictSide(
        project,
        input,
        MergeSession.Resolution.AcceptedYours,
        mergeProviderFor = { provider },
        runInBackground = { it() },
        commandFor = { repo, group, tool ->
            calls += Triple(repo, group, tool)
            ran
        }
    )

    @Test
    fun `same repo and tool - one resolve command covering every file`() {
        val many = List(50) { mockk<VirtualFile>() }
        val provider = mockk<JujutsuMergeProvider> {
            every { repositoryFor(any()) } returns repoA
            every { toolFor(any(), MergeSession.Resolution.AcceptedYours) } returns ":ours"
        }
        val calls = mutableListOf<Triple<JujutsuRepository, List<VirtualFile>, String>>()

        run(provider, many, calls)

        calls.size shouldBe 1
        calls.single().second shouldBe many
        calls.single().third shouldBe ":ours"
        verify(exactly = 1) { ran.executeAsync() }
    }

    @Test
    fun `mixed orientation and repos - one command per repo and tool`() {
        val provider = mockk<JujutsuMergeProvider> {
            every { repositoryFor(files[0]) } returns repoA
            every { repositoryFor(files[1]) } returns repoA
            every { repositoryFor(files[2]) } returns repoB
            every { toolFor(files[0], any()) } returns ":ours"
            every { toolFor(files[1], any()) } returns ":theirs"
            every { toolFor(files[2], any()) } returns ":ours"
        }
        val calls = mutableListOf<Triple<JujutsuRepository, List<VirtualFile>, String>>()

        run(provider, listOf(files[0], files[1], files[2]), calls)

        calls shouldBe listOf(
            Triple(repoA, listOf(files[0]), ":ours"),
            Triple(repoA, listOf(files[1]), ":theirs"),
            Triple(repoB, listOf(files[2]), ":ours")
        )
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
