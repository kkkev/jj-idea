package `in`.kkkev.jjidea.jj

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

class FetchTimesTest {
    private var t = Instant.parse("2026-11-01T12:00:00Z")

    init {
        FetchTimes.now = { t }
        FetchTimes.clear()
    }

    @AfterEach
    fun tearDown() {
        FetchTimes.now = Instant::now
        FetchTimes.clear()
    }

    @Test
    fun `records per remote and reports the most recent per repo`() {
        FetchTimes.record("/r", listOf("origin", "upstream"))
        t = t.plusSeconds(60)
        FetchTimes.record("/r", listOf("origin"))

        FetchTimes.lastFetched("/r", "origin") shouldBe t
        FetchTimes.lastFetched("/r", "upstream") shouldBe t.minusSeconds(60)
        FetchTimes.lastFetched("/r") shouldBe t
        FetchTimes.lastFetched("/other") shouldBe null
        FetchTimes.lastFetchedByRemote("/r").map { it.first } shouldContainExactly listOf("origin", "upstream")
    }

    @Test
    fun `fetchedRemoteNames is the named remote, else every remote of the repo`() {
        val repo = mockk<JujutsuRepository> {
            every { gitRemotes } returns listOf(GitRemote("origin", "u"), GitRemote("upstream", "u"))
        }
        repo.fetchedRemoteNames(Remote("upstream"), allRemotes = false) shouldContainExactly listOf("upstream")
        repo.fetchedRemoteNames(null, allRemotes = false) shouldContainExactly listOf("origin", "upstream")
        repo.fetchedRemoteNames(Remote("upstream"), allRemotes = true) shouldContainExactly listOf("origin", "upstream")
    }
}
