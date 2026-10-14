package `in`.kkkev.jjidea.ui.squash

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SquashSimulatorTest {
    private lateinit var repo: JujutsuRepository

    @BeforeEach
    fun setup() {
        repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns mockk()
            every { displayName } returns "repo"
        }
    }

    private fun entry(id: String, parents: List<String> = emptyList(), isWorkingCopy: Boolean = false) = LogEntry(
        repo = repo,
        id = ChangeId(id, id.take(2), null),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = "desc $id",
        bookmarks = emptyList(),
        parentIds = parents.map { ChangeId(it, it.take(2), null) },
        isWorkingCopy = isWorkingCopy
    )

    private fun ids(entries: List<LogEntry>) = entries.map { it.id.full }

    private fun parentsOf(result: SquashSimulation, id: String) =
        result.entries.single { it.id.full == id }.parentIds.map { it.full }

    @Test
    fun `source into parent is abandoned and its child re-parented`() {
        // child -> src -> dest -> base
        val base = entry("base")
        val dest = entry("dest", listOf("base"))
        val src = entry("src", listOf("dest"))
        val child = entry("child", listOf("src"))

        val result = SquashSimulator.simulate(listOf(child, src, dest, base), listOf(src), dest, abandonSources = true)

        ids(result.entries) shouldContainExactly listOf("child", "dest", "base")
        parentsOf(result, "child") shouldContainExactly listOf("dest")
        result.abandonedIds shouldBe setOf(src.id)
        result.sourceIds shouldBe emptySet()
        result.destinationId shouldBe dest.id
    }

    @Test
    fun `source into ancestor re-parents the source's child onto the source's parent`() {
        // child -> src -> mid -> dest
        val dest = entry("dest")
        val mid = entry("mid", listOf("dest"))
        val src = entry("src", listOf("mid"))
        val child = entry("child", listOf("src"))

        val result = SquashSimulator.simulate(listOf(child, src, mid, dest), listOf(src), dest, abandonSources = true)

        ids(result.entries) shouldContainExactly listOf("child", "mid", "dest")
        parentsOf(result, "child") shouldContainExactly listOf("mid")
    }

    @Test
    fun `source into sibling branch leaves the sibling in place`() {
        // src -> base <- dest
        val base = entry("base")
        val dest = entry("dest", listOf("base"))
        val src = entry("src", listOf("base"))

        val result = SquashSimulator.simulate(listOf(src, dest, base), listOf(src), dest, abandonSources = true)

        ids(result.entries) shouldContainExactlyInAnyOrder listOf("dest", "base")
        parentsOf(result, "dest") shouldContainExactly listOf("base")
    }

    @Test
    fun `multiple chained sources collapse and the child lands on the surviving parent`() {
        // child -> s1 -> s2 -> base ; dest elsewhere
        val base = entry("base")
        val dest = entry("dest", listOf("base"))
        val s2 = entry("s2", listOf("base"))
        val s1 = entry("s1", listOf("s2"))
        val child = entry("child", listOf("s1"))

        val result = SquashSimulator.simulate(
            listOf(child, s1, s2, dest, base),
            listOf(s1, s2),
            dest,
            abandonSources = true
        )

        ids(result.entries) shouldContainExactlyInAnyOrder listOf("child", "dest", "base")
        parentsOf(result, "child") shouldContainExactly listOf("base")
        result.abandonedIds shouldBe setOf(s1.id, s2.id)
    }

    @Test
    fun `kept sources stay in the graph and are marked as sources`() {
        val dest = entry("dest")
        val src = entry("src", listOf("dest"))
        val child = entry("child", listOf("src"))

        val result = SquashSimulator.simulate(listOf(child, src, dest), listOf(src), dest, abandonSources = false)

        ids(result.entries) shouldContainExactly listOf("child", "src", "dest")
        parentsOf(result, "child") shouldContainExactly listOf("src")
        result.sourceIds shouldBe setOf(src.id)
        result.abandonedIds shouldBe emptySet()
    }

    @Test
    fun `abandoned merge source gives its child both parents`() {
        val a = entry("a")
        val b = entry("b")
        val dest = entry("dest", listOf("a"))
        val merge = entry("merge", listOf("a", "b"))
        val child = entry("child", listOf("merge"))

        val result = SquashSimulator.simulate(listOf(child, merge, dest, b, a), listOf(merge), dest, true)

        parentsOf(result, "child") shouldContainExactlyInAnyOrder listOf("a", "b")
    }

    @Test
    fun `every child of an abandoned source is re-parented`() {
        val dest = entry("dest")
        val src = entry("src", listOf("dest"))
        val c1 = entry("c1", listOf("src"))
        val c2 = entry("c2", listOf("src"))

        val result = SquashSimulator.simulate(listOf(c1, c2, src, dest), listOf(src), dest, abandonSources = true)

        parentsOf(result, "c1") shouldContainExactly listOf("dest")
        parentsOf(result, "c2") shouldContainExactly listOf("dest")
    }

    @Test
    fun `abandoning the working copy moves it to the destination`() {
        val dest = entry("dest")
        val wc = entry("wc", listOf("dest"), isWorkingCopy = true)

        val result = SquashSimulator.simulate(listOf(wc, dest), listOf(wc), dest, abandonSources = true)

        result.entries.single().isWorkingCopy shouldBe true
        result.entries.single().id shouldBe dest.id
    }

    @Test
    fun `keeping the working copy source leaves it the working copy`() {
        val dest = entry("dest")
        val wc = entry("wc", listOf("dest"), isWorkingCopy = true)

        val result = SquashSimulator.simulate(listOf(wc, dest), listOf(wc), dest, abandonSources = false)

        result.entries.single { it.id == wc.id }.isWorkingCopy shouldBe true
        result.entries.single { it.id == dest.id }.isWorkingCopy shouldBe false
    }

    @Test
    fun `no destination or no sources leaves the graph untouched`() {
        val dest = entry("dest")
        val src = entry("src", listOf("dest"))
        val all = listOf(src, dest)

        SquashSimulator.simulate(all, listOf(src), null, true).entries shouldBe all
        SquashSimulator.simulate(all, emptyList(), dest, true).entries shouldBe all
    }
}
