package `in`.kkkev.jjidea.ui.common

import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** Tests for [partitionByCurrentRepo], using a plain lambda in place of the real repo resolution. */
class PartitionByCurrentRepoTest {
    private val repoA = mockk<JujutsuRepository>()
    private val repoB = mockk<JujutsuRepository>()

    @Test
    fun `currentRepo null means nothing is demoted, regardless of how many repos are represented`() {
        val items = listOf("a1", "a2", "b1")
        val repoFor = mapOf("a1" to repoA, "a2" to repoA, "b1" to repoB)

        val (current, other) = partitionByCurrentRepo(items, currentRepo = null) { repoFor.getValue(it) }

        current shouldBe items
        other shouldBe emptyList()
    }

    @Test
    fun `items belonging to currentRepo are kept, others are demoted`() {
        val items = listOf("a1", "a2", "b1", "b2")
        val repoFor = mapOf("a1" to repoA, "a2" to repoA, "b1" to repoB, "b2" to repoB)

        val (current, other) = partitionByCurrentRepo(items, currentRepo = repoA) { repoFor.getValue(it) }

        current shouldBe listOf("a1", "a2")
        other shouldBe listOf("b1", "b2")
    }

    @Test
    fun `every item belonging to currentRepo leaves the demoted half empty`() {
        val items = listOf("a1", "a2")
        val repoFor = mapOf("a1" to repoA, "a2" to repoA)

        val (current, other) = partitionByCurrentRepo(items, currentRepo = repoA) { repoFor.getValue(it) }

        current shouldBe items
        other shouldBe emptyList()
    }

    @Test
    fun `no item belonging to currentRepo demotes everything`() {
        val items = listOf("b1", "b2")
        val repoFor = mapOf("b1" to repoB, "b2" to repoB)

        val (current, other) = partitionByCurrentRepo(items, currentRepo = repoA) { repoFor.getValue(it) }

        current shouldBe emptyList()
        other shouldBe items
    }

    @Test
    fun `an item resolving to no repo at all is demoted, not kept`() {
        val items = listOf("a1", "unowned")
        val repoFor = mapOf("a1" to repoA)

        val (current, other) = partitionByCurrentRepo(items, currentRepo = repoA) { repoFor[it] }

        current shouldBe listOf("a1")
        other shouldBe listOf("unowned")
    }

    @Test
    fun `empty input yields two empty lists`() {
        partitionByCurrentRepo(emptyList<String>(), currentRepo = repoA) { null } shouldBe
            (emptyList<String>() to emptyList())
    }
}
