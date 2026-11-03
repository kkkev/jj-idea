package `in`.kkkev.jjidea.jj

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.util.launchPolling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Notices jj operations made outside the IDE by polling `jj op log -n1` (jj-idea-j2vl, GitHub #115), for
 * repositories where the `op_heads` file watch can never fire: network mounts, or custom backends that keep
 * operation state server-side so there is no local file to watch. Off unless
 * [JujutsuSettings.opHeadPollInterval] is set.
 *
 * One [launchPolling] job per repository, so a slow probe in one repo never delays another. Each tick is one
 * cheap jj process (`--ignore-working-copy`, so it never snapshots or writes). A change goes through
 * [JujutsuStateModel.externalOperationDetected], the same debounced entry the VFS event uses.
 *
 * The loop pauses while the IDE is inactive ([launchPolling]'s default gate). On activation, and when
 * (re)configured, every repo is probed at once: that primes the baseline and catches whatever happened in the
 * terminal meanwhile. A tick is skipped, not counted as a failure, while a plugin command runs on the repo
 * ([RepoActivity]) or the repo is unreadable.
 */
@Service(Service.Level.PROJECT)
class OpHeadPollService(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val repos = ConcurrentHashMap<String, JujutsuRepository>()
    private val probing = ConcurrentHashMap.newKeySet<String>()

    /** Test seam. */
    internal var onChanged: (JujutsuRepository) -> Unit = { project.stateModel.externalOperationDetected(it) }

    internal var settingsOverride: JujutsuSettings? = null
    private val settings get() = settingsOverride ?: JujutsuSettings.getInstance(project)

    init {
        // Null in plain unit tests, which call [pollOnce] directly.
        ApplicationManager.getApplication()?.messageBus?.connect(this)
            ?.subscribe(
                ApplicationActivationListener.TOPIC,
                object : ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: IdeFrame) = probeAll()
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
        if (settings.opHeadPollInterval == null) return
        for (repo in repos) {
            val key = repo.directory.path
            this.repos[key] = repo
            jobs[key] = cs.launchPolling(
                "jj op-head poll ${repo.displayName}",
                interval = { settings.opHeadPollInterval ?: DEFAULT_INTERVAL }
            ) { pollOnce(repo) }
        }
        probeAll() // baseline now, rather than a full interval from now
    }

    private fun probeAll() {
        repos.values.forEach { repo -> cs.launch(Dispatchers.IO) { pollOnce(repo) } }
    }

    internal fun activeRepoCount() = jobs.size

    /** One probe of [repo]. Returns false on failure so the poller backs off. */
    internal fun pollOnce(repo: JujutsuRepository): Boolean {
        if (settings.opHeadPollInterval == null) return true
        val key = repo.directory.path
        if (JujutsuRepositoryHealth.healthFor(key) != null || RepoActivity.isBusy(key)) return true
        // The poll loop and an activation probe must not run jj on the same repo at once.
        if (!probing.add(key)) return true
        try {
            val head = OpHeadIds.read(repo.commandExecutor) ?: return false
            if (OpHeadIds.record(key, head)) onChanged(repo)
            return true
        } finally {
            probing.remove(key)
        }
    }

    override fun dispose() {
        jobs.values.forEach { it.cancel() }
    }

    companion object {
        private val DEFAULT_INTERVAL = JujutsuSettings.OP_HEAD_POLL_DEFAULT_SECONDS.seconds

        fun getInstance(project: Project): OpHeadPollService = project.service()
    }
}
