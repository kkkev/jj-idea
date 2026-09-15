package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [buildChildrenIndex], the repo-scoped parent -> children index backing Move Up's
 * single-child resolution (jj-idea-owje, GitHub #93).
 */
class ChildrenIndexTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(repo: JujutsuRepository, changeId: String, parentIds: List<String> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "commit $changeId",
        parentIds = parentIds.map { ChangeId(it, it, null) }
    )

    @Test
    fun `a parent with one child indexes that one child`() {
        val parent = entry(repo, "p")
        val child = entry(repo, "c", parentIds = listOf("p"))

        val index = buildChildrenIndex(listOf(parent, child))

        index[ChangeKey(repo, parent.id)] shouldContainExactly listOf(child)
    }

    @Test
    fun `a parent with no children has no entry`() {
        val parent = entry(repo, "p")

        val index = buildChildrenIndex(listOf(parent))

        index[ChangeKey(repo, parent.id)] shouldBe null
    }

    @Test
    fun `a parent with two children indexes both`() {
        val parent = entry(repo, "p")
        val childA = entry(repo, "a", parentIds = listOf("p"))
        val childB = entry(repo, "b", parentIds = listOf("p"))

        val index = buildChildrenIndex(listOf(parent, childA, childB))

        index[ChangeKey(repo, parent.id)] shouldContainExactly listOf(childA, childB)
    }

    @Test
    fun `a merge commit is indexed under each of its parents`() {
        val parentA = entry(repo, "a")
        val parentB = entry(repo, "b")
        val merge = entry(repo, "m", parentIds = listOf("a", "b"))

        val index = buildChildrenIndex(listOf(parentA, parentB, merge))

        index[ChangeKey(repo, parentA.id)] shouldContainExactly listOf(merge)
        index[ChangeKey(repo, parentB.id)] shouldContainExactly listOf(merge)
    }

    @Nested
    inner class MultiRepo {
        private val repoA = mockk<JujutsuRepository>()
        private val repoB = mockk<JujutsuRepository>()

        @Test
        fun `two repos' roots sharing a change id keep independent children`() {
            // jj's root commit shares the identical synthetic change id "zzzzzzzz..." in every
            // repository (jj-idea-1ra9) - the index must key by repo-scoped ChangeKey, not the
            // bare ChangeId, or repoB's child would appear to also be repoA's root's child.
            val rootA = entry(repoA, "zzzzzzzz")
            val childA = entry(repoA, "aaa111", parentIds = listOf("zzzzzzzz"))
            val rootB = entry(repoB, "zzzzzzzz")

            val index = buildChildrenIndex(listOf(rootA, childA, rootB))

            index[ChangeKey(repoA, rootA.id)] shouldContainExactly listOf(childA)
            index[ChangeKey(repoB, rootB.id)] shouldBe null
        }
    }
}
