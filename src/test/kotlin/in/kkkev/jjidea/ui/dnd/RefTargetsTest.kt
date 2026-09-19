package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.JujutsuStateModel
import `in`.kkkev.jjidea.jj.RepositoryReferences
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.TagItem
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.util.NotifiableState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * [bookmarkTargets]/[tagTargets] look up a chip's full target set from the loaded reference state
 * at drag start (jj-idea-bico) - this pins the lookup itself: found-by-name resolves to every
 * target, and a miss (stale hit-test, or the state hasn't loaded) falls back to the single id the
 * chip was drawn with, never throwing or returning an empty set.
 *
 * This is a single O(bookmarks-in-repo) scan run once per gesture from the bean/image provider
 * (`JujutsuLogTableDnD.dragPayloadAt`, `IconAwareHtmlPaneDnD.refDragPayload`) - never from
 * `resolveLive`/`dropTargetAt`, which only ever read the [DragPayload] the platform already
 * attached to the event, so no call here can turn into a per-mouse-move rescan the way
 * [DragContextScaleTest] guards against for [DragContext].
 */
class RefTargetsTest {
    private val idA = ChangeId("aaaaaaaa", "a")
    private val idB = ChangeId("bbbbbbbb", "b")

    private fun repoWith(refs: RepositoryReferences): JujutsuRepository {
        val project = mockk<Project>(relaxed = true)
        val stateModel = mockk<JujutsuStateModel>(relaxed = true)
        val references = mockk<NotifiableState<Map<JujutsuRepository, RepositoryReferences>>>(relaxed = true)
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.project } returns project
        every { project.stateModel } returns stateModel
        every { stateModel.references } returns references
        every { references.cachedValue } returns mapOf(repo to refs)
        return repo
    }

    @Test
    fun `bookmarkTargets resolves every target of a conflicted bookmark by name`() {
        val bookmark = Bookmark("main", conflict = true)
        val repo = repoWith(RepositoryReferences(bookmarks = listOf(BookmarkItem(bookmark, listOf(idA, idB)))))

        repo.bookmarkTargets(bookmark, fallback = idA) shouldBe setOf(idA, idB)
    }

    @Test
    fun `bookmarkTargets falls back to the single id when the bookmark is not found`() {
        val repo = repoWith(RepositoryReferences())

        repo.bookmarkTargets(Bookmark("main"), fallback = idA) shouldBe setOf(idA)
    }

    @Test
    fun `bookmarkTargets falls back when the state has no targets for a present bookmark`() {
        val bookmark = Bookmark("main", deleted = true)
        val repo = repoWith(RepositoryReferences(bookmarks = listOf(BookmarkItem(bookmark, emptyList()))))

        repo.bookmarkTargets(bookmark, fallback = idA) shouldBe setOf(idA)
    }

    @Test
    fun `tagTargets resolves every target of a conflicted tag by name`() {
        val tag = Tag("v1")
        val repo = repoWith(RepositoryReferences(tags = listOf(TagItem(tag, listOf(idA, idB)))))

        repo.tagTargets(tag, fallback = idA) shouldBe setOf(idA, idB)
    }

    @Test
    fun `tagTargets falls back to the single id when the tag is not found`() {
        val repo = repoWith(RepositoryReferences())

        repo.tagTargets(Tag("v1"), fallback = idA) shouldBe setOf(idA)
    }
}
