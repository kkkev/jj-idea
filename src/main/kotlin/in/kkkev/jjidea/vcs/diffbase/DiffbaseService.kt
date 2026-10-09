package `in`.kkkev.jjidea.vcs.diffbase

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
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
 *
 * What those two consult is written by two places, both funneled through
 * [in.kkkev.jjidea.settings.JujutsuSettings.setDiffbase]-style calls followed by
 * [notifyDiffbaseChanged]: Settings → Version Control → Jujutsu's Diff Base group
 * ([in.kkkev.jjidea.settings.JujutsuConfigurable]),
 * for a permanent per-project/per-repo default, and the "Set Diff Base" quick action
 * ([in.kkkev.jjidea.actions.diffbase.SetDiffbaseAction]) for a fast, task-driven switch
 * (jj-idea-g1io / GitHub #43) — see [notifyDiffbaseChanged] for why every writer must call it.
 */
@Service(Service.Level.PROJECT)
class DiffbaseService(private val project: Project) {
    private val log = Logger.getInstance(javaClass)

    // Resolved base revision per repo (keyed by repo.directory.path), cleared on logRefresh
    // (any VCS operation, not just working-copy edits) and on a settings change. A repo with
    // no entry means "not yet resolved"; a repo that resolved to nothing (e.g. no immutable
    // ancestor) is simply absent too — both cases fall through to the default @-.
    private val cache = ConcurrentHashMap<String, ChangeId>()

    // "Is this file absent at the resolved base" per (repo, base, path), so repeatedly building
    // the editor context menu costs one `jj file list` per file, not one per menu open. Cleared
    // alongside [cache]: a base that is rewritten or re-resolved invalidates the answer.
    private val absentAtBaseCache = ConcurrentHashMap<String, Boolean>()

    private val invalidationSubscribed = AtomicBoolean(false)

    private fun ensureInvalidationSubscribed() {
        if (invalidationSubscribed.compareAndSet(false, true)) {
            project.stateModel.logRefresh.connect(project) {
                cache.clear()
                absentAtBaseCache.clear()
            }
        }
    }

    /**
     * True when a diff base is configured and resolves, and [filePath] does not exist at it (it was
     * added after the base). Annotate has nothing to show for such a file, so the Jujutsu editor
     * menu hides it (jj-idea-bia2). False when no custom base is configured (the platform already
     * hides Annotate for files that are new relative to `@-`) or when the check can't be made.
     *
     * Runs `jj` on a cache miss: **call from a background thread only.**
     */
    fun isAbsentAtBase(repo: JujutsuRepository, filePath: FilePath): Boolean {
        val base = resolve(repo) ?: return false
        val key = "${repo.directory.path}|${base.full}|${filePath.path}"
        absentAtBaseCache[key]?.let { return it }
        return repo.isAbsentAt(filePath, base).also { absentAtBaseCache[key] = it }
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

        when (val result = resolveExactlyOne(repo, revset)) {
            is ResolveResult.None -> {
                log.info("Diff base revset '$revset' resolved to nothing for ${repo.directory.path}")
                return null
            }
            is ResolveResult.Ambiguous -> {
                log.info(
                    "Diff base revset '$revset' resolved ambiguously (${result.count}+ revisions) for ${repo.directory.path}"
                )
                return null
            }
            is ResolveResult.Single -> {
                cache[path] = result.id
                return result.id
            }
        }
    }

    /**
     * Called after the diff base setting changes (from [in.kkkev.jjidea.settings.JujutsuConfigurable]).
     * Clears the resolution cache and asks the platform to refresh every open editor's gutter
     * markers — this reaches `LineStatusTrackerManager.onEverythingChanged()`, which re-runs
     * [DiffbaseContentLoader.isTrackedFile] and reloads content for every tracked file.
     *
     * An *already-open* Annotate gutter is handled by the [in.kkkev.jjidea.jj.JujutsuStateModel.diffbaseChanged]
     * notification, to which [in.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider] responds by
     * closing it. The gutter's line-number mapping is driven by the *live* `LineStatusTracker` diff, so
     * once the tracker moves to the new base a still-displayed `FileAnnotation` (computed against the
     * *old* base) is remapped through the new diff and shows misattributed lines. It is closed rather
     * than reloaded (`VcsAnnotationLocalChangesListener.reloadAnnotations()`, as jj-idea-fwea first
     * did): for a file absent at the new base the reloaded annotation is closed and empty, and
     * `AnnotateToggleAction.doAnnotate` then leaves the old gutter in place and an "Number of lines
     * annotated ... is not equal" error banner on the editor (jj-idea-bia2).
     */
    fun notifyDiffbaseChanged() {
        cache.clear()
        absentAtBaseCache.clear()
        FileStatusManager.getInstance(project).fileStatusesChanged()
        project.stateModel.diffbaseChanged.notify(Unit)
    }

    companion object {
        fun getInstance(project: Project): DiffbaseService = project.service()
    }
}

/** Outcome of [resolveExactlyOne]: a diff base must resolve to exactly one revision. */
sealed interface ResolveResult {
    data class Single(val id: ChangeId) : ResolveResult
    data object None : ResolveResult
    data class Ambiguous(val count: Int) : ResolveResult
}

/**
 * Resolves [revset] to a single revision, off the EDT. A diff base needs exactly one revision,
 * unlike the log view's revset, so no match or an ambiguous match are both reported distinctly
 * rather than treated the same as success — mirrors jj's own single-revision commands (`jj edit`,
 * `jj file annotate -r`), which refuse rather than guess.
 *
 * Shared by [DiffbaseService.resolve] (the cached hot path) and
 * [in.kkkev.jjidea.actions.diffbase.SetDiffbaseAction] (one-shot validation before writing a new
 * custom-revset override), so the two can't drift on what "resolves cleanly" means. The settings
 * panel's own Test button ([in.kkkev.jjidea.settings.JujutsuConfigurable.testDiffbaseRevset]) uses
 * a lower-level primitive to surface jj's raw stderr per repo, but applies this same rule.
 */
fun resolveExactlyOne(repo: JujutsuRepository, revset: String): ResolveResult {
    // limit = 2 (not 1): a second match is exactly what distinguishes "resolves to exactly one
    // revision" from "resolves ambiguously" without an unbounded jj log.
    val result = repo.logService.getLog(revset = Expression(revset), limit = 2, quiet = true)
    val entries = result.getOrNull().orEmpty()
    return when {
        entries.isEmpty() -> ResolveResult.None
        entries.size > 1 -> ResolveResult.Ambiguous(entries.size)
        else -> ResolveResult.Single(entries.first().id)
    }
}

/**
 * True if [filePath] does not exist at [revision]. `jj file list` succeeds with empty output when
 * the path is absent, whereas a genuine failure (jj error) is non-success — so only the former
 * counts as absent. Shared by [DiffbaseContentLoader] (gutter: empty base) and
 * [in.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider] (annotate: no attributions) so the two
 * treat a file added after the diff base identically (jj-idea-zf1j, jj-idea-bia2).
 */
fun JujutsuRepository.isAbsentAt(filePath: FilePath, revision: Revision): Boolean {
    val result = commandExecutor.fileList(listOf(filePath), revision)
    return result is CommandExecutor.CommandResult.Success && result.stdout.isBlank()
}
