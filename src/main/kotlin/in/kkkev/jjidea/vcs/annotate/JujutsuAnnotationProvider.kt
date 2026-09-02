package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.AnnotationProviderEx
import com.intellij.vcs.CacheableAnnotationProvider
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.ContentLocator
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.MergeParentOf
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.AnnotationParser
import `in`.kkkev.jjidea.jj.reconstructMergeParentContent
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.vcs.JujutsuVcsBase
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.changes.contentLocator
import `in`.kkkev.jjidea.vcs.contentLocator
import `in`.kkkev.jjidea.vcs.diffbase.DiffbaseService
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.history.JujutsuFileRevision
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides file annotations (blame) for Jujutsu files
 */
class JujutsuAnnotationProvider(
    private val project: Project,
    private val vcs: JujutsuVcsBase,
    private val nowMs: () -> Long = System::currentTimeMillis
) : AnnotationProvider,
    AnnotationProviderEx,
    CacheableAnnotationProvider {
    private val log = Logger.getInstance(javaClass)

    // Written from background preloader threads (jj-idea-a921): must be a thread-safe map, not a
    // plain HashMap. Cleared wholesale on any working-copy change below, since every cached
    // FileAnnotation is computed against @- (the working-copy parent) and would otherwise go
    // stale silently.
    private val cache = ConcurrentHashMap<VirtualFile, FileAnnotation>()

    // Per-repository rolling average of annotate durations (jj-idea-1sza), keyed by
    // repo.directory.path. Also thread-safe: populated from background preloader threads.
    private val annotateTimings = ConcurrentHashMap<String, AnnotateTiming>()

    // Subscribed lazily (on first actual cache use, not in the constructor) so unit tests can
    // build a provider around a bare mock Project without stubbing the whole state-model service
    // lookup chain. AtomicBoolean.compareAndSet makes the one-time subscription thread-safe.
    private val invalidationSubscribed = AtomicBoolean(false)

    private fun ensureCacheInvalidationSubscribed() {
        if (invalidationSubscribed.compareAndSet(false, true)) {
            project.stateModel.workingCopies.connect(project) { _ -> cache.clear() }
            // jj-idea-fwea: every cached FileAnnotation for @ is computed against the
            // configured diff base and would otherwise go stale silently when it changes.
            project.stateModel.diffbaseChanged.connect(project) { cache.clear() }
        }
    }

    override fun populateCache(file: VirtualFile) {
        if (project.isDisposed) return
        // Skip files that have no jj history: ignored files and unversioned files both lack
        // a parent-revision entry, so jj annotate fails with "No such path".
        val status = ChangeListManager.getInstance(project).getStatus(file)
        if (status == FileStatus.IGNORED || status == FileStatus.UNKNOWN) return
        ensureCacheInvalidationSubscribed()
        try {
            // jj-idea-1sza: on a repo where annotate is consistently slow, eagerly preloading
            // every opened file burns a long-running jj process the user may never need (the
            // gutter is opened on demand via annotate(file) below, which is never gated by this
            // check).
            val repo = project.jujutsuRepositoryFor(file)
            if (isPreloadBackedOff(repo)) {
                log.debug("Skipping annotation preload for ${file.path}: annotate has been slow on this repository")
                return
            }
            cache[file] = annotate(file)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to populate annotation cache for ${file.path}", e)
        }
    }

    override fun getFromCache(file: VirtualFile): FileAnnotation? {
        ensureCacheInvalidationSubscribed()
        return cache[file]
    }

    /**
     * Test seam (jj-idea-a921): lets regression tests observe/populate the annotation cache
     * directly, without driving the full [annotate] resolution path (content locator, log
     * service, working-copy lookup, etc.).
     */
    internal fun cacheForTest(): MutableMap<VirtualFile, FileAnnotation> = cache

    /**
     * Annotate a file at the working copy parent (@-), matching the LineStatusTracker base.
     * Lines changed in @ appear unannotated; IntelliJ's UpToDateLineNumberProvider handles the mapping.
     *
     * When a custom diff base is configured (jj-idea-fwea / GitHub #43), annotate at that
     * revision instead, via [DiffbaseService] — the same service [in.kkkev.jjidea.vcs.diffbase.DiffbaseContentLoader]
     * uses for the LineStatusTracker base, so the two never disagree and blame lines stay aligned.
     */
    override fun annotate(file: VirtualFile): FileAnnotation {
        val repo = project.jujutsuRepositoryFor(file)

        // 1. Find the content locator
        val contentLocator = file.contentLocator

        if (contentLocator is WorkingCopy) {
            DiffbaseService.getInstance(project).resolve(repo)?.let { base ->
                repo.getVirtualFile(FileAtVersion(file.filePath, base))?.let { baseFile ->
                    return annotateInternal(baseFile, base, repo)
                }
            }
        }

        // 2. Find the change object
        val change = repo.logService.getFileChanges(repo.getLogEntry(contentLocator) ?: repo.workingCopy, file.filePath)
            .getOrNull()?.firstOrNull()

        // 3. Locate the revision and file path of the before
        // TODO: If we can't find the log entry, it's probably a merge parent - so default to parent for now
        // If there is no change, then can just use the parent
        val before = change?.before ?: FileAtVersion(file.filePath, repo.workingCopy.parentContentLocator)
        val beforeFile = repo.getVirtualFile(before)
            ?: throw VcsException("Cannot find virtual file for $before")

        // TODO Or for a rename, the filename would have changed
        // If we get this information from a change... that's great... but we can annotate files too
        // In that case, we need to find the change object from a working copy virtual file
        (before.contentLocator as? MergeParentOf)?.let { mergeParentOf ->
            annotateMerge(beforeFile, mergeParentOf, repo)?.let { return it }
        }

        val beforeRevision = beforeRevisionFor(before.contentLocator, contentLocator, repo)
        return annotateInternal(beforeFile, beforeRevision, repo)
    }

    /**
     * Annotates a merge commit's auto-merged parent tree by annotating each real parent and
     * reconciling their blame via [MergeAnnotationReconciler], so the resulting line count
     * matches the actual resolved file (only genuine conflict-resolution edits show as
     * unattributed, rather than the whole file diverging from one arbitrary parent).
     *
     * A parent that doesn't have the file at all (e.g. it was added on only one side of a
     * criss-cross merge) is skipped rather than aborting the whole reconciliation — jj correctly
     * reports "No such path" for that parent, but the *other* parent(s) can still supply blame.
     *
     * Returns null if reconstructing the merge tree fails, or if *no* parent could be annotated,
     * so the caller can fall back to [beforeRevisionFor]'s arbitrary-first-parent behavior rather
     * than failing outright.
     */
    internal fun annotateMerge(
        file: VirtualFile,
        mergeParentOf: MergeParentOf,
        repo: JujutsuRepository
    ): FileAnnotation? {
        val childRevision = mergeParentOf.childRevision
        return try {
            val mergeCommit = repo.getLogEntry(childRevision)
            val mergeContent = repo.reconstructMergeParentContent(childRevision, file.filePath)
            val parentAnnotations = mergeCommit.parentIds.mapNotNull { parentId ->
                annotationLinesOrNull(file, parentId, repo, childRevision)
            }
            if (parentAnnotations.isEmpty()) {
                log.warn("No parent of merge $childRevision could be annotated for $file, falling back to first parent")
                return null
            }
            val annotationLines = MergeAnnotationReconciler.reconcile(mergeContent, mergeCommit, parentAnnotations)

            JujutsuFileAnnotation(
                project = project,
                repo = repo,
                file = file,
                annotationLines = annotationLines,
                vcsKey = vcs.keyInstanceMethod,
                workingCopyChangeId = repo.workingCopy.id
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to reconcile merge annotation for $childRevision, falling back to first parent", e)
            null
        }
    }

    /** [getAnnotationLines], but null (rather than throwing) if this specific parent lacks the file. */
    private fun annotationLinesOrNull(
        file: VirtualFile,
        parentId: Revision,
        repo: JujutsuRepository,
        childRevision: Revision
    ): List<AnnotationLine>? = try {
        getAnnotationLines(file, parentId, repo)
    } catch (e: VcsException) {
        log.warn("Failed to annotate parent $parentId of merge $childRevision, treating as absent in that parent", e)
        null
    }

    /**
     * `jj file annotate` requires a single revision, but a merge commit's "before" is
     * [MergeParentOf] — a synthetic reconstruction of the auto-merged tree, not a real revision
     * jj can annotate against. Used as a fallback (arbitrary first parent) when
     * [annotateMerge] can't reconcile a full multi-parent annotation.
     */
    internal fun beforeRevisionFor(
        beforeLocator: ContentLocator,
        contentLocator: ContentLocator,
        repo: JujutsuRepository
    ): Revision {
        (beforeLocator as? Revision)?.let { return it }
        (beforeLocator as? MergeParentOf)?.let { mergeParent ->
            repo.getLogEntry(mergeParent.childRevision).parentIds.firstOrNull()?.let { return it }
        }
        return ((contentLocator as? Revision) ?: WorkingCopy).parent
    }

    /** Annotate a file at a specific revision (used for "Annotate This/Previous Revision"). */
    override fun annotate(file: VirtualFile, revision: VcsFileRevision?): FileAnnotation {
        val repo = project.jujutsuRepositoryFor(file)
        val revisionId = (revision as? JujutsuFileRevision)?.entry?.id
            ?: (revision?.revisionNumber as? ChangeIdRevisionNumber)?.changeId
            ?: repo.workingCopy.id
        return annotateInternal(file, revisionId, repo)
    }

    override fun isAnnotationValid(rev: VcsFileRevision) = true

    /**
     * Entry point for the platform's built-in Annotate action on a [com.intellij.openapi.vcs.vfs.VcsVirtualFile]
     * (e.g. a file opened from the log or the File History panel) — see [com.intellij.vcs.AnnotationProviderEx].
     * Unlike [annotate], which deliberately annotates at `@-` to match the LineStatusTracker base, this
     * annotates *at* the given revision, matching [annotate]'s revision-argument overload above.
     */
    override fun annotate(path: FilePath, revision: VcsRevisionNumber): FileAnnotation {
        val repo = project.jujutsuRepositoryFor(path)
        val locator = revision.contentLocator
        val file = repo.getVirtualFile(FileAtVersion(path, locator))
            ?: throw VcsException("Cannot find virtual file for $path at $locator")

        (locator as? MergeParentOf)?.let { mergeParentOf ->
            annotateMerge(file, mergeParentOf, repo)?.let { return it }
        }

        return annotateInternal(file, (locator as? Revision) ?: repo.workingCopy.id, repo)
    }

    /** Runs `jj file annotate` for a single revision and parses the result. */
    private fun getAnnotationLines(
        file: VirtualFile,
        revision: Revision,
        repo: JujutsuRepository
    ): List<AnnotationLine> {
        val startMs = nowMs()
        val result = repo.commandExecutor.annotate(file, revision, AnnotationParser.TEMPLATE)
        // Record even on failure/timeout (but not if the call above threw, e.g. cancellation): a
        // timed-out annotate's CommandResult is the honest ~120s cost and should count toward the
        // preload backoff decision (jj-idea-1sza) just as much as a slow success would.
        annotateTimings.computeIfAbsent(repo.directory.path) { AnnotateTiming() }.record(nowMs() - startMs)
        if (result !is CommandExecutor.CommandResult.Success) {
            val message = if (result is CommandExecutor.CommandResult.Failure.TimedOut) {
                JujutsuBundle.message("annotation.error.timeout", file.name)
            } else {
                JujutsuBundle.message("annotation.error.failed", file.name, result.stderr)
            }
            log.warn(message)
            throw VcsException(message)
        }
        return AnnotationParser.parse(result.stdout)
    }

    /**
     * True once [repo]'s rolling average `jj file annotate` duration has crossed the preload
     * backoff threshold (jj-idea-1sza). Narrow seam so the scale test can assert on the preload
     * decision without driving the full [annotate] resolution path (content locator, log
     * service, etc.) — see contributing.md's "Writing a scale test".
     */
    internal fun isPreloadBackedOff(repo: JujutsuRepository) = annotateTimings[repo.directory.path]?.isSlow == true

    /**
     * Tracks a rolling (exponential moving) average of `jj file annotate` durations for one
     * repository, so [populateCache] can back off eager preloading once annotate is consistently
     * slow (jj-idea-1sza). An EMA (rather than a cumulative mean) lets the tracker recover and
     * resume preloading if annotate later speeds up. Thread-safe: recorded from background
     * preloader threads.
     */
    private class AnnotateTiming {
        @Volatile private var averageMs = 0.0

        @Volatile private var samples = 0

        @Synchronized
        fun record(durationMs: Long) {
            averageMs = if (samples == 0) {
                durationMs.toDouble()
            } else {
                EMA_ALPHA * durationMs + (1 - EMA_ALPHA) * averageMs
            }
            samples++
        }

        val isSlow: Boolean get() = samples > 0 && averageMs >= PRELOAD_BACKOFF_THRESHOLD_MS
    }

    companion object {
        // A healthy `jj file annotate` is ~1s; 5s sustained means each opened file costs 5s+ of a
        // jj process, not worth eager preloading when the gutter may never be opened. Well below
        // the 120s annotateTimeout (CliExecutor.kt), so any timeout trivially trips backoff.
        private const val PRELOAD_BACKOFF_THRESHOLD_MS = 5_000.0

        // Weight new samples equally with history so backoff engages/recovers within a couple of
        // file opens rather than being dragged out by a long history of fast samples.
        private const val EMA_ALPHA = 0.5
    }

    internal fun annotateInternal(
        file: VirtualFile,
        revision: Revision,
        repo: JujutsuRepository
    ): FileAnnotation = try {
        val annotationLines = getAnnotationLines(file, revision, repo)

        JujutsuFileAnnotation(
            project = project,
            repo = repo,
            file = file,
            annotationLines = annotationLines,
            vcsKey = vcs.keyInstanceMethod,
            workingCopyChangeId = repo.workingCopy.id
        )
    } catch (e: VcsException) {
        throw e
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: CancellationException) {
        // ContainerDisposedException (raised when the working copy / state model is queried while the
        // project is being disposed on window close) is a CancellationException, not a
        // ProcessCanceledException. Rethrow control-flow exceptions rather than logging them —
        // Logger.error() rethrows them anyway and reports a spurious error in the process.
        throw e
    } catch (e: Exception) {
        log.error("Error during annotation", e)
        throw VcsException("Failed to annotate file: ${e.message}", e)
    }
}
