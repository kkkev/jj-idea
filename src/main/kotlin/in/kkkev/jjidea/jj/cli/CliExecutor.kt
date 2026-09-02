package `in`.kkkev.jjidea.jj.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.addAllIfNotNull
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.jj.cli.Reversibility.IRREVERSIBLE
import `in`.kkkev.jjidea.jj.cli.Reversibility.READ_ONLY
import `in`.kkkev.jjidea.jj.cli.Reversibility.REVERSIBLE
import `in`.kkkev.jjidea.vcs.pathRelativeTo
import `in`.kkkev.jjidea.vcs.relativeTo
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal fun statusArgs() = JjInvocation(READ_ONLY, "status")

internal fun diffArgs(filePath: String) = JjInvocation(READ_ONLY, "diff", filePath.toFileset())

internal fun diffSummaryArgs(revision: Revision, filePath: FilePath?, root: VirtualFile?) = JjInvocation(
    READ_ONLY,
    listOfNotNull("diff", "--summary", "-r", revision.toString(), filePath?.relativeTo(root!!)?.toFileset())
)

internal fun diffSummaryBetweenArgs(from: ContentLocator, to: ContentLocator, filePath: FilePath?, root: VirtualFile?) =
    JjInvocation(
        READ_ONLY,
        listOfNotNull(
            "diff",
            "--summary",
            "--from",
            from.toString(),
            "--to",
            to.toString(),
            filePath?.relativeTo(root!!)?.toFileset()
        )
    )

internal fun showArgs(filePath: FilePath, revision: Revision, root: VirtualFile?) =
    JjInvocation(READ_ONLY, "file", "show", "-r", revision, filePath.relativeTo(root!!).toFileset())

internal fun versionArgs() = JjInvocation(READ_ONLY, "--version")

/** `jj git init` is purely local repo bootstrap - no remote involved, unlike [gitCloneArgs]. */
internal fun gitInitArgs(colocate: Boolean) =
    JjInvocation(IRREVERSIBLE, listOfNotNull("git", "init", "--colocate".takeIf { colocate }))

internal fun describeArgs(description: Description, revision: Revision) =
    JjInvocation(REVERSIBLE, "describe", "-r", revision, "--message=${description.actual}")

internal fun abandonArgs(revision: Revision) = JjInvocation(REVERSIBLE, "abandon", "-r", revision)

internal fun editArgs(revision: Revision) = JjInvocation(REVERSIBLE, "edit", revision)

internal fun tagListArgs(template: String? = null) = JjInvocation(
    READ_ONLY,
    buildList {
        add("tag")
        add("list")
        if (template != null) {
            add("-T")
            add(template)
        }
    }
)

internal fun diffGitArgs(revision: Revision) = JjInvocation(READ_ONLY, "diff", "--git", "-r", revision)

internal fun diffGitFileArgs(revision: Revision, filePath: FilePath, root: VirtualFile?) = JjInvocation(
    READ_ONLY,
    "diff",
    "--git",
    "-r",
    revision,
    "--",
    filePath.relativeTo(root!!).toFileset()
)

internal fun restoreArgs(filePaths: List<FilePath>, revision: Revision, root: VirtualFile?) = JjInvocation(
    REVERSIBLE,
    listOf("restore", "-f", revision.toString()) + filePaths.map { it.relativeTo(root!!).toFileset() }
)

internal fun gitRemoteListArgs() = JjInvocation(READ_ONLY, "git", "remote", "list")

internal fun configGetArgs(key: String) =
    JjInvocation(READ_ONLY, buildList { addAllIfNotNull("config", "get", key) })

internal fun configListArgs(key: String?, scope: CommandExecutor.ConfigScope?) =
    JjInvocation(READ_ONLY, buildList { addAllIfNotNull("config", "list", scope?.param, key) })

/**
 * `jj config set`/`unset` never touch the operation log at all (verified: the op log head is
 * unchanged after `jj config set --repo ...`) - config lives in a separate TOML file, not repo
 * state, so there is never anything for `op revert` to invert.
 */
internal fun configSetUserArgs(scope: CommandExecutor.ConfigScope, key: String, value: String) =
    JjInvocation(IRREVERSIBLE, "config", "set", scope.param, key, value)

internal fun configUnsetArgs(scope: CommandExecutor.ConfigScope, key: String) =
    JjInvocation(IRREVERSIBLE, "config", "unset", scope.param, key)

internal fun bookmarkCreateArgs(name: BookmarkName, revision: Revision = WorkingCopy) =
    JjInvocation(REVERSIBLE, "bookmark", "create", name, "-r", revision)

internal fun bookmarkDeleteArgs(name: BookmarkName) = JjInvocation(REVERSIBLE, "bookmark", "delete", name.toString())

internal fun bookmarkForgetArgs(name: BookmarkName) = JjInvocation(REVERSIBLE, "bookmark", "forget", name.toString())

internal fun bookmarkRenameArgs(oldName: BookmarkName, newName: BookmarkName) =
    JjInvocation(REVERSIBLE, "bookmark", "rename", oldName, newName)

/**
 * Builds `jj bookmark track NAME... --remote REMOTE`. All [names] must share the same remote.
 * IRREVERSIBLE - verified: `op revert --what repo` on a track/untrack op reports "Nothing
 * changed.", since tracking is entirely remote-tracking-bookmark state, the scope `--what repo`
 * deliberately excludes.
 */
internal fun bookmarkTrackArgs(names: List<BookmarkName>) = JjInvocation(
    IRREVERSIBLE,
    listOf("bookmark", "track") + names.map { it.localName } + listOf("--remote", names.first().remote)
)

internal fun bookmarkUntrackArgs(name: BookmarkName) =
    JjInvocation(IRREVERSIBLE, "bookmark", "untrack", name.localName, "--remote", name.remote)

/**
 * Build the argument list for `jj bookmark list`. Always includes untracked remote-only
 * bookmarks (`--all-remotes`) unless [remote] scopes the listing to one specific remote — `jj`
 * rejects passing both flags together.
 */
internal fun bookmarkListArgs(
    template: String? = null,
    remote: Remote? = null,
    tracked: Boolean = false,
    revision: Revision? = null
) = JjInvocation(
    READ_ONLY,
    buildList {
        add("bookmark")
        add("list")
        if (tracked) add("--tracked")
        if (remote != null) {
            add("--remote")
            add(remote.name)
        } else {
            add("--all-remotes")
        }
        if (revision != null) {
            add("-r")
            add("::$revision")
        }
        if (template != null) {
            add("-T")
            add(template)
        }
    }
)

internal fun bookmarkSetArgs(name: BookmarkName, revision: Revision = WorkingCopy, allowBackwards: Boolean = false) =
    JjInvocation(
        REVERSIBLE,
        buildList {
            addAll(listOf("bookmark", "set", name.toString(), "-r", revision.toString()))
            if (allowBackwards) add("-B")
        }
    )

/**
 * Build `jj bookmark advance --to`, moving bookmarks forward to [to]. With an empty [names], jj
 * advances every bookmark eligible per `revsets.bookmark-advance-from`; a non-empty [names]
 * restricts it to those bookmarks (jj then ignores the `bookmark-advance-from` revset for them).
 * Requires jj 0.39+ ([in.kkkev.jjidea.jj.JjFeature.BOOKMARK_ADVANCE]) — callers must gate on that
 * before invoking.
 */
internal fun bookmarkAdvanceArgs(names: List<BookmarkName> = emptyList(), to: Revision = WorkingCopy) =
    JjInvocation(REVERSIBLE, listOf("bookmark", "advance", "--to", to.toString()) + names.map { it.toString() })

internal fun tagSetArgs(tag: Tag, revision: Revision = WorkingCopy, allowMove: Boolean = false) =
    JjInvocation(
        REVERSIBLE,
        buildList {
            addAll(listOf("tag", "set", tag.name, "-r", revision.toString()))
            if (allowMove) add("--allow-move")
        }
    )

internal fun tagDeleteArgs(tag: Tag) = JjInvocation(REVERSIBLE, "tag", "delete", tag.name)

/**
 * Build the argument list for `jj file track`. [paths] must already be relative to the repo root.
 *
 * `--include-ignored` is required to actually force-track a path matching `.gitignore` - without
 * it, `jj file track` silently no-ops on ignored paths (verified against jj 0.37.0). It's harmless
 * to always pass: confirmed no-op-but-successful on already-tracked, non-ignored, and even
 * nonexistent paths.
 */
internal fun fileTrackArgs(paths: List<String>) =
    JjInvocation(REVERSIBLE, listOf("file", "track", "--include-ignored") + paths.map { it.toFileset() })

/** Build the argument list for `jj file untrack`. [paths] must already be relative to the repo root. */
internal fun fileUntrackArgs(paths: List<String>) =
    JjInvocation(REVERSIBLE, listOf("file", "untrack") + paths.map { it.toFileset() })

/**
 * Build the argument list for `jj file list`. [paths] must already be relative to the repo root.
 * The only fully reliable way to determine tracked status - see docs/jj-track-untrack-model.md.
 */
internal fun fileListArgs(paths: List<String>) =
    JjInvocation(READ_ONLY, listOf("file", "list") + paths.map { it.toFileset() })

/** Build the argument list for `jj git fetch`. IRREVERSIBLE - verified: reverting a fetch leaves
 * `main@origin` still pointing at the fetched (now hidden) commit while local `main` moves back,
 * a visibly broken repo. */
internal fun gitFetchArgs(remote: Remote? = null, allRemotes: Boolean = false) = JjInvocation(
    IRREVERSIBLE,
    buildList {
        add("git")
        add("fetch")
        if (allRemotes) {
            add("--all-remotes")
        } else if (remote != null) {
            add("--remote")
            add(remote.name)
        }
    }
)

/**
 * Build the argument list for `jj git push`. IRREVERSIBLE - verified: `op revert --what repo`
 * reports "Nothing changed." since a push's effect is entirely on the remote and the
 * remote-tracking scope `--what repo` deliberately excludes.
 *
 * @param changeRevisions Revisions to push via repeated `--change` flags, each auto-generating its
 *   own `push-<change-id>`-style bookmark (see `git.push-bookmark-prefix`) — `jj git push -c A -c B`
 *   is natively supported and creates one bookmark per change. Mutually exclusive with
 *   [bookmark]/[allBookmarks]/[revision] — one of the four scopes below wins, in this precedence
 *   order.
 */
internal fun gitPushArgs(
    remote: Remote? = null,
    bookmark: Bookmark? = null,
    allBookmarks: Boolean = false,
    changeRevisions: List<Revision> = emptyList(),
    revision: Revision? = null,
    dryRun: Boolean = false
) = JjInvocation(
    IRREVERSIBLE,
    buildList {
        add("git")
        add("push")
        if (remote != null) {
            add("--remote")
            add(remote.name)
        }
        when {
            allBookmarks -> add("--all")
            bookmark != null -> {
                add("--bookmark")
                add(bookmark.name.name)
            }

            changeRevisions.isNotEmpty() -> changeRevisions.forEach {
                add("--change")
                add(it.toString())
            }

            revision != null -> {
                add("-r")
                add(revision.toString())
            }
        }
        if (dryRun) add("--dry-run")
    }
)

/**
 * Build the argument list for `jj squash`.
 *
 * Emits `--` before the filesets, aligned with [squashIntoArgs] (needed there because `--from` is
 * repeatable and could otherwise swallow a fileset argument; kept here too for consistency, even
 * though `-r` doesn't have the same ambiguity).
 */
internal fun squashArgs(
    revision: Revision,
    filePaths: List<String> = emptyList(),
    description: Description? = null,
    keepEmptied: Boolean = false
) = JjInvocation(
    REVERSIBLE,
    buildList {
        add("squash")
        add("-r")
        add(revision.toString())
        if (description != null) {
            add("--message=${description.actual}")
        }
        if (keepEmptied) add("--keep-emptied")
        if (filePaths.isNotEmpty()) {
            add("--")
            addAll(filePaths.map { it.toFileset() })
        }
    }
)

internal fun resolveListArgs(revision: Revision = WorkingCopy) =
    JjInvocation(READ_ONLY, "resolve", "--list", "-r", revision.toString())

/** Build the argument list for `jj resolve --tool <tool>`. */
internal fun resolveArgs(paths: List<String>, tool: String, revision: Revision = WorkingCopy) = JjInvocation(
    REVERSIBLE,
    buildList {
        add("resolve")
        add("-r")
        add(revision.toString())
        add("--tool")
        add(tool)
        addAll(paths.map { it.toFileset() })
    }
)

/**
 * Build the argument list for `jj split`.
 *
 * [insertBefore], when set, adds `-B <revision>` (`--insert-before`): the selected fileset is
 * extracted into a new commit inserted before that revision, and the remaining changes stay on
 * the original commit's identity/location - the inverse of the no-flag default. `jj` itself
 * rejects `-B` combined with `--parallel`, so callers must not pass both.
 */
internal fun splitArgs(
    revision: Revision,
    filePaths: List<String> = emptyList(),
    description: Description? = null,
    parallel: Boolean = false,
    insertBefore: Revision? = null
) = JjInvocation(
    REVERSIBLE,
    buildList {
        add("split")
        add("-r")
        add(revision.toString())
        if (insertBefore != null) {
            add("-B")
            add(insertBefore.toString())
        }
        if (description != null) {
            add("--message=${description.actual}")
        }
        if (parallel) add("--parallel")
        addAll(filePaths.map { it.toFileset() })
    }
)

/**
 * Build the full argument list for `jj split --tool <tool>` (interactive diff-editor split).
 *
 * [configArgs] are `NAME=VALUE` strings emitted as `--config NAME=VALUE` **before** the
 * subcommand, since `--config` is a jj global option that must precede the subcommand.
 * When [tool] is set, `--tool <tool>` is added (which implies `--interactive`), and no
 * filesets are passed — the tool drives selection over the whole diff.
 */
internal fun splitInteractiveArgs(
    revision: Revision,
    description: Description? = null,
    parallel: Boolean = false,
    configArgs: List<String> = emptyList(),
    tool: String,
    insertBefore: Revision? = null
) = JjInvocation(
    REVERSIBLE,
    buildList {
        // Global --config flags before the subcommand.
        for (kv in configArgs) {
            add("--config")
            add(kv)
        }
        add("split")
        add("-r")
        add(revision.toString())
        if (insertBefore != null) {
            add("-B")
            add(insertBefore.toString())
        }
        if (description != null) add("--message=${description.actual}")
        if (parallel) add("--parallel")
        add("--tool=$tool")
    }
)

/** Build the argument list for `jj git clone`. IRREVERSIBLE - a remote operation, not repo-scope. */
internal fun gitCloneArgs(source: String, destination: String, colocate: Boolean) = JjInvocation(
    IRREVERSIBLE,
    buildList {
        add("git")
        add("clone")
        if (colocate) add("--colocate") else add("--no-colocate")
        add(source)
        add(destination)
    }
)

/** Build the argument list for `jj squash --from ... --into ...`. */
internal fun squashIntoArgs(
    sources: List<Revision>,
    destination: Revision,
    filePaths: List<String> = emptyList(),
    description: Description? = null,
    keepEmptied: Boolean = false
) = JjInvocation(
    REVERSIBLE,
    buildList {
        addAll(squashIntoPrefixArgs(sources, destination, description, keepEmptied))
        if (filePaths.isNotEmpty()) {
            add("--")
            addAll(filePaths.map { it.toFileset() })
        }
    }
)

/**
 * Build the full argument list for `jj squash --from <source> --into <destination> --tool <tool>`
 * (interactive diff-editor squash) — the squash analog of [splitInteractiveArgs].
 *
 * [configArgs] are `NAME=VALUE` strings emitted as `--config NAME=VALUE` **before** the
 * subcommand, since `--config` is a jj global option that must precede the subcommand. No
 * filesets are passed — the tool drives selection over the whole diff, exactly as for
 * [splitInteractiveArgs]. Single-source only — see [in.kkkev.jjidea.jj.CommandExecutor.
 * squashIntoInteractive].
 */
internal fun squashIntoInteractiveArgs(
    source: Revision,
    destination: Revision,
    description: Description? = null,
    keepEmptied: Boolean = false,
    configArgs: List<String> = emptyList(),
    tool: String
) = JjInvocation(
    REVERSIBLE,
    buildList {
        for (kv in configArgs) {
            add("--config")
            add(kv)
        }
        addAll(squashIntoPrefixArgs(listOf(source), destination, description, keepEmptied))
        add("--tool=$tool")
    }
)

/** Shared `squash --into <dest> --from <src>... [--message] [--keep-emptied]` prefix. */
private fun squashIntoPrefixArgs(
    sources: List<Revision>,
    destination: Revision,
    description: Description?,
    keepEmptied: Boolean
): List<String> = buildList {
    add("squash")
    add("--into")
    add(destination.toString())
    sources.forEach {
        add("--from")
        add(it.toString())
    }
    if (description != null) add("--message=${description.actual}")
    if (keepEmptied) add("--keep-emptied")
}

/**
 * Build the argument list for `jj new`.
 *
 * `jj new` has no `--onto` flag - with [destinationMode] `ONTO`, [parentRevisions] are emitted
 * as positional revsets; [RebaseDestinationMode.ONTO]'s `flag` string is ignored. With
 * `INSERT_AFTER`/`INSERT_BEFORE`, each revision is emitted as its own `-A`/`-B`.
 */
internal fun newArgs(
    description: Description,
    parentRevisions: List<Revision>,
    destinationMode: RebaseDestinationMode = RebaseDestinationMode.ONTO,
    edit: Boolean = true
) = JjInvocation(
    REVERSIBLE,
    buildList {
        add("new")
        if (!description.empty) add("--message=${description.actual}")
        if (!edit) add("--no-edit")
        when (destinationMode) {
            RebaseDestinationMode.ONTO -> addAll(parentRevisions.map { it.toString() })
            else -> parentRevisions.forEach {
                add(destinationMode.flag)
                add(it.toString())
            }
        }
    }
)

/** Build the argument list for `jj duplicate`. */
internal fun duplicateArgs(
    revisions: List<Revision>,
    destinations: List<Revision> = emptyList(),
    destinationMode: RebaseDestinationMode = RebaseDestinationMode.ONTO
) = JjInvocation(
    REVERSIBLE,
    buildList {
        add("duplicate")
        revisions.forEach { add(it.toString()) }
        destinations.forEach {
            add(destinationMode.flag)
            add(it.toString())
        }
    }
)

internal fun rebaseArgs(
    revisions: List<Revision>,
    destinations: List<Revision>,
    sourceMode: RebaseSourceMode = RebaseSourceMode.REVISION,
    destinationMode: RebaseDestinationMode = RebaseDestinationMode.ONTO
) = JjInvocation(
    REVERSIBLE,
    buildList {
        add("rebase")
        revisions.forEach {
            add(sourceMode.flag)
            add(it.toString())
        }
        destinations.forEach {
            add(destinationMode.flag)
            add(it.toString())
        }
    }
)

/**
 * Converts a completed [ProcessOutput] into a [CommandExecutor.CommandResult], distinguishing a
 * killed-by-timeout process from an ordinary failure. `runProcess(timeout)` destroys the process on
 * timeout without setting an exit code, so [ProcessOutput.getExitCode] alone can't tell the two apart —
 * and stderr is typically empty since the process never got to report anything. On timeout we
 * synthesize a stderr message naming the command and the limit, so callers (e.g. annotate) have
 * something real to show the user instead of a blank string.
 *
 * On success, [reversibility] decides which [CommandExecutor.CommandResult.Success.Irreversible.Reason]
 * an untracked success carries - undo tracking itself (matching a written operation to the command
 * that wrote it) is wired in a later change; every success here is [Irreversible][Reversibility]
 * until then.
 */
internal fun ProcessOutput.toCommandResult(
    cmdName: String,
    timeoutMillis: Long,
    reversibility: Reversibility
): CommandExecutor.CommandResult =
    if (isTimeout) {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeoutMillis)
        CommandExecutor.CommandResult.Failure.TimedOut(
            stdout = stdout,
            timeoutMillis = timeoutMillis,
            stderr = JujutsuBundle.message("cli.error.timeout", cmdName, seconds.toString())
        )
    } else if (exitCode == 0) {
        val reason = when (reversibility) {
            READ_ONLY -> CommandExecutor.CommandResult.Success.Irreversible.Reason.READ_ONLY
            IRREVERSIBLE -> CommandExecutor.CommandResult.Success.Irreversible.Reason.NOT_REVERSIBLE_COMMAND
            REVERSIBLE -> CommandExecutor.CommandResult.Success.Irreversible.Reason.NOT_TRACKED
        }
        CommandExecutor.CommandResult.Success.Irreversible(stdout, stderr, reason)
    } else {
        CommandExecutor.CommandResult.Failure.Exited(stdout, stderr, exitCode)
    }

/**
 * CLI-based implementation of JujutsuCommandExecutor
 */
class CliExecutor(
    private val root: VirtualFile?,
    private val executableProvider: () -> String = { "jj" },
    private val onJjNotFound: (() -> Unit)? = null
) : CommandExecutor {
    private val log = Logger.getInstance(javaClass)
    private val defaultTimeout = TimeUnit.SECONDS.toMillis(30)
    private val networkTimeout = TimeUnit.SECONDS.toMillis(120)

    // `jj file annotate` can take far longer than other commands on very large/deep-history repos
    // (see jj-idea-hpvu); give it the same generous budget as network operations rather than the
    // 30s default, which was observed to kill a ~100s annotate outright.
    private val annotateTimeout = TimeUnit.SECONDS.toMillis(120)

    companion object {
        /** Pattern to extract percentage from git progress output (e.g., "Receiving objects:  45% (123/456)") */
        private val PROGRESS_PATTERN = Regex("""(\d+)%""")

        /**
         * Creates a CliExecutor for operations that don't require an existing repository
         * (e.g., gitClone, isAvailable, version).
         */
        fun forRootlessOperations(executableProvider: () -> String = { "jj" }) =
            CliExecutor(root = null, executableProvider = executableProvider)
    }

    override fun status() = execute(root, statusArgs())

    override fun resolveList(revision: Revision) = execute(root, resolveListArgs(revision))

    override fun resolve(paths: List<String>, tool: String, revision: Revision) =
        execute(root, resolveArgs(paths, tool, revision))

    override fun diff(filePath: String) = execute(root, diffArgs(filePath))

    override fun diffSummary(revision: Revision, filePath: FilePath?) =
        execute(root, diffSummaryArgs(revision, filePath, root))

    override fun diffSummaryBetween(from: ContentLocator, to: ContentLocator, filePath: FilePath?) =
        execute(root, diffSummaryBetweenArgs(from, to, filePath, root))

    override fun show(filePath: FilePath, revision: Revision) = execute(root, showArgs(filePath, revision, root))

    override fun isAvailable() = try {
        val result = execute(null, versionArgs())
        result is CommandExecutor.CommandResult.Success
    } catch (e: Exception) {
        log.warn("Failed to check jj availability", e)
        false
    }

    override fun version() = try {
        val result = execute(null, versionArgs())
        if (result is CommandExecutor.CommandResult.Success) {
            result.stdout.trim()
        } else {
            null
        }
    } catch (e: Exception) {
        log.warn("Failed to get jj version", e)
        null
    }

    override fun gitInit(colocate: Boolean) = execute(root, gitInitArgs(colocate))

    override fun describe(description: Description, revision: Revision) =
        execute(root, describeArgs(description, revision))

    override fun new(
        description: Description,
        parentRevisions: List<Revision>,
        destinationMode: RebaseDestinationMode,
        edit: Boolean
    ): CommandExecutor.CommandResult = execute(root, newArgs(description, parentRevisions, destinationMode, edit))

    override fun abandon(revision: Revision): CommandExecutor.CommandResult = execute(root, abandonArgs(revision))

    override fun edit(revision: Revision): CommandExecutor.CommandResult = execute(root, editArgs(revision))

    override fun duplicate(
        revisions: List<Revision>,
        destinations: List<Revision>,
        destinationMode: RebaseDestinationMode
    ) = execute(root, duplicateArgs(revisions, destinations, destinationMode))

    override fun log(
        revset: Revset,
        template: String?,
        filePaths: List<FilePath>,
        limit: Int?,
        quiet: Boolean
    ): CommandExecutor.CommandResult {
        val args = mutableListOf<String>("log")
        if (revset !is Revset.Default) {
            args.add("-r")
            args.add(revset.toString())
        }
        args.add("--no-graph")
        if (template != null) {
            args.add("-T")
            args.add(template)
        }
        if (limit != null) {
            args.add("--limit")
            args.add(limit.toString())
        }
        args.addAll(filePaths.map { it.relativeTo(root!!).toFileset() })
        return execute(root, JjInvocation(READ_ONLY, args), warnOnFailure = !quiet)
    }

    override fun annotate(file: VirtualFile, revision: Revision, template: String?): CommandExecutor.CommandResult {
        val args = mutableListOf("file", "annotate", "-r", revision.toString())
        if (template != null) {
            args.add("-T")
            args.add(template)
        }
        // Unlike the other file/diff/split/squash/restore/resolve commands, `jj file annotate`
        // takes a single literal <PATH> argument, not a <FILESETS>... list - it is not parsed as
        // a fileset expression, so wrapping it in `cwd:"..."` would be wrong (jj would look for
        // a literal file named `cwd:"..."`). No escaping is needed here at all: this argument is
        // passed straight through as one argv entry (no shell involved), so meta-characters like
        // ()[] are already safe. Verified against jj 0.44 - see GitHub #73.
        args.add(file.pathRelativeTo(root!!))
        return execute(root, JjInvocation(READ_ONLY, args), timeout = annotateTimeout, warnOnFailure = false)
    }

    override fun tagList(template: String?): CommandExecutor.CommandResult = execute(root, tagListArgs(template))

    override fun tagSet(tag: Tag, revision: Revision, allowMove: Boolean) =
        execute(root, tagSetArgs(tag, revision, allowMove))

    override fun tagDelete(tag: Tag) = execute(root, tagDeleteArgs(tag))

    override fun bookmarkList(
        template: String?,
        remote: Remote?,
        tracked: Boolean,
        revision: Revision?
    ) = execute(root, bookmarkListArgs(template, remote, tracked, revision))

    override fun bookmarkCreate(name: BookmarkName, revision: Revision) =
        execute(root, bookmarkCreateArgs(name, revision))

    override fun bookmarkDelete(name: BookmarkName) = execute(root, bookmarkDeleteArgs(name))

    override fun bookmarkForget(name: BookmarkName) = execute(root, bookmarkForgetArgs(name))

    override fun bookmarkRename(oldName: BookmarkName, newName: BookmarkName) =
        execute(root, bookmarkRenameArgs(oldName, newName))

    override fun bookmarkSet(name: BookmarkName, revision: Revision, allowBackwards: Boolean) =
        execute(root, bookmarkSetArgs(name, revision, allowBackwards))

    override fun bookmarkAdvance(names: List<BookmarkName>, to: Revision) =
        execute(root, bookmarkAdvanceArgs(names, to))

    override fun bookmarkTrack(names: List<BookmarkName>) = execute(root, bookmarkTrackArgs(names))

    override fun bookmarkUntrack(name: BookmarkName) = execute(root, bookmarkUntrackArgs(name))

    override fun diffGit(revision: Revision): CommandExecutor.CommandResult = execute(root, diffGitArgs(revision))

    override fun diffGitFile(revision: Revision, filePath: FilePath): CommandExecutor.CommandResult =
        execute(root, diffGitFileArgs(revision, filePath, root))

    override fun restore(filePaths: List<FilePath>, revision: Revision): CommandExecutor.CommandResult =
        execute(root, restoreArgs(filePaths, revision, root))

    override fun fileTrack(filePaths: List<FilePath>): CommandExecutor.CommandResult =
        execute(root, fileTrackArgs(filePaths.map { it.relativeTo(root!!) }))

    override fun fileUntrack(filePaths: List<FilePath>): CommandExecutor.CommandResult =
        execute(root, fileUntrackArgs(filePaths.map { it.relativeTo(root!!) }))

    override fun fileList(filePaths: List<FilePath>): CommandExecutor.CommandResult =
        execute(root, fileListArgs(filePaths.map { it.relativeTo(root!!) }))

    override fun rebase(
        revisions: List<Revision>,
        destinations: List<Revision>,
        sourceMode: RebaseSourceMode,
        destinationMode: RebaseDestinationMode
    ) = execute(root, rebaseArgs(revisions, destinations, sourceMode, destinationMode))

    override fun squash(
        revision: Revision,
        filePaths: List<FilePath>,
        description: Description?,
        keepEmptied: Boolean
    ) = execute(root, squashArgs(revision, filePaths.map { it.relativeTo(root!!) }, description, keepEmptied))

    override fun squashInto(
        sources: List<Revision>,
        destination: Revision,
        filePaths: List<FilePath>,
        description: Description?,
        keepEmptied: Boolean
    ) = execute(
        root,
        squashIntoArgs(
            sources,
            destination,
            filePaths.map { it.relativeTo(root!!) },
            description,
            keepEmptied
        )
    )

    override fun squashIntoInteractive(
        source: Revision,
        destination: Revision,
        description: Description?,
        keepEmptied: Boolean,
        configArgs: List<String>,
        tool: String
    ) = execute(root, squashIntoInteractiveArgs(source, destination, description, keepEmptied, configArgs, tool))

    override fun split(
        revision: Revision,
        filePaths: List<FilePath>,
        description: Description?,
        parallel: Boolean,
        insertBefore: Revision?
    ) = execute(
        root,
        splitArgs(revision, filePaths.map { it.relativeTo(root!!) }, description, parallel, insertBefore)
    )

    override fun splitInteractive(
        revision: Revision,
        description: Description?,
        parallel: Boolean,
        configArgs: List<String>,
        tool: String,
        insertBefore: Revision?
    ) = execute(root, splitInteractiveArgs(revision, description, parallel, configArgs, tool, insertBefore))

    override fun gitFetch(remote: Remote?, allRemotes: Boolean) =
        execute(root, gitFetchArgs(remote, allRemotes), timeout = networkTimeout)

    override fun gitPush(
        remote: Remote?,
        bookmark: Bookmark?,
        allBookmarks: Boolean,
        changeRevisions: List<Revision>,
        revision: Revision?,
        dryRun: Boolean
    ) = execute(
        root,
        gitPushArgs(remote, bookmark, allBookmarks, changeRevisions, revision, dryRun),
        timeout = networkTimeout
    )

    override fun gitRemoteList() = execute(root, gitRemoteListArgs())

    override fun latestPushedAncestorCommitId(revision: Revision, remoteName: String): String? {
        val revset = Expression("latest(ancestors($revision) & ancestors(remote_bookmarks(remote=$remoteName)))")
        val result = log(revset, template = "commit_id", limit = 1)
        return result.stdout.trim().takeIf { result is CommandExecutor.CommandResult.Success && it.isNotEmpty() }
    }

    override fun latestPushedAncestorCommitId(remoteName: String) =
        latestPushedAncestorCommitId(WorkingCopy, remoteName)

    override fun gitClone(source: String, destination: String, colocate: Boolean) =
        execute(null, gitCloneArgs(source, destination, colocate), timeout = networkTimeout)

    override fun configGet(key: String) = execute(root, configGetArgs(key), warnOnFailure = false)

    override fun configList(key: String?, scope: CommandExecutor.ConfigScope?) =
        execute(root, configListArgs(key, scope), warnOnFailure = false)

    override fun configSetUser(scope: CommandExecutor.ConfigScope, key: String, value: String) =
        execute(root, configSetUserArgs(scope, key, value))

    override fun configUnset(scope: CommandExecutor.ConfigScope, key: String) =
        execute(root, configUnsetArgs(scope, key))

    /**
     * Clone a Git repository with streaming progress updates.
     * Updates the progress indicator with clone status and percentage.
     */
    fun gitCloneWithProgress(
        source: String,
        destination: String,
        colocate: Boolean,
        indicator: ProgressIndicator
    ): CommandExecutor.CommandResult {
        val args = gitCloneArgs(source, destination, colocate).args
        val executable = executableProvider()
        val commandLine = GeneralCommandLine(executable)
            .withParameters(args)
            .withCharset(StandardCharsets.UTF_8)

        commandLine.environment["NO_COLOR"] = "1"

        log.info("Executing: jj ${args.joinToString(" ")}")

        return try {
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val handler = OSProcessHandler(commandLine)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text
                    if (text.isNotBlank()) {
                        if (outputType.toString() == "stderr") {
                            stderr.append(text)
                            updateProgress(indicator, text)
                        } else {
                            stdout.append(text)
                        }
                    }
                }
            })

            handler.startNotify()
            val completed = handler.waitFor(networkTimeout)

            val exitCode = handler.exitCode ?: -1

            if (!completed) {
                log.warn("Clone timed out after ${networkTimeout}ms")
                handler.destroyProcess()
                val seconds = TimeUnit.MILLISECONDS.toSeconds(networkTimeout)
                CommandExecutor.CommandResult.Failure.TimedOut(
                    stdout = stdout.toString(),
                    timeoutMillis = networkTimeout,
                    stderr = JujutsuBundle.message("cli.error.timeout", "git clone", seconds.toString())
                )
            } else {
                log.info("Clone completed: exit=$exitCode")
                if (exitCode != 0) {
                    log.warn("Clone failed: $stderr")
                    CommandExecutor.CommandResult.Failure.Exited(stdout.toString(), stderr.toString(), exitCode)
                } else {
                    CommandExecutor.CommandResult.Success.Irreversible(
                        stdout.toString(),
                        stderr.toString(),
                        CommandExecutor.CommandResult.Success.Irreversible.Reason.NOT_REVERSIBLE_COMMAND
                    )
                }
            }
        } catch (_: ProcessNotCreatedException) {
            log.warn("jj executable not found: $executable")
            onJjNotFound?.invoke()
            CommandExecutor.CommandResult.Failure.NotLaunched(
                executable = executable,
                stderr = "jj executable not found: $executable. Please install jj or configure the path in Settings."
            )
        } catch (e: Exception) {
            log.error("Failed to execute jj git clone", e)
            CommandExecutor.CommandResult.Failure.NotLaunched(executable, "Failed to execute jj: ${e.message}")
        }
    }

    private fun updateProgress(indicator: ProgressIndicator, text: String) {
        // Update progress text with the latest non-blank line
        text.lines().lastOrNull { it.isNotBlank() }?.let { line ->
            indicator.text2 = line.trim()
        }

        // Parse percentage from git progress output (e.g., "Receiving objects:  45% (123/456)")
        PROGRESS_PATTERN.find(text)?.let { match ->
            val percentage = match.groupValues[1].toIntOrNull()
            if (percentage != null) {
                indicator.isIndeterminate = false
                indicator.fraction = percentage / 100.0
            }
        }
    }

    private fun execute(
        workingDir: VirtualFile?,
        invocation: JjInvocation,
        timeout: Long = defaultTimeout,
        warnOnFailure: Boolean = true
    ): CommandExecutor.CommandResult {
        val args = invocation.args
        val executable = executableProvider()
        val commandLine = GeneralCommandLine(executable)
            .withParameters(args)
            .withCharset(StandardCharsets.UTF_8)

        workingDir?.let { commandLine.setWorkDirectory(it.path) }

        // Add color=never to avoid ANSI codes in output
        commandLine.environment["NO_COLOR"] = "1"

        val cmdName = args.joinToString(" ") {
            val truncatePoint =
                listOfNotNull(20, it.length, it.indexOfFirst { c -> c.isWhitespace() }.takeIf { i -> i >= 0 }).min()
            if (truncatePoint < it.length) it.substring(0..truncatePoint - 1) + "..." else it
        }
        log.info("Executing in ${workingDir?.path ?: "."}: jj $cmdName (${Thread.currentThread().name})")

        val startTime = System.currentTimeMillis()

        val processHandler = try {
            CapturingProcessHandler(commandLine)
        } catch (_: ProcessNotCreatedException) {
            // jj executable not found - return error result instead of throwing
            log.warn("jj executable not found: $executable")
            onJjNotFound?.invoke()
            return CommandExecutor.CommandResult.Failure.NotLaunched(
                executable = executable,
                stderr = "jj executable not found: $executable. Please install jj or configure the path in Settings."
            )
        }

        val output: ProcessOutput = processHandler.runProcess(timeout.toInt())
        val duration = System.currentTimeMillis() - startTime

        log.info(
            "Completed in ${workingDir?.path ?: "."}: jj $cmdName in ${duration}ms " +
                "(exit=${output.exitCode}, timeout=${output.isTimeout})"
        )

        if (output.isTimeout) {
            // A timeout is always worth a log line, regardless of warnOnFailure: it's never the
            // caller's expected outcome the way an ordinary non-zero exit code sometimes is.
            log.warn("jj $cmdName timed out after ${timeout}ms")
        } else {
            output.takeIf { it.exitCode != 0 }?.run {
                val message = "jj $cmdName failed with exit code $exitCode:\n$stderr"
                if (warnOnFailure) log.warn(message) else log.info(message)
            }
        }

        return output.toCommandResult(cmdName, timeout, invocation.reversibility)
    }
}
