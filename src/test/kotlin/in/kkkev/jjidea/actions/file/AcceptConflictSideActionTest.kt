package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.conflict.ExtractedConflict
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

    private fun conflict(current: String?, last: String?, currentIsJjSide1: Boolean) = ExtractedConflict(
        mergeData = mockk(),
        currentTitle = current,
        lastTitle = last,
        currentIsJjSide1 = currentIsJjSide1
    )

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
            every { loadConflict(any()) } returns conflict(null, null, true)
            every { toolFor(any<ExtractedConflict>(), MergeSession.Resolution.AcceptedYours) } returns ":ours"
        }
        val calls = mutableListOf<Triple<JujutsuRepository, List<VirtualFile>, String>>()

        run(provider, many, calls)

        calls.size shouldBe 1
        calls.single().second shouldBe many
        calls.single().third shouldBe ":ours"
        verify(exactly = 1) { ran.executeAsync() }
        // Scale: one conflict load per file (no double extraction for label + tool), one command.
        verify(exactly = 50) { provider.loadConflict(any()) }
    }

    @Test
    fun `mixed orientation and repos - one command per repo and tool`() {
        val provider = mockk<JujutsuMergeProvider> {
            every { repositoryFor(files[0]) } returns repoA
            every { repositoryFor(files[1]) } returns repoA
            every { repositoryFor(files[2]) } returns repoB
            val c0 = conflict(null, null, true)
            val c1 = conflict(null, null, false)
            val c2 = conflict(null, null, true)
            every { loadConflict(files[0]) } returns c0
            every { loadConflict(files[1]) } returns c1
            every { loadConflict(files[2]) } returns c2
            every { toolFor(c0, any()) } returns ":ours"
            every { toolFor(c1, any()) } returns ":theirs"
            every { toolFor(c2, any()) } returns ":ours"
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
    fun `files naming the same commits in swapped order - second file accepts the opposite side`() {
        val c0 = conflict("A", "B", true)
        val c1 = conflict("B", "A", true)
        val provider = mockk<JujutsuMergeProvider> {
            every { repositoryFor(any()) } returns repoA
            every { loadConflict(files[0]) } returns c0
            every { loadConflict(files[1]) } returns c1
        }
        // Mirror the real orientation logic so the test sees which side each file is told to accept.
        every { provider.toolFor(any<ExtractedConflict>(), any()) } answers {
            val c = firstArg<ExtractedConflict>()
            if (secondArg<MergeSession.Resolution>() == MergeSession.Resolution.AcceptedYours) {
                c.toolForCurrent
            } else {
                c.toolForLast
            }
        }
        val calls = mutableListOf<Triple<JujutsuRepository, List<VirtualFile>, String>>()

        run(provider, listOf(files[0], files[1]), calls)

        // Accepting "CURRENT" (A, per file 1): file 1 via its CURRENT, file 2 via its LAST.
        calls shouldBe listOf(
            Triple(repoA, listOf(files[0]), c0.toolForCurrent),
            Triple(repoA, listOf(files[1]), c1.toolForLast)
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
