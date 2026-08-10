package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Regression tests for [CommitGraphBuilder] with entries from more than one repository
 * (jj-idea-1ra9). jj's root commit shares the identical (synthetic) change id "zzzzzzzz..." in
 * every repository; the graph must key nodes/edges by the repo-scoped [ChangeKey], not the bare
 * [ChangeId], so two repos' roots never collapse into one node or get a connector line drawn
 * between them.
 */
class CommitGraphBuilderRepoScopingTest {
    private val repoA = mockk<JujutsuRepository>()
    private val repoB = mockk<JujutsuRepository>()

    private fun entry(repo: JujutsuRepository, changeId: String, parentIds: List<String> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "commit $changeId",
        parentIdentifiers = parentIds.map {
            LogEntry.Identifiers(ChangeId(it, it, null), CommitId("0".repeat(40)))
        }
    )

    @Test
    fun `two repos' roots sharing a change id produce two distinct graph nodes`() {
        val rootA = entry(repoA, "zzzzzzzz")
        val rootB = entry(repoB, "zzzzzzzz")

        val graph = CommitGraphBuilder().buildGraph(listOf(rootA, rootB))

        graph shouldHaveSize 2
        graph shouldContainKey ChangeKey(repoA, rootA.id)
        graph shouldContainKey ChangeKey(repoB, rootB.id)
    }

    @Test
    fun `a repo's commit is not treated as a child of another repo's same-id parent`() {
        // repoA: child -> zzzzzzzz (its own root). repoB: an unrelated root with the same id.
        val rootA = entry(repoA, "zzzzzzzz")
        val childA = entry(repoA, "aaa111", parentIds = listOf("zzzzzzzz"))
        val rootB = entry(repoB, "zzzzzzzz")

        val graph = CommitGraphBuilder().buildGraph(listOf(childA, rootA, rootB))

        val childNode = graph.getValue(ChangeKey(repoA, childA.id))
        // The only passthrough/parent-lane bookkeeping childA can have points at repoA's root -
        // it must never resolve to repoB's root by bare id collision.
        childNode.passthroughLanes.keys.forEach { it.repo shouldBe repoA }

        val rootBNode = graph.getValue(ChangeKey(repoB, rootB.id))
        rootBNode.childLanes.shouldBeEmpty()
    }
}
