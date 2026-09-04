package `in`.kkkev.jjidea.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.Topic
import `in`.kkkev.jjidea.util.NotifiableState.Listener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

inline fun <reified L> topic(displayName: String) = Topic.create(displayName, L::class.java)

interface NotifiableState<T> {
    fun interface Listener<T> {
        fun changed(newValue: T)
    }

    val value: T

    /**
     * True once at least one [invalidate] call has finished loading — regardless of whether the
     * loaded value differs from [T]'s start value. Lets a consumer distinguish "genuinely empty"
     * (loaded, but there's nothing) from "hasn't loaded yet" (still showing the start value),
     * e.g. to show a loading placeholder instead of an empty list (jj-idea-a52h).
     */
    val hasLoaded: Boolean

    /**
     * Returns the current cached value if already loaded, or runs the loader synchronously
     * on the calling thread if the cache is still at its start value.
     *
     * ## When to use
     * Only call this from a **background thread** — e.g. inside another [NotifiableState] loader,
     * or a pooled-thread VCS provider. It exists to break ordering dependencies between states
     * whose loaders cascade: without it, a loader that reads another state's [value] may see an
     * empty start value if the upstream load hasn't finished yet.
     *
     * ## Why it is safe
     * Loaders are pure functions (read-only, idempotent). If the background load races with this
     * synchronous call, both runs produce the same result; the background loader's result is
     * discarded by the version check in [invalidate]. [value] is only ever written by the
     * background loader, so there is no write-write conflict.
     *
     * ## What it does NOT do
     * - Does **not** update [value] or notify listeners — the background loader owns that.
     * - Does **not** coalesce with an in-flight background load.
     *
     * ## What would break it
     * - **Calling from EDT** — would block the UI thread for the duration of the loader.
     * - **A loader with side effects** — double-execution would fire those effects twice.
     *   Keep loaders pure.
     */
    val immediateValue: T

    /**
     * Returns the current cached [value] without ever blocking, firing off a background
     * [invalidate] if the state has not loaded yet.
     *
     * ## When to use
     * Call this from a read action or an action's `update()`/`getChildren()` (even on
     * [com.intellij.openapi.actionSystem.ActionUpdateThread.BGT]) - platform code runs those under
     * a read action, where a synchronous shell-out (as [immediateValue] can do on a cold cache)
     * is forbidden (jj-idea-c4tp; see [in.kkkev.jjidea.actions.file.TrackedToggleAction] for the
     * same pattern applied to tracked-file state).
     *
     * ## What it does NOT do
     * Never blocks and never guarantees a fresh value: a caller on a cold cache gets [T]'s start
     * value once, with the real value arriving asynchronously via [connect] on the next
     * [invalidate] completion.
     */
    val cachedValue: T
        get() {
            if (!hasLoaded) invalidate()
            return value
        }

    fun connect(parent: Disposable, handler: Listener<T>)

    /**
     * Connects to the notifiable state, and fires the current state immediately and synchronously, rather than waiting
     * for an asynchronous event fired via [runLater].
     *
     * Like [cachedValue], this starts a background load if the state has not loaded yet — so a consumer that
     * subscribes before anything else has touched the state still gets a real value, delivered later via [connect].
     * Without that, a state whose only observers use this method would sit on its start value forever.
     */
    fun connectAndFireSync(parent: Disposable, handler: Listener<T>)

    fun invalidate()

    /**
     * Suspends until this state has loaded a non-start value, then returns it.
     *
     * If the value is already loaded ([value] != start value), returns immediately.
     *
     * ## Why this exists
     * [in.kkkev.jjidea.ui.services.JujutsuStartupActivity] awaits this on
     * [in.kkkev.jjidea.jj.JujutsuStateModel.initialisedRepositories] before returning, ensuring the
     * repository cache is warm before IntelliJ activates VCS and calls annotation/diff providers.
     *
     * ## Ordering guarantee relied upon
     * IntelliJ guarantees that VCS activation — which triggers
     * `com.intellij.vcs.CacheableAnnotationProvider.populateCache` and
     * [com.intellij.openapi.vcs.diff.DiffProvider.getLastRevision] — happens **after** all
     * [com.intellij.openapi.startup.ProjectActivity] instances complete. If that guarantee is
     * removed in a future platform version, this await will no longer prevent the race and the
     * annotation/diff providers will need their own fallback.
     *
     * ## Timeout
     * Gives up after [timeoutMs] ms to avoid hanging startup when jj is unavailable or
     * misconfigured. On timeout, returns the current [value] as-is (may still be the start value).
     */
    suspend fun awaitLoad(timeoutMs: Long = 10_000): T
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
 * loaded yet (e.g., workingCopies depends on initialisedRepositories).
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

    @Volatile
    override var hasLoaded: Boolean = false
        private set

    override val immediateValue: T
        get() {
            // Gate on hasLoaded, not equalityCheck(value, startValue) (jj-idea-c4tp): a repo that
            // genuinely loads to the same value as startValue (e.g. no git remotes) would otherwise
            // look "not yet loaded" forever and re-run loader() synchronously on every access.
            if (hasLoaded) return value
            val loaded = loader()
            hasLoaded = true
            return loaded
        }

    override suspend fun awaitLoad(timeoutMs: Long): T {
        if (!equalityCheck(value, startValue)) return value
        return withTimeoutOrNull(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { cont ->
                val disposable = Disposer.newDisposable("awaitLoad-$topicDisplayName")
                cont.invokeOnCancellation { Disposer.dispose(disposable) }
                connect(disposable) { newValue ->
                    Disposer.dispose(disposable)
                    if (cont.isActive) cont.resume(newValue)
                }
            }
        } ?: value
    }

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
            hasLoaded = true
            if (changed) {
                value = newValue
                runLater {
                    publisher.changed(newValue)
                }
            }
        }
    }

    override fun connectAndFireSync(parent: Disposable, handler: Listener<T>) {
        // Subscribe directly rather than via connect(): connect() replays a warm value through
        // runLater, which would deliver the same value twice (once here, once on the EDT) to
        // handlers that aren't idempotent — JujutsuStartupActivity's raises a user notification.
        subscribe(parent, handler)
        handler.changed(cachedValue)
    }

    private fun subscribe(parent: Disposable, handler: Listener<T>) {
        project.messageBus.connect(parent).subscribe(topic, handler)
    }

    override fun connect(parent: Disposable, handler: Listener<T>) {
        subscribe(parent, handler)
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
