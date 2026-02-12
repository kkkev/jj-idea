package `in`.kkkev.jjidea.jj.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicBoolean

inline fun <reified L> topic(displayName: String) = Topic.create(displayName, L::class.java)

interface NotifiableState<T> {
    fun interface Listener<T> {
        fun changed(oldValue: T, newValue: T)
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
    private val loading = AtomicBoolean(false)
    private val invalidatedWhileLoading = AtomicBoolean(false)

    @Volatile
    override var value: T = startValue

    init {
        invalidate()
    }

    override fun invalidate() {
        if (!loading.compareAndSet(false, true)) {
            // A load is already in progress; mark that we need to reload when it finishes
            log.info("[$topicDisplayName] invalidate skipped (already loading)")
            invalidatedWhileLoading.set(true)
            return
        }
        log.info("[$topicDisplayName] invalidate started on ${Thread.currentThread().name}")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val newValue = loader()
                val changed = !equalityCheck(value, newValue)
                log.info("[$topicDisplayName] load complete, changed=$changed")
                if (changed) {
                    val oldValue = value
                    value = newValue
                    notify(publisher, oldValue, newValue)
                }
            } finally {
                loading.set(false)
                // If invalidated while loading, re-run to pick up latest state
                if (invalidatedWhileLoading.compareAndSet(true, false)) {
                    log.info("[$topicDisplayName] re-invalidating (invalidated while loading)")
                    invalidate()
                }
            }
        }
    }

    override fun connect(parent: Disposable, handler: NotifiableState.Listener<T>) {
        project.messageBus.connect(parent).subscribe(topic, handler)
        // Only notify immediately if value differs from startValue (meaning load completed)
        // Otherwise, the load completion will trigger notification via invalidate()
        if (!equalityCheck(value, startValue)) {
            notify(handler, startValue, value)
        }
    }

    fun notify(listener: NotifiableState.Listener<T>, oldValue: T, newValue: T) {
        ApplicationManager.getApplication().invokeLater {
            listener.changed(oldValue, newValue)
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
