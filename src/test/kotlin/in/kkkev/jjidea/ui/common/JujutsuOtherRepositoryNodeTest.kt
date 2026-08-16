package `in`.kkkev.jjidea.ui.common

import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** [JujutsuOtherRepositoryNode]'s own properties. Needs no `Project`, so this is a plain unit test. */
class JujutsuOtherRepositoryNodeTest {
    private val repo = mockk<JujutsuRepository> { every { displayName } returns "repo-b" }

    @Test
    fun `labeled with the repo's own display name`() {
        JujutsuOtherRepositoryNode(repo).textPresentation shouldBe "repo-b"
    }

    @Test
    fun `expanded by default - unlike the Other Repositories umbrella it sits under`() {
        // Once a user has already drilled into "Other Repositories" (itself collapsed by
        // default), there's no reason to make them expand each repo inside it again too.
        JujutsuOtherRepositoryNode(repo).shouldExpandByDefault() shouldBe true
    }
}
