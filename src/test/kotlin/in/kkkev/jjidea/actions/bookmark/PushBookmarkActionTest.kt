package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.GitRemote
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PushBookmarkActionTest {
    @Nested
    inner class `pushAvailability` {
        @Test
        fun `disabled when the remote entry has nothing ahead`() {
            pushAvailability(Bookmark("main"), Bookmark("main@origin", aheadCount = 0)) shouldBe
                PushAvailability.UP_TO_DATE
        }

        @Test
        fun `enabled when the remote entry is ahead`() {
            pushAvailability(Bookmark("main"), Bookmark("main@origin", aheadCount = 2)) shouldBe
                PushAvailability.ENABLED
        }

        @Test
        fun `enabled when this remote has never seen the bookmark`() {
            pushAvailability(Bookmark("new-thing"), remoteBookmark = null) shouldBe PushAvailability.ENABLED
        }

        @Test
        fun `enabled for a deletion still present on this remote`() {
            pushAvailability(Bookmark("old", deleted = true), Bookmark("old@origin", aheadCount = 0)) shouldBe
                PushAvailability.ENABLED
        }

        @Test
        fun `disabled for a deletion this remote never had`() {
            pushAvailability(Bookmark("old", deleted = true), remoteBookmark = null) shouldBe
                PushAvailability.UP_TO_DATE
        }

        @Test
        fun `ignores the local bookmark's own aheadCount entirely (colocated @git contamination)`() {
            // A colocated repo's local bookmark is always in sync with its own automatic @git
            // remote, so its aggregate aheadCount is always 0 regardless of origin/github state.
            // Availability must be driven only by the specific remote entry passed in.
            pushAvailability(Bookmark("main", aheadCount = 0), Bookmark("main@origin", aheadCount = 3)) shouldBe
                PushAvailability.ENABLED
        }
    }

    @Nested
    inner class `action group` {
        private val repo = mockk<JujutsuRepository>(relaxed = true)
        private lateinit var presentation: Presentation
        private lateinit var event: AnActionEvent

        @BeforeEach
        fun setup() {
            presentation = Presentation()
            event = mockk(relaxed = true)
            every { event.presentation } returns presentation
        }

        private fun remotes(vararg names: String) = names.map { GitRemote(it, "https://example.com/$it.git") }

        @Test
        fun `hidden with no Git remotes`() {
            every { repo.cachedGitRemotes } returns emptyList()
            val group = pushBookmarkAction(repo, Bookmark("main"), emptyList())
            group.update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `transparent with one remote - up to date`() {
            every { repo.cachedGitRemotes } returns remotes("origin")
            val group = pushBookmarkAction(repo, Bookmark("main"), listOf(Bookmark("main@origin", aheadCount = 0)))
            group.update(event)
            group.isPopup shouldBe false
            val child = group.getChildren(event).single()
            child.update(event)
            presentation.isEnabled shouldBe false
            presentation.text shouldBe "Push 'main' to origin... (up to date)"
        }

        @Test
        fun `transparent with one remote - ahead`() {
            every { repo.cachedGitRemotes } returns remotes("origin")
            val group = pushBookmarkAction(repo, Bookmark("main"), listOf(Bookmark("main@origin", aheadCount = 1)))
            val child = group.getChildren(event).single()
            child.update(event)
            presentation.isEnabled shouldBe true
            presentation.text shouldBe "Push 'main' to origin..."
        }

        @Test
        fun `submenu with two remotes, each evaluated independently`() {
            every { repo.cachedGitRemotes } returns remotes("origin", "github")
            val group = pushBookmarkAction(
                repo,
                Bookmark("main"),
                listOf(Bookmark("main@origin", aheadCount = 0), Bookmark("main@github", aheadCount = 5))
            )
            group.update(event)
            group.isPopup shouldBe true
            presentation.text shouldBe "Push 'main' to"

            // With >1 remote the submenu gains a leading "push to all remotes" entry plus a
            // separator ahead of the per-remote actions (jj-idea-ndzp).
            val children = group.getChildren(event)
            children.size shouldBe 4

            val allRemotesPresentation = Presentation()
            val allRemotesEvent = mockk<AnActionEvent>(relaxed = true)
            every { allRemotesEvent.presentation } returns allRemotesPresentation
            children[0].update(allRemotesEvent)
            // github is ahead, so pushing to all remotes is enabled even though origin is up to date.
            allRemotesPresentation.isEnabled shouldBe true

            val originPresentation = Presentation()
            val originEvent = mockk<AnActionEvent>(relaxed = true)
            every { originEvent.presentation } returns originPresentation
            children[2].update(originEvent)
            originPresentation.isEnabled shouldBe false

            val githubPresentation = Presentation()
            val githubEvent = mockk<AnActionEvent>(relaxed = true)
            every { githubEvent.presentation } returns githubPresentation
            children[3].update(githubEvent)
            githubPresentation.isEnabled shouldBe true
        }

        @Test
        fun `push to all remotes is disabled when every remote is up to date`() {
            every { repo.cachedGitRemotes } returns remotes("origin", "github")
            val group = pushBookmarkAction(
                repo,
                Bookmark("main"),
                listOf(Bookmark("main@origin", aheadCount = 0), Bookmark("main@github", aheadCount = 0))
            )
            val allRemotes = group.getChildren(event).first()
            allRemotes.update(event)
            presentation.isEnabled shouldBe false
            presentation.text shouldBe "Push 'main' to all remotes (up to date)"
        }

        @Test
        fun `a remote never tracked for this bookmark is still enabled`() {
            every { repo.cachedGitRemotes } returns remotes("origin")
            val group = pushBookmarkAction(repo, Bookmark("new-thing"), emptyList())
            val child = group.getChildren(event).single()
            child.update(event)
            presentation.isEnabled shouldBe true
        }
    }
}
