package `in`.kkkev.jjidea.jj

import com.intellij.openapi.util.Disposer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * jj-idea-2570.8: the operation-count deliverable - how many `jj` calls the background emptiness fetch makes.
 * The runners are manual so a test decides when the worker runs, which makes queue priority observable.
 */
class MergeEmptinessTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private val calls = mutableListOf<List<CommitId>>()
    private var failNext = false
    private var omit: CommitId? = null

    /** Empty iff the id's number is even. */
    private fun answer(id: CommitId) = id.full.removePrefix("c").toInt() % 2 == 0

    private val tasks = ArrayDeque<Runnable>()
    private val emptiness = RepoMergeEmptiness(
        fetch = { ids ->
            calls += ids.toList()
            if (failNext) error("jj failed")
            ids.filter { it != omit }.associateWith(::answer)
        },
        runNow = { tasks += it },
        runLater = { _, task -> tasks += task }
    )

    private fun runWorker() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }

    private fun entry(n: Int, deferred: Boolean = true) = LogEntry(
        repo = repo,
        id = ChangeId("x$n", "x$n"),
        commitId = CommitId("c$n"),
        underlyingDescription = "",
        immutable = true,
        parentIds = listOf(ChangeId("p", "p"), ChangeId("q", "q")),
        emptyDeferred = deferred
    )

    @Test
    fun `prefetch fetches in batches of 50 and caches every answer`() {
        val entries = (0 until 120).map { entry(it) }

        emptiness.prefetch(entries)
        runWorker()

        calls.map { it.size } shouldContainExactly listOf(50, 50, 20)
        entries.forEach {
            emptiness.peek(it) shouldBe if (answer(it.commitId)) Emptiness.EMPTY else Emptiness.NOT_EMPTY
        }
        calls.size shouldBe 3
    }

    @Test
    fun `cached, queued and non-deferred entries cost no further fetches`() {
        val entries = (0 until 10).map { entry(it) }
        emptiness.prefetch(entries)
        emptiness.prefetch(entries) // already queued
        emptiness.peek(entries[3]) // already queued: promoted, not requested again
        runWorker()
        calls.flatten().sortedBy { it.full } shouldContainExactly entries.map { it.commitId }.sortedBy { it.full }
        val callsSoFar = calls.size

        emptiness.prefetch(entries)
        entries.forEach { emptiness.peek(it) }
        emptiness.prefetch(listOf(entry(99, deferred = false)))
        runWorker()

        calls.size shouldBe callsSoFar
    }

    @Test
    fun `peek on an uncached deferred entry is pending until the worker has run`() {
        val e = entry(2)

        emptiness.peek(e) shouldBe Emptiness.PENDING
        calls.size shouldBe 0

        runWorker()

        emptiness.peek(e) shouldBe Emptiness.EMPTY
    }

    @Test
    fun `a peeked (visible) id jumps ahead of the prefetched backlog, newest peek first`() {
        emptiness.prefetch((0 until 100).map { entry(it) })
        emptiness.peek(entry(70))
        emptiness.peek(entry(71))

        runWorker()

        calls.first() shouldContainExactly listOf(CommitId("c71"), CommitId("c70"))
    }

    @Test
    fun `a failed fetch throws, leaves its ids pending and is not retried by repainting`() {
        val e = entry(1)
        emptiness.peek(e)
        failNext = true

        shouldThrow<IllegalStateException> { runWorker() }

        emptiness.peek(e) shouldBe Emptiness.PENDING
        runWorker()
        calls.size shouldBe 1

        // The worker is restartable: new ids still get fetched.
        failNext = false
        emptiness.peek(entry(2))
        runWorker()
        emptiness.peek(entry(2)) shouldBe Emptiness.EMPTY
    }

    @Test
    fun `resolve fetches one id on a miss and nothing on a hit`() {
        val e = entry(4)

        emptiness.resolve(e) shouldBe true
        emptiness.resolve(e) shouldBe true

        calls shouldContainExactly listOf(listOf(CommitId("c4")))
    }

    @Test
    fun `resolve fails loudly when jj omits the id`() {
        omit = CommitId("c5")

        shouldThrow<IllegalStateException> { emptiness.resolve(entry(5)) }
        emptiness.peek(entry(5)) shouldBe Emptiness.PENDING
    }

    @Test
    fun `listeners are told exactly which ids landed, until disposed`() {
        val landed = mutableListOf<Set<CommitId>>()
        val parent = Disposer.newDisposable()
        emptiness.addListener(parent) { landed += it }

        emptiness.prefetch(listOf(entry(1), entry(2)))
        runWorker()
        landed shouldContainExactly listOf(setOf(CommitId("c1"), CommitId("c2")))

        Disposer.dispose(parent)
        emptiness.prefetch(listOf(entry(3)))
        runWorker()
        landed.size shouldBe 1
    }

    @Test
    fun `addListener on an already-disposed parent is a no-op`() {
        val landed = mutableListOf<Set<CommitId>>()
        val parent = Disposer.newDisposable()
        Disposer.dispose(parent)

        emptiness.addListener(parent) { landed += it }

        emptiness.prefetch(listOf(entry(1)))
        runWorker()
        landed shouldBe emptyList()
    }

    @Test
    fun `a non-deferred entry reports its template value without touching the repository`() {
        entry(1, deferred = false).emptiness shouldBe Emptiness.NOT_EMPTY
        entry(1, deferred = false).resolveEmpty() shouldBe false
    }
}
