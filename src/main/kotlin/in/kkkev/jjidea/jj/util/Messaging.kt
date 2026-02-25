package `in`.kkkev.jjidea.jj.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.Topic
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

class SimpleNotifiableState<T : Any>(
    val project: Project,
    val topicDisplayName: String,
    val startValue: T,
    val equalityCheck: (T, T) -> Boolean,
    val loader: () -> T
) :
    NotifiableState<T> {
    private val log = Logger.getInstance(javaClass)
    val topic = topic<NotifiableState.Listener<T>>(topicDisplayName)

    private val publisher = project.messageBus.syncPublisher(topic)

    /**
     * Monotonically increasing version counter. Each [invalidate] call bumps this.
     * After a load completes, if the version has moved on, we reload — no separate
     * "invalidated while loading" flag needed.
     */
    private val version = AtomicInteger(0)

    @Volatile
    override var value: T = startValue

    init {
        invalidate()
    }

    override fun invalidate() {
        val myVersion = version.incrementAndGet()
        log.info("[$topicDisplayName] invalidate v$myVersion on ${Thread.currentThread().name}")
        ApplicationManager.getApplication().executeOnPooledThread {
            val newValue = loader()
            // If version has moved on since we started, another load is queued or will be —
            // just discard this result. The latest invalidate() will produce a fresher load.
            if (version.get() != myVersion) {
                log.info("[$topicDisplayName] v$myVersion stale (now v${version.get()}), discarding")
                return@executeOnPooledThread
            }
            val changed = !equalityCheck(value, newValue)
            log.info("[$topicDisplayName] v$myVersion loaded, changed=$changed")
            if (changed) {
                value = newValue
                ApplicationManager.getApplication().invokeLater {
                    publisher.changed(newValue)
                }
            }
        }
    }

    override fun connect(parent: Disposable, handler: NotifiableState.Listener<T>) {
        project.messageBus.connect(parent).subscribe(topic, handler)
        val current = value
        if (!equalityCheck(current, startValue)) {
            ApplicationManager.getApplication().invokeLater {
                @Suppress("UnstableApiUsage")
                if (!Disposer.isDisposed(parent)) {
                    handler.changed(current)
                }
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
        ApplicationManager.getApplication().invokeLater {
            publisher.onEvent(event)
        }
    }
}

fun <E> simpleNotifier(project: Project, topicDisplayName: String): Notifier<E> =
    SimpleNotifier(project, topicDisplayName)
