package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [RootFilterSelection] (jj-idea-qcks, GitHub #96): the tri-state root filter's
 * included-wins-over-excluded predicate, including the acceptance criterion's two "repo not
 * in either set" branches - the case of a repository mapped after the filter was set (see
 * `JujutsuStateModel`'s live `VcsListener` reaction).
 */
class RootFilterSelectionTest {
    private val repoA = mockk<JujutsuRepository>()
    private val repoB = mockk<JujutsuRepository>()
    private val repoC = mockk<JujutsuRepository>()

    @Test
    fun `both empty shows everything`() {
        val selection = RootFilterSelection()
        selection.isActive shouldBe false
        selection.shows(repoA) shouldBe true
        selection.shows(repoB) shouldBe true
    }

    @Test
    fun `included non-empty shows only included roots`() {
        val selection = RootFilterSelection(included = setOf(repoA))
        selection.isActive shouldBe true
        selection.shows(repoA) shouldBe true
        selection.shows(repoB) shouldBe false
    }

    @Test
    fun `excluded non-empty shows everything but excluded roots`() {
        val selection = RootFilterSelection(excluded = setOf(repoA))
        selection.isActive shouldBe true
        selection.shows(repoA) shouldBe false
        selection.shows(repoB) shouldBe true
    }

    @Test
    fun `included wins when both are non-empty`() {
        val selection = RootFilterSelection(included = setOf(repoA), excluded = setOf(repoB))
        selection.shows(repoA) shouldBe true
        selection.shows(repoB) shouldBe false
        // repoC is in neither set - included wins, so it defaults to hidden.
        selection.shows(repoC) shouldBe false
    }

    @Test
    fun `a repo added mid-session defaults to hidden when the included set is non-empty`() {
        val selection = RootFilterSelection(included = setOf(repoA))
        selection.shows(repoC) shouldBe false
    }

    @Test
    fun `a repo added mid-session defaults to shown when only exclusions are active`() {
        val selection = RootFilterSelection(excluded = setOf(repoA))
        selection.shows(repoC) shouldBe true
    }
}
