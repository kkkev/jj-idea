package `in`.kkkev.jjidea.util

import java.util.concurrent.atomic.AtomicLong

/**
 * Discards superseded async results: call [next] when starting a computation and apply its
 * result only if [isCurrent] still holds for the returned token.
 */
class LatestWinsGate {
    private val generation = AtomicLong()

    fun next() = generation.incrementAndGet()

    fun isCurrent(token: Long) = generation.get() == token
}
