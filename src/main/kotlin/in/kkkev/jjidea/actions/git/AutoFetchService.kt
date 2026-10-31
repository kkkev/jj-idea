package `in`.kkkev.jjidea.actions.git

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.FetchTimes
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.JujutsuRepositoryHealth
import `in`.kkkev.jjidea.jj.RepoActivity
import `in`.kkkev.jjidea.jj.fetchedRemoteNames
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.services.addExpiringAction
import `in`.kkkev.jjidea.util.launchPolling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Periodic background `jj git fetch --all-remotes` (jj-idea-crik, GitHub #90). Off unless
 * [JujutsuSettings.autoFetchInterval] is set. One [launchPolling] job per repository, so a slow or
 * failing fetch in one repo never delays or backs off another.
 *
 * Safe alongside other work: a tick is skipped while a plugin command is running on the repo
 * ([RepoActivity]), and the fetch passes `--ignore-working-copy` so it never writes a snapshot
 * operation of its own that would become the target of Undo.
 *
 * The loop pauses while the IDE is inactive ([launchPolling]'s default gate). To make up for it,
 * activating the IDE fetches at once any repo whose last fetch ([FetchTimes]) is older than the
 * interval, so coming back never shows data up to a full interval stale.
 *
 * Quiet by design: no progress UI, no balloon on success (the log refreshes through the normal
 * [invalidate] path when the operation head moved), and a failure balloon only for the first
 * failure after a success. Backoff on repeated failure comes from [launchPolling].
 */
@Service(Service.Level.PROJECT)
class AutoFetchService(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val repos = ConcurrentHashMap<String, JujutsuRepository>()
    private val lastOpHead = ConcurrentHashMap<String, String>()
    private val failing = ConcurrentHashMap.newKeySet<String>()
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    /** Test seams. */
    internal var onFailure: (JujutsuRepository, String) -> Unit = ::notifyFailure
    internal var onChanged: (JujutsuRepository) -> Unit = { it.invalidate(vfsChanged = true) }

    internal var settingsOverride: JujutsuSettings? = null
    private val settings get() = settingsOverride ?: JujutsuSettings.getInstance(project)

    init {
        // Null in plain unit tests, which call [catchUp] directly.
        ApplicationManager.getApplication()?.messageBus?.connect(this)
            ?.subscribe(
                ApplicationActivationListener.TOPIC,
                object : ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: IdeFrame) = catchUp()
                }
            )
    }

    /** Brings the running jobs in line with the settings and the current repositories. Idempotent. */
    @Synchronized
    fun reconcile(
        repos: Collection<JujutsuRepository> = project.stateModel.initialisedRepositories.cachedValue.values
    ) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        this.repos.clear()
        if (settings.autoFetchInterval == null) return
        for (repo in repos) {
            val key = repo.directory.path
            this.repos[key] = repo
            jobs[key] = cs.launchPolling(
                "jj auto-fetch ${repo.displayName}",
                interval = { settings.autoFetchInterval ?: 10.minutes }
            ) { fetchOnce(repo) }
        }
    }

    /** The IDE just regained focus: fetch now any repo not fetched within the interval. */
    internal fun catchUp() {
        staleRepos().forEach { repo -> cs.launch(Dispatchers.IO) { fetchOnce(repo) } }
    }

    /** Repos whose last fetch is older than the interval (or never happened this session). */
    internal fun staleRepos(): List<JujutsuRepository> {
        val interval = settings.autoFetchInterval?.toJavaDuration() ?: return emptyList()
        val cutoff = FetchTimes.now().minus(interval)
        return repos.values.filter {
            FetchTimes.lastFetched(it.directory.path).let { last -> last == null || last.isBefore(cutoff) }
        }
    }

    /** One fetch cycle for [repo]. Returns false on failure so the poller backs off. */
    internal fun fetchOnce(repo: JujutsuRepository): Boolean {
        if (settings.autoFetchInterval == null) return true
        val key = repo.directory.path
        if (JujutsuRepositoryHealth.healthFor(key) != null || repo.gitRemotes.isEmpty()) return true
        // A plugin command (e.g. Undo's multi-step status/op log/undo) is running here: skip this tick
        // without counting it as a failure; the next interval tries again.
        if (RepoActivity.isBusy(key)) return true
        // The poll loop and a focus catch-up must not fetch the same repo at once.
        if (!fetching.add(key)) return true
        try {
            return fetch(repo, key)
        } finally {
            fetching.remove(key)
        }
    }

    private fun fetch(repo: JujutsuRepository, key: String): Boolean {
        val executor = repo.commandExecutor
        val result = executor.gitFetch(remote = null, allRemotes = true, ignoreWorkingCopy = true)
        if (result !is CommandExecutor.CommandResult.Success) {
            log.info("Automatic fetch failed for ${repo.displayName}: ${result.stderr}")
            if (failing.add(key)) onFailure(repo, result.stderr)
            return false
        }
        failing.remove(key)
        FetchTimes.record(key, repo.fetchedRemoteNames(null, allRemotes = true))

        val head = (executor.opLog(1, OP_HEAD_TEMPLATE) as? CommandExecutor.CommandResult.Success)?.stdout?.trim()
        if (head.isNullOrEmpty()) return true
        val previous = lastOpHead.put(key, head)
        if (previous != null && previous != head) onChanged(repo)
        return true
    }

    private fun notifyFailure(repo: JujutsuRepository, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Jujutsu")
            .createNotification(
                JujutsuBundle.message("auto.fetch.failed.title"),
                "${repo.displayName}: ${message.trim()}",
                NotificationType.WARNING
            )
            .also {
                it.addExpiringAction("auto.fetch.failed.disable") {
                    settings.state.autoFetchIntervalMinutes = 0
                    reconcile()
                }
            }
            .notify(project)
    }

    override fun dispose() {
        jobs.values.forEach { it.cancel() }
    }

    companion object {
        private val log = Logger.getInstance(AutoFetchService::class.java)
        internal const val OP_HEAD_TEMPLATE = "self.id().short(12)"

        fun getInstance(project: Project): AutoFetchService = project.service()
    }
}
