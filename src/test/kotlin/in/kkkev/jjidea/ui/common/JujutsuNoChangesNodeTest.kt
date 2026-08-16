package `in`.kkkev.jjidea.ui.common

import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** [JujutsuNoChangesNode]'s own properties. Needs no `Project`, so this is a plain unit test. */
class JujutsuNoChangesNodeTest {
    private val repo = mockk<JujutsuRepository> { every { displayName } returns "my-repo" }

    @Test
    fun `text presentation is just the repo name (the qualifier is render-only)`() {
        JujutsuNoChangesNode(repo).textPresentation shouldBe "my-repo"
    }

    @Test
    fun `sorts before Other Repositories and after Conflicts`() {
        // Pins the actual value rather than referencing the platform's protected sort-weight
        // constants symbolically - see JujutsuOtherRepositoriesNodeTest's equivalent note.
        // CONFLICTS_SORT_WEIGHT=0 < 4 < UNVERSIONED_SORT_WEIGHT=9 (JujutsuOtherRepositoriesNode).
        JujutsuNoChangesNode(repo).sortWeight shouldBe 4
    }
}
