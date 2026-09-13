package `in`.kkkev.jjidea.vcs.diff

import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.util.ThreeState
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import `in`.kkkev.jjidea.jj.conflict.ExtractedConflict
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Covers the four cases from GitHub #119 / jj-idea-ct7e at the new seam: the revision on the
 * [Change]'s after-side is the only thing that determines what the panes show, so each case below
 * is just a different disk-vs-revision content pairing.
 */
class JujutsuConflictDiffRequestProviderTest {
    private val project = mockk<Project>()
    private val repo = mockk<JujutsuRepository>()
    private val filePath = mockk<FilePath>()
    private val extractor = mockk<ConflictExtractor>()
    private val recordedContent = mutableListOf<String>()

    private val provider = JujutsuConflictDiffRequestProvider(
        extractor = extractor,
        repoFor = { _, _ -> repo },
        contentFor = { _, text, _ ->
            recordedContent += text
            mockk<DiffContent>()
        }
    )

    @BeforeEach
    fun setUpFilePathExtension() {
        // canCreate/process resolve Change.filePath via the real VcsExtensions.kt extension,
        // which falls through to VcsUtil.getFilePath and needs a live Application - stub the
        // extension itself instead, same trick JujutsuMergeProviderTest uses.
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        every { any<Project>().possibleJujutsuRepositoryFor(any<FilePath>()) } returns repo
        every { filePath.name } returns "file.txt"
    }

    @AfterEach
    fun tearDownFilePathExtension() = unmockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")

    private fun revision(content: String?, path: FilePath = filePath): ContentRevision =
        mockk<ContentRevision>().also {
            every { it.file } returns path
            every { it.content } returns content
        }

    private fun conflictedChange(before: ContentRevision?, after: ContentRevision?) =
        Change(before, after, FileStatus.MERGED_WITH_CONFLICTS)

    private fun extractedConflict(
        current: String = "ours",
        original: String = "base",
        theirs: String = "theirs",
        currentTitle: String? = null,
        lastTitle: String? = null
    ) = ExtractedConflict(
        mergeData = MergeData().also {
            it.CURRENT = current.toByteArray(Charsets.UTF_8)
            it.ORIGINAL = original.toByteArray(Charsets.UTF_8)
            it.LAST = theirs.toByteArray(Charsets.UTF_8)
        },
        currentTitle = currentTitle,
        lastTitle = lastTitle,
        currentIsJjSide1 = true
    )

    private fun presentableFor(change: Change): ChangeDiffRequestProducer =
        mockk<ChangeDiffRequestProducer>().also {
            every { it.project } returns project
            every { it.change } returns change
        }

    // -------------------------------------------------------------------------
    // canCreate
    // -------------------------------------------------------------------------

    @Test
    fun `canCreate - conflicted jj change - true`() {
        val change = conflictedChange(null, revision("content"))
        provider.canCreate(project, change) shouldBe true
    }

    @Test
    fun `canCreate - non-conflicted change - false`() {
        val change = Change(null, revision("content"))
        provider.canCreate(project, change) shouldBe false
    }

    @Test
    fun `canCreate - conflicted change with no after revision - false`() {
        val change = conflictedChange(revision("content"), null)
        provider.canCreate(project, change) shouldBe false
    }

    @Test
    fun `canCreate - conflicted change outside any jj repo - false`() {
        val p = JujutsuConflictDiffRequestProvider(extractor = extractor, repoFor = { _, _ -> null })
        val change = conflictedChange(null, revision("content"))
        p.canCreate(project, change) shouldBe false
    }

    @Test
    fun `canCreate - null project - false`() {
        val change = conflictedChange(null, revision("content"))
        provider.canCreate(null, change) shouldBe false
    }

    // -------------------------------------------------------------------------
    // process - GitHub #119's four cases
    // -------------------------------------------------------------------------

    @Test
    fun `process - working copy is the conflicted commit - shows its own conflict`() {
        // The after-revision IS the disk read here (CurrentContentRevision collapses to it) -
        // there's nothing else to distinguish, this is just the pre-existing working case.
        val after = revision("<<<<<<< disk markers")
        every { extractor.extract("<<<<<<< disk markers".toByteArray(Charsets.UTF_8)) } returns extractedConflict()

        val request = provider.process(presentableFor(conflictedChange(null, after)), mockk(), mockk())

        request.shouldBeInstanceOf<SimpleDiffRequest>()
        recordedContent shouldBe listOf("ours", "base", "theirs")
    }

    @Test
    fun `process - working copy inherited the conflict - uses the revision, not disk, content`() {
        // The clicked commit's own revision read returns different markers than whatever is
        // sitting on disk right now - assert the panes come from the former.
        val revisionMarkers = "<<<<<<< from jj file show -r <rev>"
        val diskMarkers = "<<<<<<< coincidentally on disk too"
        every { extractor.extract(revisionMarkers.toByteArray(Charsets.UTF_8)) } returns
            extractedConflict(current = "from-revision")
        every { extractor.extract(diskMarkers.toByteArray(Charsets.UTF_8)) } returns
            extractedConflict(current = "from-disk")

        val after = revision(revisionMarkers)
        provider.process(presentableFor(conflictedChange(null, after)), mockk(), mockk())

        recordedContent[0] shouldBe "from-revision"
    }

    @Test
    fun `process - clean sibling of the working copy - still produces the three panes`() {
        // This is the reported bug: today, nothing on disk is conflicted (a clean sibling), so
        // the old working-copy-only path throws "Could not extract conflict data". The fix reads
        // the clicked commit's own revision instead and never touches disk at all.
        val after = revision("<<<<<<< change A's own markers")
        every { extractor.extract("<<<<<<< change A's own markers".toByteArray(Charsets.UTF_8)) } returns
            extractedConflict(current = "change-a-content")

        val request = provider.process(presentableFor(conflictedChange(null, after)), mockk(), mockk())

        request.shouldBeInstanceOf<SimpleDiffRequest>()
        recordedContent[0] shouldBe "change-a-content"
    }

    @Test
    fun `process - working copy conflicted from an unrelated rebase - never reads disk`() {
        // The after-revision mock here has no wiring to any "disk" concept at all - it's the
        // only content source process() is given, so asserting the correct output already proves
        // disk was never consulted. Extra belbelt-and-braces: verify content() was read exactly
        // once (the after-revision), not twice (which would suggest a working-copy fallback).
        val after = revision("<<<<<<< change A's markers, unrelated to @'s own conflict")
        every {
            extractor.extract("<<<<<<< change A's markers, unrelated to @'s own conflict".toByteArray(Charsets.UTF_8))
        } returns extractedConflict(current = "change-a-content", lastTitle = "change A")

        val request = provider.process(presentableFor(conflictedChange(null, after)), mockk(), mockk())

        request.shouldBeInstanceOf<SimpleDiffRequest>()
        recordedContent[0] shouldBe "change-a-content"
        verify(exactly = 1) { after.content }
    }

    // -------------------------------------------------------------------------
    // Titles, ordering, fallback
    // -------------------------------------------------------------------------

    @Test
    fun `process - three contents in CURRENT, ORIGINAL, LAST order`() {
        val after = revision("markers")
        every { extractor.extract("markers".toByteArray(Charsets.UTF_8)) } returns
            extractedConflict(current = "c", original = "o", theirs = "l")

        provider.process(presentableFor(conflictedChange(null, after)), mockk(), mockk())

        recordedContent shouldBe listOf("c", "o", "l")
    }

    @Test
    fun `process - titles fall back to Side #1 - Side #2 when jj has no commit labels`() {
        val after = revision("markers")
        every { extractor.extract("markers".toByteArray(Charsets.UTF_8)) } returns
            extractedConflict(currentTitle = null, lastTitle = null)

        val request = provider.process(presentableFor(conflictedChange(null, after)), mockk(), mockk())
            as SimpleDiffRequest

        request.contentTitles[0] shouldBe "Side #1"
        request.contentTitles[2] shouldBe "Side #2"
    }

    @Test
    fun `process - titles use jj's own commit labels when present (GitHub #112 agreement)`() {
        val after = revision("markers")
        every { extractor.extract("markers".toByteArray(Charsets.UTF_8)) } returns
            extractedConflict(
                currentTitle = "my change (rebased revision)",
                lastTitle = "destination (rebase destination)"
            )

        val request = provider.process(presentableFor(conflictedChange(null, after)), mockk(), mockk())
            as SimpleDiffRequest

        request.contentTitles[0] shouldBe "my change (rebased revision)"
        request.contentTitles[2] shouldBe "destination (rebase destination)"
    }

    // Neither fallback test below calls into ChangeDiffRequestProducer.createSimpleRequest -
    // that method is private on some floor IDE versions despite being public on the compile
    // target (caught by verifyPlugin as an IllegalAccessError risk), so the fallback is built
    // directly from the Change's own revisions instead. See the fallback branch's own comment.

    @Test
    fun `process - extraction fails - falls back to a plain two-side request instead of throwing`() {
        val after = revision("no markers here")
        every { extractor.extract("no markers here".toByteArray(Charsets.UTF_8)) } returns null

        val request = provider.process(presentableFor(conflictedChange(null, after)), mockk(), mockk())
            as SimpleDiffRequest

        request.contents.size shouldBe 2
    }

    @Test
    fun `process - after revision has no content - falls back without throwing`() {
        val after = revision(null)

        val request = provider.process(presentableFor(conflictedChange(null, after)), mockk(), mockk())
            as SimpleDiffRequest

        request.contents.size shouldBe 2
    }

    // -------------------------------------------------------------------------
    // isEquals
    // -------------------------------------------------------------------------

    @Test
    fun `isEquals - always defers to the platform's default comparison`() {
        val c1 = conflictedChange(null, revision("a"))
        val c2 = conflictedChange(null, revision("b"))
        provider.isEquals(c1, c2) shouldBe ThreeState.UNSURE
    }
}
