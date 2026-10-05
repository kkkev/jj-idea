package `in`.kkkev.jjidea.vcs

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsTaskHandler
import com.intellij.openapi.vcs.changes.ChangeListManager
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult

/**
 * IntelliJ Tasks integration (`vcs.taskHandler`, jj-idea-n3u0, GitHub #102). A task maps to a bookmark:
 * the [TaskInfo] name is the bookmark name, so the name the user types, the name Tasks persists and the
 * name used to push are the same string. Repositories are identified by [JujutsuRepository.directory] path.
 *
 * - start: `jj new` (unless `@` is already blank) then `jj bookmark create`, carrying the task's commit
 *   message (Tasks writes it to the default changelist comment first) as the description
 * - switch: `jj edit` the newest mutable head of the bookmark's own stack, or `jj new` on top when immutable.
 *   There is no shelving: jj snapshots a dirty `@`
 * - close (only when Tasks' "Merge branch" is ticked): asks how to integrate - rebase the task's whole stack onto
 *   the original, a merge change, or just switch back - and advances the bookmarks (see [integrateTask]). The
 *   task bookmark is never deleted
 *
 * Listing reads only cached state ([JujutsuStateModel.references] / [JujutsuStateModel.closestBookmarks]),
 * so it runs no jj process and is safe on the EDT.
 */
class JujutsuTaskHandler(private val project: Project) : VcsTaskHandler() {
    override fun isEnabled() = project.isJujutsu

    override fun isSyncEnabled() = true

    override fun getAllExistingTasks(): Array<TaskInfo> {
        val refs = project.stateModel.references.value.mapKeys { it.key.directory.path }
        return taskInfosFromBookmarks(refs.mapValues { it.value.bookmarks }).toTypedArray()
    }

    override fun getCurrentTasks(): Array<TaskInfo> {
        val closest = project.stateModel.closestBookmarks.value.mapKeys { it.key.directory.path }
        return currentTaskInfos(closest.mapValues { it.value?.names.orEmpty() }).toTypedArray()
    }

    override fun startNewTask(taskName: String): TaskInfo {
        val repos = project.initialisedJujutsuRepositories.toList()
        val comment = Description(
            runCatching {
                ChangeListManager.getInstance(project).defaultChangeList.comment
            }.getOrNull().orEmpty().trim()
        )
        val references = project.stateModel.references.value
        for (repo in repos) {
            val items = references[repo]?.bookmarks.orEmpty()
            if (items.any { !it.bookmark.isRemote && it.bookmark.localName == taskName }) {
                switchRepo(repo, taskName, items, null)
                continue
            }
            // Read `@` fresh: Tasks may have just switched to the "from" bookmark (creating a change), and the
            // cached working copy would still show the old `@`, leaving an extra empty change behind.
            repo.createCommand {
                val wc = repo.logService.getLogBasic(WorkingCopy, limit = 1).getOrNull()?.firstOrNull()
                startTask(BookmarkName(taskName), comment, blankWorkingCopy = wc != null && wc.isBlank)
            }
                .onSuccess { invalidate(select = WorkingCopy, vfsChanged = true) }
                .onFailure { tellUser("task.start.error") }
                .executeAsync()
        }
        return TaskInfo(taskName, repos.map { it.directory.path })
    }

    override fun switchToTask(taskInfo: TaskInfo, invokeAfter: Runnable?): Boolean {
        val name = taskInfo.name ?: return false
        val repos = project.initialisedJujutsuRepositories.filter { it.directory.path in taskInfo.repositories }
        if (repos.isEmpty()) return false
        val references = project.stateModel.references.value
        var pending = repos.size
        var failed = false
        for (repo in repos) {
            switchRepo(repo, name, references[repo]?.bookmarks.orEmpty()) { ok ->
                failed = failed || !ok
                if (--pending == 0 && !failed) invokeAfter?.run()
            }
        }
        return true
    }

    override fun closeTask(taskInfo: TaskInfo, original: TaskInfo) {
        val task = taskInfo.name
        val origin = original.name
        if (origin == null) return
        if (task == null) {
            switchToTask(original, null)
            return
        }
        val mode = chooseCloseMode(project, task, origin) ?: return
        val repos = project.initialisedJujutsuRepositories.filter { it.directory.path in taskInfo.repositories }
        for (repo in repos) {
            repo.createCommand { integrateTask(repo.logService, task, origin, mode) }
                .onSuccess { invalidate(select = WorkingCopy, vfsChanged = true) }
                .onFailure { tellUser("task.close.error") }
                .executeAsync()
        }
    }

    override fun isBranchNameValid(branchName: String) = isValidTaskName(branchName)

    override fun cleanUpBranchName(suggestedName: String) = cleanUpTaskName(suggestedName)

    /** [onDone] runs on the EDT with whether the switch succeeded. */
    private fun switchRepo(
        repo: JujutsuRepository,
        name: String,
        bookmarks: List<BookmarkItem>,
        onDone: ((Boolean) -> Unit)?
    ) {
        val immutable = bookmarks.any { it.bookmark.localName == name && it.immutable }
        repo.createCommand {
            val target = repo.logService.getLogBasic(Expression(switchTargetRevset(name, immutable)), limit = 1)
                .getOrNull()
                ?.firstOrNull()
            switchToTarget(BookmarkName(name), target)
        }
            .onSuccess {
                invalidate(select = WorkingCopy, vfsChanged = true)
                onDone?.invoke(true)
            }
            .onFailure {
                tellUser("task.switch.error")
                onDone?.invoke(false)
            }
            .addUndoTracking("task.switch.undo")
            .executeAsync()
    }
}

/** Empty and undescribed: nothing to preserve, so a task can claim it rather than stacking another change. */
internal val LogEntry.isBlank get() = isEmpty && description.empty

private const val NAME_PATTERN = "[A-Za-z0-9_/]+(?:[.+-][A-Za-z0-9_/]+)*"
private val validTaskName = Regex(NAME_PATTERN)
private val invalidChars = Regex("[^A-Za-z0-9_/.+-]")
private val separatorRuns = Regex("([.+-])[.+-]+")

/** jj's unquoted revset symbol grammar: no spaces or `@`, no leading, trailing or doubled separators. */
internal fun isValidTaskName(name: String) = validTaskName.matches(name)

internal fun cleanUpTaskName(name: String) = name
    .replace(invalidChars, "-")
    .replace(separatorRuns, "$1")
    .trim('.', '+', '-')

/** Local bookmarks, plus remote-only ones (flagged `isRemote`, like git's remote branches), grouped across repos. */
internal fun taskInfosFromBookmarks(bookmarksByRepo: Map<String, List<BookmarkItem>>): List<VcsTaskHandler.TaskInfo> {
    data class Found(val repos: MutableSet<String> = linkedSetOf(), var remote: Boolean = true)
    val found = sortedMapOf<String, Found>()
    for ((repo, items) in bookmarksByRepo) {
        val usable = items.map { it.bookmark }.filterNot { it.deleted || it.remote == "git" }
        val local = usable.filterNot { it.isRemote }.map { it.localName }.toSet()
        for (b in usable) {
            if (b.isRemote && b.localName in local) continue
            val f = found.getOrPut(b.localName) { Found() }
            f.repos += repo
            f.remote = f.remote && b.isRemote
        }
    }
    return found.map { (name, f) -> VcsTaskHandler.TaskInfo(name, f.repos, f.remote) }
}

/** Bookmarks nearest each repo's `@`, grouped across repos. */
internal fun currentTaskInfos(closestByRepo: Map<String, List<BookmarkName>>): List<VcsTaskHandler.TaskInfo> {
    val byName = linkedMapOf<String, MutableList<String>>()
    for ((repo, names) in closestByRepo) {
        for (n in names) byName.getOrPut(n.name) { mutableListOf() } += repo
    }
    return byName.map { (name, repos) -> VcsTaskHandler.TaskInfo(name, repos) }
}

private fun quoted(name: String) = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * The change to land on when switching to task [name]: the newest head of the bookmark's own stack,
 * stopping short of descendants of any other bookmark. An [immutable] bookmark (e.g. trunk) has no
 * stack of its own, so the target is the bookmark itself (and the switch becomes `jj new` on top).
 * Remote-only bookmarks are included so a task can be started from one.
 */
internal fun switchTargetRevset(name: String, immutable: Boolean): String {
    val q = quoted(name)
    val base = "(bookmarks(exact:$q) | remote_bookmarks(exact:$q))"
    if (immutable) return base
    return "latest(heads($base:: ~ (($base+:: & bookmarks())::)))"
}

/** `jj new` on top of an immutable [target]; otherwise `jj edit` it, or the bookmark itself if unresolved. */
internal fun CommandExecutor.switchToTarget(bookmark: BookmarkName, target: LogEntry?): CommandResult =
    if (target?.immutable == true) {
        new(Description.EMPTY, listOf(target.commitId))
    } else {
        edit(target?.commitId ?: bookmark)
    }

/**
 * Starts a task: describes a [blankWorkingCopy] `@` with [description] (when given) and bookmarks it,
 * otherwise creates a new change (carrying [description]) and bookmarks that. Stops at the first failure.
 */
internal fun CommandExecutor.startTask(
    bookmark: BookmarkName,
    description: Description,
    blankWorkingCopy: Boolean
): CommandResult {
    val prepared = if (blankWorkingCopy) {
        if (description.empty) null else describe(description)
    } else {
        new(description)
    }
    if (prepared is CommandResult.Failure) return prepared
    return bookmarkCreate(bookmark)
}

/** How closing a task integrates it into the task it was started from. */
internal enum class CloseMode {
    /** Rebase the task's whole stack onto the original, then fast-forward a mutable original. */
    REBASE,

    /** A "Merge <task>" change with both as parents, then fast-forward a mutable original. */
    MERGE,

    /** Only move `@` back to the original. */
    SWITCH_BACK
}

private const val CLOSE_MODE_KEY = "jj.tasks.closeMode"

/** The remembered choice, else asks (EDT). `null` means the user cancelled. */
private fun chooseCloseMode(project: Project, task: String, original: String): CloseMode? {
    val props = PropertiesComponent.getInstance()
    CloseMode.entries.find { it.name == props.getValue(CLOSE_MODE_KEY) }?.let { return it }
    val remember = object : DoNotAskOption.Adapter() {
        override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
            val chosen = CloseMode.entries.getOrNull(exitCode)
            if (isSelected && chosen != null) props.setValue(CLOSE_MODE_KEY, chosen.name)
        }

        override fun getDoNotShowMessage() = JujutsuBundle.message("task.close.remember")
    }
    val options = arrayOf(
        JujutsuBundle.message("task.close.rebase", original),
        JujutsuBundle.message("task.close.merge"),
        JujutsuBundle.message("task.close.switch")
    )
    // MessageDialogBuilder rather than Messages.showDialog: the latter's DoNotAskOption overload only exists on 2025.3+
    val chosen = MessageDialogBuilder.Message(
        JujutsuBundle.message("task.close.title"),
        JujutsuBundle.message("task.close.message", task, original)
    )
        .buttons(*options)
        .defaultButton(options[0])
        .icon(Messages.getQuestionIcon())
        .doNotAsk(remember)
        .show(project)
    return CloseMode.entries.getOrNull(options.indexOf(chosen))
}

private fun namedBookmark(name: String) = "bookmarks(exact:${quoted(name)})"

/**
 * Closes [task] into [original] in [mode], stopping at the first failure:
 * 1. move the task bookmark forward to its stack head (bookmarks don't follow `jj new`, and a rebase carries a
 *    bookmark with the commits, so this makes the integration include all the work)
 * 2. rebase `roots(original..task)` onto the original (the whole stack, not just the tip), or create the merge change
 *    (plus a fresh `@` on top)
 * 3. fast-forward the original bookmark to the result - only when it is a mutable local bookmark; immutable or
 *    remote-only trunk is never moved
 * 4. [REBASE][CloseMode.REBASE] and [SWITCH_BACK][CloseMode.SWITCH_BACK] end by switching `@` back to the original
 */
internal fun CommandExecutor.integrateTask(
    logService: LogService,
    task: String,
    original: String,
    mode: CloseMode
): CommandResult {
    val originalBookmark = BookmarkName(original)
    val taskRef = BookmarkName(task)
    val originalEntry = logService.getLogBasic(
        Expression(namedBookmark(original)),
        limit = 1
    ).getOrNull()?.firstOrNull()
    val originalMutable = originalEntry != null && !originalEntry.immutable
    val originalRev = RevisionExpression("latest(${switchTargetRevset(original, immutable = true)})")

    var tip: Revision = taskRef
    if (mode != CloseMode.SWITCH_BACK) {
        val head = logService.getLogBasic(Expression(switchTargetRevset(task, immutable = false)), limit = 1)
            .getOrNull()?.firstOrNull()
        if (head != null) {
            (bookmarkSet(taskRef, head.commitId) as? CommandResult.Failure)?.let { return it }
        }
        val integrated = if (mode == CloseMode.REBASE) {
            rebase(
                listOf(RevisionExpression("roots($originalRev..${namedBookmark(task)})")),
                listOf(originalRev),
                RebaseSourceMode.SOURCE
            )
        } else {
            val merged = new(Description("Merge $task"), listOf(originalRev, taskRef))
            tip = RevisionExpression("@-")
            if (merged is CommandResult.Failure) merged else new(Description.EMPTY)
        }
        if (integrated is CommandResult.Failure) return integrated
        if (originalMutable) {
            (bookmarkSet(originalBookmark, tip) as? CommandResult.Failure)?.let { return it }
        }
        if (mode == CloseMode.MERGE) return integrated
    }
    val target = logService.getLogBasic(
        Expression(switchTargetRevset(original, immutable = originalEntry?.immutable ?: true)),
        limit = 1
    ).getOrNull()?.firstOrNull()
    return switchToTarget(originalBookmark, target)
}
