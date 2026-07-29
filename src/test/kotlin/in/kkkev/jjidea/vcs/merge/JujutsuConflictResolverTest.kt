package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeProvider
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Regression coverage for GitHub #63: closing the merge tool without resolving must never
 * write anything back to the file, and must stop processing any remaining conflicted files.
 * See [JujutsuConflictResolver]'s class doc for why the platform's own merge dialog can't be
 * trusted with this (it hands the real file's Document to the merge tool as output, and on
 * IntelliJ 2026.2's default iterative flow, cancel no longer restores it).
 */
class JujutsuConflictResolverTest {
    private val project = mockk<Project>()
    private val mergeProvider = mockk<MergeProvider>(relaxed = true)

    private fun mergeData(current: String = "ours", original: String = "base", theirs: String = "theirs") =
        MergeData().also {
            it.CURRENT = current.toByteArray(Charsets.UTF_8)
            it.ORIGINAL = original.toByteArray(Charsets.UTF_8)
            it.LAST = theirs.toByteArray(Charsets.UTF_8)
        }

    private fun resolverWith(
        resolveOne: (VirtualFile, MergeData) -> ByteArray?,
        writeResolved: MutableMap<VirtualFile, ByteArray> = mutableMapOf()
    ) = JujutsuConflictResolver(
        project = project,
        mergeProvider = mergeProvider,
        resolveOne = resolveOne,
        writeResolved = { file, bytes -> writeResolved[file] = bytes }
    ) to writeResolved

    @Test
    fun `cancel on the only file - writes nothing and does not mark resolved`() {
        val file = mockk<VirtualFile>()
        every { mergeProvider.loadRevisions(file) } returns mergeData()

        val (resolver, written) = resolverWith(resolveOne = { _, _ -> null })

        resolver.resolve(listOf(file))

        written shouldBe emptyMap()
        verify(exactly = 0) { mergeProvider.conflictResolvedForFile(file) }
    }

    @Test
    fun `cancel on the first of two files - stops before the second file is touched`() {
        val file1 = mockk<VirtualFile>()
        val file2 = mockk<VirtualFile>()
        every { mergeProvider.loadRevisions(file1) } returns mergeData()
        every { mergeProvider.loadRevisions(file2) } returns mergeData()

        val (resolver, written) = resolverWith(resolveOne = { _, _ -> null })

        resolver.resolve(listOf(file1, file2))

        written shouldBe emptyMap()
        verify(exactly = 0) { mergeProvider.loadRevisions(file2) }
    }

    @Test
    fun `resolved - writes the resolved bytes and marks the file resolved`() {
        val file = mockk<VirtualFile>()
        every { mergeProvider.loadRevisions(file) } returns mergeData()
        val resolvedBytes = "merged content".toByteArray(Charsets.UTF_8)

        val (resolver, written) = resolverWith(resolveOne = { _, _ -> resolvedBytes })

        resolver.resolve(listOf(file))

        written shouldBe mapOf(file to resolvedBytes)
        verify(exactly = 1) { mergeProvider.conflictResolvedForFile(file) }
    }

    @Test
    fun `accept yours - writes CURRENT content`() {
        val file = mockk<VirtualFile>()
        every { mergeProvider.loadRevisions(file) } returns mergeData(current = "ours wins")

        val (resolver, written) = resolverWith(resolveOne = { _, data -> data.CURRENT })

        resolver.resolve(listOf(file))

        written[file]?.toString(Charsets.UTF_8) shouldBe "ours wins"
    }

    @Test
    fun `accept theirs - writes LAST content`() {
        val file = mockk<VirtualFile>()
        every { mergeProvider.loadRevisions(file) } returns mergeData(theirs = "theirs wins")

        val (resolver, written) = resolverWith(resolveOne = { _, data -> data.LAST })

        resolver.resolve(listOf(file))

        written[file]?.toString(Charsets.UTF_8) shouldBe "theirs wins"
    }

    @Test
    fun `already resolved - loadRevisions throws - file is skipped, later files still processed`() {
        val alreadyResolved = mockk<VirtualFile>()
        val stillConflicted = mockk<VirtualFile>()
        every { mergeProvider.loadRevisions(alreadyResolved) } throws VcsException("no conflict markers")
        every { mergeProvider.loadRevisions(stillConflicted) } returns mergeData()
        val resolvedBytes = "resolved".toByteArray(Charsets.UTF_8)

        val (resolver, written) = resolverWith(resolveOne = { _, _ -> resolvedBytes })

        resolver.resolve(listOf(alreadyResolved, stillConflicted))

        written shouldBe mapOf(stillConflicted to resolvedBytes)
        verify(exactly = 0) { mergeProvider.conflictResolvedForFile(alreadyResolved) }
        verify(exactly = 1) { mergeProvider.conflictResolvedForFile(stillConflicted) }
    }

    @Test
    fun `two files - both resolved - each written and marked resolved once`() {
        val file1 = mockk<VirtualFile>()
        val file2 = mockk<VirtualFile>()
        every { mergeProvider.loadRevisions(file1) } returns mergeData()
        every { mergeProvider.loadRevisions(file2) } returns mergeData()
        val bytes1 = "one".toByteArray(Charsets.UTF_8)
        val bytes2 = "two".toByteArray(Charsets.UTF_8)

        val (resolver, written) = resolverWith(
            resolveOne = { file, _ -> if (file === file1) bytes1 else bytes2 }
        )

        resolver.resolve(listOf(file1, file2))

        written shouldBe mapOf(file1 to bytes1, file2 to bytes2)
        verify(exactly = 1) { mergeProvider.conflictResolvedForFile(file1) }
        verify(exactly = 1) { mergeProvider.conflictResolvedForFile(file2) }
    }
}
