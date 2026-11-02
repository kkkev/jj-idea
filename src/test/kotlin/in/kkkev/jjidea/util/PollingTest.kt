package `in`.kkkev.jjidea.util

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class PollingTest {
    /**
     * Virtual clock: each wait advances time instantly and, past [limit], cancels the polling
     * coroutine it is running in. It must not go through a `Job` reference assigned after
     * `launchPolling` returns: the loop runs on Dispatchers.IO and can pass the limit first.
     */
    private class Clock(val limit: Duration) {
        var now = Duration.ZERO
        val waits = mutableListOf<Duration>()

        suspend fun wait(d: Duration) {
            waits += d
            now += d
            if (now > limit) currentCoroutineContext().cancel(CancellationException("done"))
            yield()
        }
    }

    private fun poll(
        limit: Duration,
        shouldRun: () -> Boolean = { true },
        maxBackoff: Int = 10,
        task: suspend () -> Boolean
    ): Clock {
        val clock = Clock(limit)
        runBlocking {
            CoroutineScope(coroutineContext).launchPolling(
                "test",
                { 10.minutes },
                shouldRun,
                maxBackoff,
                clock::wait,
                task
            ).join()
        }
        return clock
    }

    @Test
    fun `runs once per interval`() {
        var runs = 0
        poll(60.minutes) {
            runs++
            true
        }
        // ticks at 10,20,...,60 (the 70-minute wait trips the limit and is cancelled before running)
        runs shouldBe 6
    }

    @Test
    fun `operation count - three repos over an hour at ten minutes is at most 18 fetches`() {
        var runs = 0
        repeat(3) {
            poll(60.minutes) {
                runs++
                true
            }
        }
        (runs <= 18) shouldBe true
    }

    @Test
    fun `does not run while shouldRun is false`() {
        var runs = 0
        poll(60.minutes, shouldRun = { false }) {
            runs++
            true
        }
        runs shouldBe 0
    }

    @Test
    fun `failure backs off exponentially capped and success resets`() {
        val results = ArrayDeque(listOf(false, false, false, false, false, true, true))
        val clock = poll(100000.minutes) { results.removeFirstOrNull() ?: throw CancellationException() }
        clock.waits.take(8).map { it.inWholeMinutes } shouldContainExactly
            listOf(10L, 20, 40, 80, 100, 100, 10, 10)
    }

    @Test
    fun `exception counts as a failure and polling continues`() {
        var runs = 0
        val clock = poll(60.minutes) {
            runs++
            error("boom")
        }
        (runs >= 2) shouldBe true
        clock.waits.drop(1).first() shouldNotBe 10.minutes
    }

    @Test
    fun `cancelling the job stops polling`() {
        var runs = 0
        runBlocking {
            val job = CoroutineScope(coroutineContext).launchPolling("test", { 10.minutes }, { true }, task = {
                runs++
                true
            })
            job.cancelAndJoin() // real delay(10 min) is cancelled long before the first run
        }
        runs shouldBe 0
    }
}
