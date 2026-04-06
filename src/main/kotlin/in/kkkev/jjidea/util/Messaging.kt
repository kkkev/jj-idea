package `in`.kkkev.jjidea.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import `in`.kkkev.jjidea.util.NotifiableState.Listener
import java.util.concurrent.atomic.AtomicInteger

inline fun <reified L> topic(displayName: String) = Topic.create(displayName, L::class.java)

interface NotifiableState<T> {
    fun interface Listener<T> {
        fun changed(newValue: T)
    }

    val value: T

    fun connect(parent: Disposable, handler: Listener<T>)

    fun invalidate()
}

fun <T : Any> notifiableState(
    project: Project,
    topicDisplayName: String,
    startValue: T,
    equalityCheck: (T, T) -> Boolean = { a, b -> a == b },
    loader: () -> T
): NotifiableState<T> =
    SimpleNotifiableState(project, topicDisplayName, startValue, equalityCheck, loader)

/**
 * A state holder that loads its value on a background thread and notifies listeners on EDT when it changes.
 *
 * ## Threading Contract
 * - [invalidate] is safe to call from any thread. It schedules [loader] on a pooled thread.
 * - [loader] runs on a pooled background thread.
 * - [Listener.changed] is called on EDT via [ApplicationManager.getApplication().invokeLater].
 * - [value] is `@Volatile` and can be read from any thread (returns the last successfully loaded value).
 *
 * ## Versioning
 * Each [invalidate] call bumps an [java.util.concurrent.atomic.AtomicInteger] version counter. If a newer invalidation arrives
 * before the loader completes, the stale result is discarded. This means rapid invalidations
 * naturally coalesce to the latest value without explicit debouncing.
 *
 * ## Initialization
 * Does NOT auto-invalidate on construction. Callers must call [invalidate] explicitly to trigger the
 * first load. This avoids wasted loads when downstream states depend on upstream state that hasn't
 * loaded yet (e.g., repositoryStates depends on initializedRoots).
 */
class SimpleNotifiableState<T : Any>(
    val project: Project,
    val topicDisplayName: String,
    val startValue: T,
    val equalityCheck: (T, T) -> Boolean,
    val loader: () -> T
) : NotifiableState<T> {
    private val log = Logger.getInstance(javaClass)
    val topic = topic<Listener<T>>(topicDisplayName)

    private val publisher = project.messageBus.syncPublisher(topic)

    /**
     * Monotonically increasing version counter. Each [invalidate] call bumps this.
     * After a load completes, if the version has moved on, we reload — no separate
     * "invalidated while loading" flag needed.
     */
    private val version = AtomicInteger(0)

    @Volatile
    override var value: T = startValue

    override fun invalidate() {
        val myVersion = version.incrementAndGet()
        log.info("[$topicDisplayName] invalidate v$myVersion on ${Thread.currentThread().name}")
        runInBackground {
            val newValue = loader()
            // If version has moved on since we started, another load is queued or will be —
            // just discard this result. The latest invalidate() will produce a fresher load.
            if (version.get() != myVersion) {
                log.info("[$topicDisplayName] v$myVersion stale (now v${version.get()}), discarding")
                return@runInBackground
            }
            val changed = !equalityCheck(value, newValue)
            log.info("[$topicDisplayName] v$myVersion loaded, changed=$changed")
            if (changed) {
                value = newValue
                runLater {
                    publisher.changed(newValue)
                }
            }
        }
    }

    override fun connect(parent: Disposable, handler: Listener<T>) {
        project.messageBus.connect(parent).subscribe(topic, handler)
        val current = value
        if (!equalityCheck(current, startValue)) {
            // Replay current value to the new handler only (not all subscribers).
            // Using handler directly instead of publisher avoids spurious replays
            // to long-lived subscribers (log tab, working copy panel).
            runLater {
                handler.changed(current)
            }
        }
    }
}

interface Notifier<E> {
    fun interface Listener<E> {
        fun onEvent(event: E)
    }

    fun connect(parent: Disposable, handler: Listener<E>)

    fun notify(event: E)
}

class SimpleNotifier<E>(val project: Project, topicDisplayName: String) : Notifier<E> {
    val topic = topic<Notifier.Listener<E>>(topicDisplayName)

    private val publisher = project.messageBus.syncPublisher(topic)

    override fun connect(parent: Disposable, handler: Notifier.Listener<E>) {
        project.messageBus.connect(parent).subscribe(topic, handler)
    }

    override fun notify(event: E) {
        runLater {
            publisher.onEvent(event)
        }
    }
}

fun <E> simpleNotifier(project: Project, topicDisplayName: String): Notifier<E> =
    SimpleNotifier(project, topicDisplayName)
