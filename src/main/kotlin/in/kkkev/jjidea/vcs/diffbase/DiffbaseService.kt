package `in`.kkkev.jjidea.vcs.diffbase

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves the configured [in.kkkev.jjidea.settings.DiffbaseStrategy] to a concrete base
 * revision for a repository, and is the single source of truth both
 * [DiffbaseContentLoader] (editor gutter change markers) and
 * [in.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider] (blame) consult — so they can
 * never disagree on the base revision (jj-idea-fwea / GitHub #43). Disagreement there is
 * what causes annotation misalignment: IntelliJ's `UpToDateLineNumberProvider` maps editor
 * lines to annotation lines *through* the LineStatusTracker diff.
 */
@Service(Service.Level.PROJECT)
class DiffbaseService(private val project: Project) {
    private val log = Logger.getInstance(javaClass)

    // Resolved base revision per repo (keyed by repo.directory.path), cleared on logRefresh
    // (any VCS operation, not just working-copy edits) and on a settings change. A repo with
    // no entry means "not yet resolved"; a repo that resolved to nothing (e.g. no immutable
    // ancestor) is simply absent too — both cases fall through to the default @-.
    private val cache = ConcurrentHashMap<String, ChangeId>()

    private val invalidationSubscribed = AtomicBoolean(false)

    private fun ensureInvalidationSubscribed() {
        if (invalidationSubscribed.compareAndSet(false, true)) {
            project.stateModel.logRefresh.connect(project) { cache.clear() }
        }
    }

    /**
     * True when a custom diff base is configured for [repo] — a settings lookup only, safe to
     * call from the EDT (in particular from [DiffbaseContentLoader.isTrackedFile]). Never
     * shells out to jj; use [resolve] for the actual revision, from a background thread.
     */
    fun isActive(repo: JujutsuRepository): Boolean {
        val settings = JujutsuSettings.getInstance(project)
        val strategy = settings.diffbaseStrategy(repo)
        val revset = strategy.revset(settings.customDiffbaseRevset(repo))
        return revset != null
    }

    /** [isActive], resolving the repo for [file] first; false if [file] isn't in a jj repo. */
    fun isActive(file: VirtualFile): Boolean {
        val repo = project.possibleJujutsuRepositoryFor(file) ?: return false
        return isActive(repo)
    }

    /**
     * Resolves the configured diff base for [repo] to a concrete [ChangeId], or `null` when no
     * custom diff base is configured (use `@-`), the configured revset failed to resolve (e.g.
     * no immutable ancestor exists, or a bad custom expression), or it resolved *ambiguously*
     * (matched more than one revision) — callers fall back to today's default behaviour in
     * every case. A diff base needs exactly one revision, unlike the log view's revset, so an
     * ambiguous match is treated as a failure rather than picking one arbitrarily — mirroring
     * jj's own single-revision commands (`jj edit`, `jj file annotate -r`), which refuse rather
     * than guess. See [in.kkkev.jjidea.settings.JujutsuConfigurable.testDiffbaseRevset] for the
     * same check surfaced at validation time.
     *
     * Runs `jj log` on a cache miss: **call from a background thread only.** Results are
     * cached per repo until the next [in.kkkev.jjidea.jj.JujutsuStateModel.logRefresh] or
     * [notifyDiffbaseChanged], so N open editors in the same repo share one resolution.
     */
    fun resolve(repo: JujutsuRepository): ChangeId? {
        ensureInvalidationSubscribed()
        val path = repo.directory.path
        cache[path]?.let { return it }

        val settings = JujutsuSettings.getInstance(project)
        val strategy = settings.diffbaseStrategy(repo)
        val revset = strategy.revset(settings.customDiffbaseRevset(repo)) ?: return null

        // limit = 2 (not 1): a second match is exactly what distinguishes "resolves to exactly
        // one revision" from "resolves ambiguously" without an unbounded jj log.
        val result = repo.logService.getLog(revset = Expression(revset), limit = 2, quiet = true)
        val entries = result.getOrNull().orEmpty()
        when {
            entries.isEmpty() -> {
                log.info("Diff base revset '$revset' resolved to nothing for ${repo.directory.path}")
                return null
            }
            entries.size > 1 -> {
                log.info("Diff base revset '$revset' resolved ambiguously (2+ revisions) for ${repo.directory.path}")
                return null
            }
        }
        val entry = entries.first()
        cache[path] = entry.id
        return entry.id
    }

    /**
     * Called after the diff base setting changes (from [in.kkkev.jjidea.settings.JujutsuConfigurable]).
     * Clears the resolution cache, asks the platform to refresh every open editor's gutter
     * markers — this reaches `LineStatusTrackerManager.onEverythingChanged()`, which re-runs
     * [DiffbaseContentLoader.isTrackedFile] and reloads content for every tracked file — and
     * forces any *already-open* Annotate gutter to re-fetch and redisplay.
     *
     * The last part matters: an open gutter's line-number mapping is driven by the *live*
     * `LineStatusTracker` diff (`UpToDateLineNumberProviderImpl` re-reads it on every paint),
     * so once `fileStatusesChanged()` moves the tracker to the new base, a still-displayed but
     * un-refreshed `FileAnnotation` (computed against the *old* base) gets remapped through the
     * new diff and shows misattributed lines. `reloadAnnotations()` calls `reload(null)` on
     * every currently-registered `FileAnnotation`
     * ([com.intellij.openapi.vcs.actions.AnnotateToggleAction] registers one whenever a gutter
     * opens), which re-invokes the Annotate provider and swaps in a fresh, correctly-based
     * `FileAnnotation` — the same pattern git4idea's own annotation-affecting toggles use
     * (`GitToggleAnnotationOptionsActionProvider.resetAllAnnotations`). Annotate's own
     * *cache* is still cleared separately by
     * [in.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider], for editors that aren't open yet.
     */
    fun notifyDiffbaseChanged() {
        cache.clear()
        FileStatusManager.getInstance(project).fileStatusesChanged()
        ProjectLevelVcsManager.getInstance(project).annotationLocalChangesListener.reloadAnnotations()
        project.stateModel.diffbaseChanged.notify(Unit)
    }

    companion object {
        fun getInstance(project: Project): DiffbaseService = project.service()
    }
}
