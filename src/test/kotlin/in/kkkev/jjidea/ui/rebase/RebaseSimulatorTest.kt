package `in`.kkkev.jjidea.ui.rebase

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RebaseSimulatorTest {
    private lateinit var repo: JujutsuRepository

    @BeforeEach
    fun setup() {
        repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns mockk()
            every { displayName } returns "repo"
        }
    }

    private fun entry(
        id: String,
        parents: List<String> = emptyList(),
        description: String = "desc $id"
    ) = LogEntry(
        repo = repo,
        id = ChangeId(id, id.take(2), null),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = description,
        bookmarks = emptyList(),
        parentIdentifiers = parents.map {
            LogEntry.Identifiers(ChangeId(it, it.take(2), null), CommitId("0000000000000000000000000000000000000000"))
        }
    )

    private fun ids(entries: List<LogEntry>) = entries.map { it.id.full }

    private fun parentIds(entry: LogEntry) = entry.parentIds.map { it.full }

    @Nested
    inner class `REVISION mode + ONTO` {
        @Test
        fun `single source entry is reparented to destination`() {
            // Graph: A -> B -> C
            val c = entry("C")
            val b = entry("B", listOf("C"))
            val a = entry("A", listOf("B"))
            val allEntries = listOf(a, b, c)

            val result = RebaseSimulator.simulate(
                allEntries,
                sourceEntries = listOf(b),
                destinationIds = setOf(c.id),
                sourceMode = RebaseSourceMode.REVISION,
                destinationMode = RebaseDestinationMode.ONTO
            )

            result.sourceIds shouldBe setOf(b.id)
            result.destinationIds shouldBe setOf(c.id)

            // B should now have C as parent (moved onto C)
            val movedB = result.entries.find { it.id == b.id }!!
            parentIds(movedB) shouldContainExactly listOf("C")

            // A should now point to B's original parent (C) since B was extracted
            val movedA = result.entries.find { it.id == a.id }!!
            parentIds(movedA) shouldContainExactly listOf("C")
        }
    }

    @Nested
    inner class `SOURCE mode + ONTO` {
        @Test
        fun `source and descendants are all moved`() {
            // Graph: A -> B -> C -> D
            val d = entry("D")
            val c = entry("C", listOf("D"))
            val b = entry("B", listOf("C"))
            val a = entry("A", listOf("B"))
            val allEntries = listOf(a, b, c, d)

            val result = RebaseSimulator.simulate(
                allEntries,
                sourceEntries = listOf(b),
                destinationIds = setOf(d.id),
                sourceMode = RebaseSourceMode.SOURCE,
                destinationMode = RebaseDestinationMode.ONTO
            )

            // B and A (descendant of B) should be moved
            result.sourceIds shouldContainExactlyInAnyOrder listOf(a.id, b.id)

            // B (root of moved set) should have D as parent
            val movedB = result.entries.find { it.id == b.id }!!
            parentIds(movedB) shouldContainExactly listOf("D")

            // A should still point to B (internal link preserved)
            val movedA = result.entries.find { it.id == a.id }!!
            parentIds(movedA) shouldContainExactly listOf("B")
        }
    }

    @Nested
    inner class `BRANCH mode + ONTO` {
        @Test
        fun `whole branch is moved`() {
            // Graph: A -> B -> C, D -> C (separate branch)
            val c = entry("C")
            val b = entry("B", listOf("C"))
            val a = entry("A", listOf("B"))
            val d = entry("D", listOf("C"))
            val e = entry("E") // unrelated
            val allEntries = listOf(a, b, c, d, e)

            val result = RebaseSimulator.simulate(
                allEntries,
                sourceEntries = listOf(a),
                destinationIds = setOf(e.id),
                sourceMode = RebaseSourceMode.BRANCH,
                destinationMode = RebaseDestinationMode.ONTO
            )

            // Branch rooted at C: C, B, A, D should all be moved
            result.sourceIds shouldContainExactlyInAnyOrder listOf(a.id, b.id, c.id, d.id)
        }
    }

    @Nested
    inner class `INSERT_AFTER` {
        @Test
        fun `moved entries inserted after destination`() {
            // Graph: A -> B -> C
            // Move A after C: A becomes child of C, B (former child of C) becomes child of A
            val c = entry("C")
            val b = entry("B", listOf("C"))
            val a = entry("A", listOf("B"))
            val allEntries = listOf(a, b, c)

            val result = RebaseSimulator.simulate(
                allEntries,
                sourceEntries = listOf(a),
                destinationIds = setOf(c.id),
                sourceMode = RebaseSourceMode.REVISION,
                destinationMode = RebaseDestinationMode.INSERT_AFTER
            )

            // A (moved) should have C as parent
            val movedA = result.entries.find { it.id == a.id }!!
            parentIds(movedA) shouldContainExactly listOf("C")

            // B (was child of C) should now point to A (the moved tip)
            val movedB = result.entries.find { it.id == b.id }!!
            parentIds(movedB) shouldContainExactly listOf("A")
        }
    }

    @Nested
    inner class `INSERT_BEFORE` {
        @Test
        fun `moved entries inserted before destination`() {
            // Graph: A -> B -> C
            // Move A before B: A becomes parent of B, B's old parent (C) becomes A's parent
            val c = entry("C")
            val b = entry("B", listOf("C"))
            val a = entry("A", listOf("B"))
            val allEntries = listOf(a, b, c)

            val result = RebaseSimulator.simulate(
                allEntries,
                sourceEntries = listOf(a),
                destinationIds = setOf(b.id),
                sourceMode = RebaseSourceMode.REVISION,
                destinationMode = RebaseDestinationMode.INSERT_BEFORE
            )

            // A (moved) should have C as parent (B's original parent)
            val movedA = result.entries.find { it.id == a.id }!!
            parentIds(movedA) shouldContainExactly listOf("C")

            // B should now point to A (the moved tip)
            val movedB = result.entries.find { it.id == b.id }!!
            parentIds(movedB) shouldContainExactly listOf("A")
        }
    }

    @Nested
    inner class `topological sort` {
        @Test
        fun `children appear before parents`() {
            val c = entry("C")
            val b = entry("B", listOf("C"))
            val a = entry("A", listOf("B"))
            // Input in wrong order: parent before child
            val shuffled = listOf(c, a, b)

            val sorted = RebaseSimulator.topologicalSort(shuffled)

            val sortedIds = ids(sorted)
            // A before B before C
            sortedIds.indexOf("A") shouldBe 0
            sortedIds.indexOf("B") shouldBe 1
            sortedIds.indexOf("C") shouldBe 2
        }

        @Test
        fun `single entry returns as-is`() {
            val a = entry("A")
            RebaseSimulator.topologicalSort(listOf(a)) shouldBe listOf(a)
        }

        @Test
        fun `empty list returns empty`() {
            RebaseSimulator.topologicalSort(emptyList()) shouldBe emptyList()
        }
    }

    @Nested
    inner class `collectDescendants` {
        @Test
        fun `collects transitive descendants`() {
            val d = entry("D")
            val c = entry("C", listOf("D"))
            val b = entry("B", listOf("C"))
            val a = entry("A", listOf("B"))
            val allEntries = listOf(a, b, c, d)

            val descendants = RebaseSimulator.collectDescendants(allEntries, setOf(c.id))

            descendants shouldContainExactlyInAnyOrder listOf(a.id, b.id)
        }

        @Test
        fun `no descendants returns empty`() {
            val a = entry("A")
            val b = entry("B")
            val allEntries = listOf(a, b)

            val descendants = RebaseSimulator.collectDescendants(allEntries, setOf(a.id))

            descendants shouldBe emptySet()
        }
    }

    @Nested
    inner class `scoping` {
        @Test
        fun `small entry list is returned as-is`() {
            val entries = (1..10).map { entry("E$it") }

            val scoped = RebaseSimulator.scopeToRelevant(entries, setOf(entries[0].id), setOf(entries[1].id))

            scoped shouldBe entries
        }
    }

    @Nested
    inner class `multiple sources` {
        @Test
        fun `multiple source entries are all moved in REVISION mode`() {
            val d = entry("D")
            val c = entry("C", listOf("D"))
            val b = entry("B", listOf("D"))
            val a = entry("A", listOf("B"))
            val allEntries = listOf(a, b, c, d)

            val result = RebaseSimulator.simulate(
                allEntries,
                sourceEntries = listOf(b, c),
                destinationIds = setOf(d.id),
                sourceMode = RebaseSourceMode.REVISION,
                destinationMode = RebaseDestinationMode.ONTO
            )

            result.sourceIds shouldContainExactlyInAnyOrder listOf(b.id, c.id)
        }
    }

    @Nested
    inner class `multiple destinations` {
        @Test
        fun `multiple destinations create merge parents`() {
            val d = entry("D")
            val c = entry("C")
            val b = entry("B", listOf("C"))
            val a = entry("A", listOf("B"))
            val allEntries = listOf(a, b, c, d)

            val result = RebaseSimulator.simulate(
                allEntries,
                sourceEntries = listOf(a),
                destinationIds = setOf(c.id, d.id),
                sourceMode = RebaseSourceMode.REVISION,
                destinationMode = RebaseDestinationMode.ONTO
            )

            val movedA = result.entries.find { it.id == a.id }!!
            parentIds(movedA) shouldContainExactlyInAnyOrder listOf("C", "D")
        }
    }

    @Nested
    inner class `empty inputs` {
        @Test
        fun `empty sources returns all entries unchanged`() {
            val a = entry("A")
            val allEntries = listOf(a)

            val result = RebaseSimulator.simulate(
                allEntries,
                sourceEntries = emptyList(),
                destinationIds = setOf(a.id),
                sourceMode = RebaseSourceMode.REVISION,
                destinationMode = RebaseDestinationMode.ONTO
            )

            result.entries shouldBe allEntries
            result.sourceIds shouldBe emptySet()
        }

        @Test
        fun `empty destinations returns all entries unchanged`() {
            val a = entry("A")
            val allEntries = listOf(a)

            val result = RebaseSimulator.simulate(
                allEntries,
                sourceEntries = listOf(a),
                destinationIds = emptySet(),
                sourceMode = RebaseSourceMode.REVISION,
                destinationMode = RebaseDestinationMode.ONTO
            )

            result.entries shouldBe allEntries
            result.destinationIds shouldBe emptySet()
        }
    }
}
