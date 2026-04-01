package `in`.kkkev.jjidea.contract

import java.nio.file.Path
import kotlin.io.path.*

/**
 * In-memory jj stub implementing JjBackend.
 * Produces output identical to real jj CLI so the same contract tests pass against both.
 */
class JjStub(override val workDir: Path) : JjBackend {
    private var idCounter = 0
    private val changes = mutableListOf<StubChange>()
    private var workingCopyIndex = -1

    private val workingCopy get() = changes[workingCopyIndex]

    override fun run(vararg args: String): JjBackend.Result {
        val argList = args.toList()
        return try {
            dispatch(argList)
        } catch (e: StubError) {
            JjBackend.Result(1, "", e.message ?: "Unknown error")
        }
    }

    override fun init() {
        val result = run("git", "init", "--colocate")
        check(result.isSuccess) { "jj git init failed: ${result.stderr}" }
    }

    override fun createFile(path: String, content: String) {
        val file = workDir.resolve(path)
        file.parent.createDirectories()
        file.writeText(content)
    }

    override fun describe(message: String) {
        val result = run("describe", "-m", message)
        check(result.isSuccess) { "jj describe failed: ${result.stderr}" }
    }

    override fun newChange(message: String) {
        val result = if (message.isNotEmpty()) {
            run("new", "-m", message)
        } else {
            run("new")
        }
        check(result.isSuccess) { "jj new failed: ${result.stderr}" }
    }

    override fun bookmarkCreate(name: String) {
        val result = run("bookmark", "create", name)
        check(result.isSuccess) { "jj bookmark create failed: ${result.stderr}" }
    }

    override fun split(message: String, filePaths: List<String>, revision: String) {
        val args = mutableListOf("split", "-r", revision, "-m", message)
        args.addAll(filePaths)
        val result = run(*args.toTypedArray())
        check(result.isSuccess) { "jj split failed: ${result.stderr}" }
    }

    // -- Command dispatch --

    private fun dispatch(args: List<String>): JjBackend.Result = when {
        args.startsWith("git", "init") -> cmdGitInit()
        args.startsWith("log") -> cmdLog(args)
        args.startsWith("status") -> cmdStatus()
        args.startsWith("diff") -> cmdDiff(args)
        args.startsWith("describe") -> cmdDescribe(args)
        args.startsWith("new") -> cmdNew(args)
        args.startsWith("abandon") -> cmdAbandon(args)
        args.startsWith("edit") -> cmdEdit(args)
        args.startsWith("split") -> cmdSplit(args)
        args.startsWith("file", "show") -> cmdFileShow(args)
        args.startsWith("file", "annotate") -> cmdFileAnnotate(args)
        args.startsWith("bookmark") -> dispatchBookmark(args)
        else -> throw StubError("Unknown command: ${args.joinToString(" ")}")
    }

    private fun dispatchBookmark(args: List<String>): JjBackend.Result = when {
        args.startsWith("bookmark", "create") -> cmdBookmarkCreate(args)
        args.startsWith("bookmark", "set") -> cmdBookmarkSet(args)
        args.startsWith("bookmark", "delete") -> cmdBookmarkDelete(args)
        args.startsWith("bookmark", "rename") -> cmdBookmarkRename(args)
        args.startsWith("bookmark", "list") -> cmdBookmarkList(args)
        else -> throw StubError("Unknown bookmark subcommand: ${args.joinToString(" ")}")
    }

    // -- Commands --

    private fun cmdGitInit(): JjBackend.Result {
        workDir.resolve(".jj").createDirectories()
        // Root commit (immutable, empty)
        val rootChange = newStubChange(description = "", parentIds = emptyList(), immutable = true)
        changes.add(rootChange)
        // Working copy on top of root
        val wc = newStubChange(description = "", parentIds = listOf(rootChange.commitId))
        changes.add(wc)
        workingCopyIndex = 1
        return ok()
    }

    private fun cmdLog(args: List<String>): JjBackend.Result {
        val revset = args.flagValue("-r") ?: "all()"
        val template = args.flagValue("-T") ?: throw StubError("Log requires -T template")
        val resolved = resolveRevset(revset)
        val output = buildString {
            for (change in resolved) {
                append(formatLogEntry(change, template))
            }
        }
        return ok(output)
    }

    private fun cmdStatus(): JjBackend.Result {
        val diffs = computeDiffs(workingCopy)
        if (diffs.isEmpty()) {
            val desc = workingCopy.description.lines().firstOrNull()?.ifEmpty { "(no description set)" }
                ?: "(no description set)"
            return ok("Working copy : ${shortId(workingCopy.changeId)} $desc\n")
        }
        val output = buildString {
            append("Working copy changes:\n")
            for ((path, status) in diffs.sortedBy { it.first }) {
                append("$status $path\n")
            }
            val desc = workingCopy.description.lines().firstOrNull()?.ifEmpty { "(no description set)" }
                ?: "(no description set)"
            append("Working copy : ${shortId(workingCopy.changeId)} $desc\n")
        }
        return ok(output)
    }

    private fun cmdDiff(args: List<String>): JjBackend.Result {
        val revset = args.flagValue("-r") ?: "@"
        val isSummary = "--summary" in args
        if (!isSummary) throw StubError("Only --summary diffs supported in stub")
        val change = resolveOne(revset)
        val diffs = computeDiffs(change)
        val output = diffs.sortedBy { it.first }.joinToString("") { (path, status) -> "$status $path\n" }
        return ok(output)
    }

    private fun cmdDescribe(args: List<String>): JjBackend.Result {
        val message = args.flagValue("-m") ?: ""
        val revset = args.flagValue("-r") ?: "@"
        val change = resolveOne(revset)
        change.description = message
        change.commitId = nextCommitId()
        return ok()
    }

    private fun cmdNew(args: List<String>): JjBackend.Result {
        val message = args.flagValue("-m") ?: ""
        // Snapshot working copy files before creating new change
        snapshotWorkingCopy()
        val wc = newStubChange(
            description = message,
            parentIds = listOf(workingCopy.commitId)
        )
        changes.add(wc)
        workingCopyIndex = changes.size - 1
        return ok()
    }

    private fun cmdAbandon(args: List<String>): JjBackend.Result {
        val revset = args.flagValue("-r") ?: "@"
        val change = resolveOne(revset)
        change.abandoned = true
        // If abandoning working copy, re-parent to its parent
        if (change === workingCopy) {
            val parent = changes.first { it.commitId == change.parentIds.first() }
            val newWc = newStubChange(description = "", parentIds = listOf(parent.commitId))
            changes.add(newWc)
            workingCopyIndex = changes.size - 1
        }
        return ok()
    }

    private fun cmdEdit(args: List<String>): JjBackend.Result {
        // edit takes revset as positional or via -r
        val revset = args.flagValue("-r") ?: args.drop(1).firstOrNull() ?: "@"
        val change = resolveOne(revset)
        workingCopyIndex = changes.indexOf(change)
        return ok()
    }

    private fun cmdSplit(args: List<String>): JjBackend.Result {
        val message = args.flagValue("-m") ?: ""
        val revset = args.flagValue("-r") ?: "@"
        // File paths are positional args after flags
        val filePaths = args.drop(1)
            .filter { it != "-r" && it != revset && it != "-m" && it != message && !it.startsWith("-") }

        val isWc = revset == "@"
        if (isWc) snapshotWorkingCopy()
        val source = resolveOne(revset)

        if (filePaths.isEmpty()) {
            throw StubError("split requires file paths (interactive mode not supported in stub)")
        }

        // Partition files: selected go to first (parent), remaining go to second (child)
        val sourceFiles = if (isWc) {
            // For WC, include both explicit files and filesystem diffs
            val allFiles = source.files.toMutableMap()
            val fsFiles = scanWorkDir()
            val parentFiles = getFilesAtChange(findParentOrNull(source))
            for ((path, content) in fsFiles) {
                if (path !in allFiles && (path !in parentFiles || parentFiles[path] != content)) {
                    allFiles[path] = content
                }
            }
            for (path in parentFiles.keys) {
                if (path !in fsFiles && path !in allFiles) {
                    allFiles[path] = null
                }
            }
            allFiles
        } else {
            source.files.toMap()
        }

        val selectedFiles = mutableMapOf<String, String?>()
        val remainingFiles = mutableMapOf<String, String?>()
        for ((path, content) in sourceFiles) {
            if (path in filePaths) {
                selectedFiles[path] = content
            } else {
                remainingFiles[path] = content
            }
        }

        // First change (selected): keeps source's parents, gets the -m description
        val firstChange = newStubChange(description = message, parentIds = source.parentIds)
        firstChange.files.putAll(selectedFiles)
        changes.add(firstChange)

        // Second change (remaining): child of first, keeps original description
        val secondChange = newStubChange(description = source.description, parentIds = listOf(firstChange.commitId))
        secondChange.files.putAll(remainingFiles)
        changes.add(secondChange)

        // Reparent children of source to point to second
        val rebased = mutableListOf<StubChange>()
        for (change in changes) {
            if (change === source || change === firstChange || change === secondChange) continue
            if (change.abandoned) continue
            val idx = change.parentIds.indexOf(source.commitId)
            if (idx >= 0) {
                val newParentIds = change.parentIds.toMutableList()
                newParentIds[idx] = secondChange.commitId
                // StubChange parentIds is a val, so we need to create new one
                rebased.add(change)
            }
        }
        // Since parentIds is a val List, we need a workaround. Let me check...
        // Actually parentIds is just List<String> on StubChange - it's a val.
        // We need to handle this differently. Let me update working copy index.

        // Remove source
        source.abandoned = true

        // Handle working copy: if splitting WC, move WC to second
        if (isWc) {
            workingCopyIndex = changes.indexOf(secondChange)
        } else {
            // Reparent working copy if it was a child of source
            // We can't mutate parentIds directly, so create replacement changes
            reparentChildren(source.commitId, secondChange.commitId)
        }

        // Build stderr matching jj's format
        val rebasedCount = rebased.size
        val stderr = buildString {
            if (rebasedCount > 0) {
                val suffix = if (rebasedCount == 1) "" else "s"
                appendLine("Rebased $rebasedCount descendant commit$suffix")
            }
            appendLine(
                "Selected changes : ${shortId(firstChange.changeId)} " +
                    "${shortCommitId(firstChange.commitId)} $message"
            )
            val remainingDesc = source.description.ifEmpty { "(no description set)" }
            appendLine(
                "Remaining changes: ${shortId(secondChange.changeId)} " +
                    "${shortCommitId(secondChange.commitId)} $remainingDesc"
            )
        }

        return JjBackend.Result(0, "", stderr)
    }

    /**
     * Reparent all non-abandoned changes that had [oldParentId] as a parent
     * to point to [newParentId] instead. Since [StubChange.parentIds] is a val,
     * we replace the change in the list.
     */
    private fun reparentChildren(oldParentId: String, newParentId: String) {
        for (i in changes.indices) {
            val change = changes[i]
            if (change.abandoned) continue
            val idx = change.parentIds.indexOf(oldParentId)
            if (idx >= 0) {
                val newParentIds = change.parentIds.toMutableList()
                newParentIds[idx] = newParentId
                val replacement = StubChange(
                    changeId = change.changeId,
                    commitId = nextCommitId(),
                    description = change.description,
                    parentIds = newParentIds,
                    files = change.files,
                    bookmarks = change.bookmarks,
                    authorName = change.authorName,
                    authorEmail = change.authorEmail,
                    timestamp = change.timestamp,
                    abandoned = change.abandoned,
                    immutable = change.immutable
                )
                changes[i] = replacement
                if (workingCopyIndex == i) workingCopyIndex = i // stays the same index
            }
        }
    }

    private fun cmdFileShow(args: List<String>): JjBackend.Result {
        val revset = args.flagValue("-r") ?: "@"
        // File path is the last arg that doesn't start with -
        val filePath =
            args.dropWhile { it != "show" }
                .drop(1)
                .lastOrNull { it != "-r" && it != revset && !it.startsWith("-") }
                ?: throw StubError("file show requires a path")
        val change = resolveOne(revset)
        val content = getFileAtChange(change, filePath)
            ?: throw StubError("No such file: $filePath")
        return ok(content)
    }

    private fun cmdFileAnnotate(args: List<String>): JjBackend.Result {
        // Auto-snapshot working copy so file changes are visible to annotation (matching real jj)
        snapshotWorkingCopy()
        val revset = args.flagValue("-r") ?: "@"
        val template = args.flagValue("-T") ?: throw StubError("annotate requires -T")
        // File path: last positional arg
        val filePath = args.dropWhile { it != "annotate" }
            .drop(1)
            .lastOrNull { it != "-r" && it != revset && it != "-T" && it != template && !it.startsWith("-") }
            ?: throw StubError("annotate requires a path")
        val target = resolveOne(revset)
        val content = getFileAtChange(target, filePath) ?: throw StubError("No such file: $filePath")
        val lines = content.lines().let { if (it.last().isEmpty()) it.dropLast(1) else it }

        // Build ancestor chain root -> target
        val chain = ancestorChain(target)
        // For each line, find which change introduced/modified it
        val attributions = annotateLines(chain, filePath, lines)

        val output = buildString {
            for ((line, change) in lines.zip(attributions)) {
                val desc = change.description + if (change.description.endsWith("\n")) "" else "\n"
                field(change.changeId)
                field(shortId(change.changeId))
                field("") // offset (non-divergent)
                field(change.commitId)
                field(shortCommitId(change.commitId))
                field(change.authorName)
                field(change.authorEmail)
                field(change.timestamp.toString())
                field(desc)
                field(line + "\n")
            }
        }
        return ok(output)
    }

    private fun cmdBookmarkCreate(args: List<String>): JjBackend.Result {
        val name = args.drop(2).firstOrNull { !it.startsWith("-") }
            ?: throw StubError("bookmark create requires a name")
        workingCopy.bookmarks.add(name)
        return ok()
    }

    private fun cmdBookmarkSet(args: List<String>): JjBackend.Result {
        val name = args.drop(2).firstOrNull { !it.startsWith("-") }
            ?: throw StubError("bookmark set requires a name")
        val revset = args.flagValue("-r") ?: "@"
        val target = resolveOne(revset)
        // Remove from all changes
        changes.forEach { it.bookmarks.remove(name) }
        target.bookmarks.add(name)
        return ok()
    }

    private fun cmdBookmarkDelete(args: List<String>): JjBackend.Result {
        val name = args.drop(2).firstOrNull { !it.startsWith("-") }
            ?: throw StubError("bookmark delete requires a name")
        changes.forEach { it.bookmarks.remove(name) }
        return ok()
    }

    private fun cmdBookmarkRename(args: List<String>): JjBackend.Result {
        val positional = args.drop(2).filter { !it.startsWith("-") }
        if (positional.size < 2) throw StubError("bookmark rename requires old and new names")
        val oldName = positional[0]
        val newName = positional[1]
        changes.forEach { c ->
            if (c.bookmarks.remove(oldName)) c.bookmarks.add(newName)
        }
        return ok()
    }

    private fun cmdBookmarkList(args: List<String>): JjBackend.Result {
        val template = args.flagValue("-T") ?: throw StubError("bookmark list requires -T")
        val allBookmarks = changes.filter { !it.abandoned }
            .flatMap { c -> c.bookmarks.map { name -> name to c } }
            .sortedBy { it.first }

        val output = buildString {
            for ((name, change) in allBookmarks) {
                when {
                    isFullBookmarkTemplate(template) -> {
                        field("true") // present (all stub bookmarks are local)
                        field(name) // nameWithRemote (no remotes in stub)
                        field(qualifiedChangeId(change))
                    }

                    isBookmarkTemplate(template) -> {
                        field(name)
                        field(qualifiedChangeId(change))
                    }

                    else -> throw StubError("Unknown bookmark template")
                }
            }
        }
        return ok(output)
    }

    // -- Template formatting --

    private fun formatLogEntry(change: StubChange, template: String): String = buildString {
        val isFullTemplate = "author.name()" in template
        val isBasicTemplate = "current_working_copy" in template

        if (!isBasicTemplate && !isFullTemplate) {
            throw StubError("Unknown log template")
        }

        val desc = if (change.description.isEmpty()) "" else change.description + "\n"
        val isWc = change === workingCopy
        val isEmpty = computeDiffs(change).isEmpty()

        // 9 basic fields
        field(qualifiedChangeId(change))
        field(qualifiedCommitId(change))
        field(desc)
        field(change.bookmarks.joinToString(",") { "$it;true" })
        field(formatParents(change))
        field(if (isWc) "true" else "false")
        field("false") // conflict
        field(if (isEmpty) "true" else "false")
        field(if (change.immutable) "true" else "false")

        if (isFullTemplate) {
            field(change.authorName)
            field(change.authorEmail)
            field(change.timestamp.toString())
            field(change.authorName) // committer = author
            field(change.authorEmail)
            field(change.timestamp.toString())
        }
    }

    private fun formatParents(change: StubChange): String =
        change.parentIds.mapNotNull { pid ->
            val parent = changes.firstOrNull { it.commitId == pid } ?: return@mapNotNull null
            "${qualifiedChangeId(parent)}|${qualifiedCommitId(parent)}"
        }.joinToString(",")

    private fun qualifiedChangeId(change: StubChange) =
        "${change.changeId}~${shortId(change.changeId)}~"

    private fun qualifiedCommitId(change: StubChange) =
        "${change.commitId}~${shortCommitId(change.commitId)}"

    private fun shortId(fullId: String): String {
        val allIds = changes.filter { !it.abandoned }.map { it.changeId }
        return shortest(fullId, allIds)
    }

    private fun shortCommitId(fullId: String): String {
        val allIds = changes.filter { !it.abandoned }.map { it.commitId }
        return shortest(fullId, allIds)
    }

    private fun shortest(id: String, allIds: List<String>): String {
        for (len in 1..id.length) {
            val prefix = id.substring(0, len)
            if (allIds.count { it.startsWith(prefix) } <= 1) return prefix
        }
        return id
    }

    // -- Revset resolution --

    private fun resolveRevset(revset: String): List<StubChange> = when (revset) {
        "@" -> listOf(workingCopy)
        "@-" -> {
            val parentId = workingCopy.parentIds.firstOrNull()
                ?: throw StubError("Working copy has no parent")
            listOf(changes.first { it.commitId == parentId })
        }

        "all()" -> changes.filter { !it.abandoned }.reversed()
        else -> {
            val match = changes.firstOrNull { !it.abandoned && it.changeId.startsWith(revset) }
                ?: throw StubError("No change matching: $revset")
            listOf(match)
        }
    }

    private fun resolveOne(revset: String) = resolveRevset(revset).single()

    // -- File operations --

    private fun snapshotWorkingCopy() {
        val fsFiles = scanWorkDir()
        val parentFiles = getFilesAtChange(findParent(workingCopy))
        // Detect additions and modifications
        for ((path, content) in fsFiles) {
            val parentContent = parentFiles[path]
            if (parentContent != content) {
                workingCopy.files[path] = content
            }
        }
        // Detect deletions
        for (path in parentFiles.keys) {
            if (path !in fsFiles) {
                workingCopy.files[path] = null
            }
        }
    }

    private fun computeDiffs(change: StubChange): List<Pair<String, Char>> {
        val parentFiles = getFilesAtChange(findParentOrNull(change))
        // For working copy, also check filesystem
        return if (change === workingCopy) {
            val fsFiles = scanWorkDir()
            val result = mutableListOf<Pair<String, Char>>()
            // Check explicit files in the change
            for ((path, content) in change.files) {
                if (content == null) {
                    if (path in parentFiles) result.add(path to 'D')
                } else if (path in parentFiles) {
                    if (content != parentFiles[path]) result.add(path to 'M')
                } else {
                    result.add(path to 'A')
                }
            }
            // Check filesystem files not yet in change.files
            for ((path, content) in fsFiles) {
                if (path in change.files) continue
                val parentContent = parentFiles[path]
                if (parentContent == null) {
                    result.add(path to 'A')
                } else if (parentContent != content) {
                    result.add(path to 'M')
                }
            }
            // Check parent files deleted on filesystem
            for (path in parentFiles.keys) {
                if (path in change.files) continue
                if (path !in fsFiles) result.add(path to 'D')
            }
            result
        } else {
            change.files.mapNotNull { (path, content) ->
                if (content == null) {
                    if (path in parentFiles) path to 'D' else null
                } else if (path in parentFiles) {
                    if (content != parentFiles[path]) path to 'M' else null
                } else {
                    path to 'A'
                }
            }
        }
    }

    private fun getFileAtChange(change: StubChange, path: String): String? {
        // For working copy, check filesystem first
        if (change === workingCopy) {
            val fsFile = workDir.resolve(path)
            if (fsFile.exists()) return fsFile.readText()
            // Check if explicitly deleted
            if (change.files[path] == null && path in change.files) return null
        }
        // Check this change's files
        if (path in change.files) return change.files[path]
        // Walk parents
        val parent = findParentOrNull(change) ?: return null
        return getFileAtChange(parent, path)
    }

    private fun getFilesAtChange(change: StubChange?): Map<String, String> {
        if (change == null) return emptyMap()
        val parentFiles = getFilesAtChange(findParentOrNull(change)).toMutableMap()
        for ((path, content) in change.files) {
            if (content == null) {
                parentFiles.remove(path)
            } else {
                parentFiles[path] = content
            }
        }
        return parentFiles
    }

    private fun findParent(change: StubChange) =
        findParentOrNull(change) ?: throw StubError("Change has no parent")

    private fun findParentOrNull(change: StubChange): StubChange? {
        val parentId = change.parentIds.firstOrNull() ?: return null
        return changes.firstOrNull { it.commitId == parentId }
    }

    private fun scanWorkDir(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        scanDir(workDir, workDir, result)
        return result
    }

    private fun scanDir(dir: Path, root: Path, result: MutableMap<String, String>) {
        val entries = dir.toFile().listFiles() ?: return
        for (entry in entries.sorted()) {
            val path = entry.toPath()
            if (path.name == ".jj" || path.name == ".git") continue
            if (path.isDirectory()) {
                scanDir(path, root, result)
            } else {
                val relativePath = root.relativize(path).toString()
                result[relativePath] = path.readText()
            }
        }
    }

    // -- Annotation --

    private fun ancestorChain(target: StubChange): List<StubChange> {
        val chain = mutableListOf<StubChange>()
        var current: StubChange? = target
        while (current != null) {
            chain.add(current)
            current = findParentOrNull(current)
        }
        return chain.reversed() // root -> target
    }

    private fun annotateLines(
        chain: List<StubChange>,
        filePath: String,
        lines: List<String>
    ): List<StubChange> {
        // Start with all lines attributed to the first change that has the file
        val attributions = MutableList(lines.size) { chain.first() }
        var previousLines: List<String>? = null

        for (change in chain) {
            val contentAtChange = getFileContentFromChain(change, filePath)
            if (contentAtChange == null) {
                previousLines = null
                continue
            }
            val currentLines = contentAtChange.lines().let { if (it.last().isEmpty()) it.dropLast(1) else it }

            if (previousLines == null) {
                // First time file appears - attribute all matching lines to this change
                for (i in lines.indices) {
                    if (i < currentLines.size) attributions[i] = change
                }
            } else {
                // Attribute new/changed lines to this change
                for (i in lines.indices) {
                    if (i < currentLines.size) {
                        val currentLine = currentLines[i]
                        val prevLine = previousLines.getOrNull(i)
                        if (currentLine != prevLine) {
                            attributions[i] = change
                        }
                    }
                }
            }
            previousLines = currentLines
        }
        return attributions
    }

    private fun getFileContentFromChain(change: StubChange, path: String): String? {
        if (path in change.files) return change.files[path]
        val parent = findParentOrNull(change) ?: return null
        return getFileContentFromChain(parent, path)
    }

    // -- Helpers --

    private fun newStubChange(
        description: String,
        parentIds: List<String>,
        immutable: Boolean = false
    ) = StubChange(
        changeId = nextChangeId(),
        commitId = nextCommitId(),
        description = description,
        parentIds = parentIds,
        immutable = immutable
    )

    private fun nextChangeId(): String {
        val id = idCounter++
        return String.format("%032x", id.toBigInteger())
    }

    private fun nextCommitId(): String {
        val id = idCounter++
        return String.format("%040x", id.toBigInteger())
    }

    private fun isFullBookmarkTemplate(template: String) = "present" in template && "normal_target" in template
    private fun isBookmarkTemplate(template: String) = "normal_target" in template

    private fun StringBuilder.field(value: String) {
        append(value)
        append('\u0000')
    }

    private fun ok(stdout: String = "") = JjBackend.Result(0, stdout, "")

    private fun List<String>.startsWith(vararg prefix: String) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun List<String>.flagValue(flag: String): String? {
        val idx = indexOf(flag)
        return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
    }

    private class StubError(message: String) : RuntimeException(message)
}

private data class StubChange(
    val changeId: String,
    var commitId: String,
    var description: String,
    val parentIds: List<String>,
    val files: MutableMap<String, String?> = mutableMapOf(),
    val bookmarks: MutableList<String> = mutableListOf(),
    val authorName: String = "Test User",
    val authorEmail: String = "test@example.com",
    val timestamp: Long = System.currentTimeMillis() / 1000,
    var abandoned: Boolean = false,
    val immutable: Boolean = false
)
