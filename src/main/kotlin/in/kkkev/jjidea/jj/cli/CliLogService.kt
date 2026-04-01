package `in`.kkkev.jjidea.jj.cli

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import `in`.kkkev.jjidea.jj.*
import kotlinx.datetime.Instant

interface LogSpec<T> {
    val spec: String
    val count: Int

    fun take(input: Iterator<String>): T
}

abstract class SingleField<T>(
    private val fieldName: String
) : LogSpec<T> {
    override val spec get() = "$fieldName ++ \"\\0\""
    override val count get() = 1

    abstract fun parse(input: String): T

    override fun take(input: Iterator<String>) = parse(input.next())
}

// TODO Need a way to ensure that only single fields are terminated
interface MultipleFields<T> : LogSpec<T> {
    val fields: Array<out LogSpec<*>>
    override val spec get() = fields.joinToString(" ++ ") { it.spec }
    override val count get() = fields.sumOf { it.count }
}

abstract class LogTemplate<T>(
    override vararg val fields: LogSpec<*>
) : MultipleFields<T>

fun <T> logTemplate(
    vararg fields: LogSpec<*>,
    taker: (Iterator<String>) -> T
) = object : LogTemplate<T>(*fields) {
    override fun take(input: Iterator<String>) = taker(input)
}

/**
 * CLI-based implementation of JujutsuLogService.
 * Centralizes all template generation and parsing logic.
 */
class CliLogService(private val repo: JujutsuRepository) : LogService {
    private val log = Logger.getInstance(javaClass)

    private val executor = repo.commandExecutor
    val logTemplates = LogTemplates()

    override fun getLog(revset: Revset, filePaths: List<FilePath>, limit: Int?) =
        getLog(logTemplates.fullLogTemplate, revset, filePaths, limit)

    override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?) =
        getLog(logTemplates.basicLogTemplate, revset, filePaths, limit)

    override fun getBookmarks(): Result<List<BookmarkItem>> {
        log.debug("Getting bookmarks")

        val result = executor.bookmarkList(logTemplates.bookmarkListTemplate.spec)
        return if (result.isSuccess) {
            toResult("Failed to parse bookmarks") {
                parse(logTemplates.bookmarkListTemplate, result.stdout).filterNotNull()
            }
        } else {
            log.warn("Bookmark list command failed: ${result.stderr}")
            Result.failure(VcsException("Error from jj bookmark list: " + result.stderr))
        }
    }

    private fun <T> toResult(failureMessage: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        log.warn("$failureMessage: ${e.message}")
        Result.failure(e)
    }

    override fun getFileChanges(revision: Revision): Result<List<FileChange>> {
        log.debug("Getting file changes for revision: $revision")

        val result = executor.diffSummary(revision)

        return if (result.isSuccess) {
            toResult("Failed to parse file changes") {
                parseFileChanges(result.stdout).also {
                    log.debug("Parsed ${it.size} file changes")
                }
            }
        } else {
            log.warn("Diff summary command failed: ${result.stderr}")
            Result.failure(Exception("Diff summary command failed: ${result.stderr}"))
        }
    }

    /**
     * Parse diff summary output.
     * Format: "M path/to/file" or "A path/to/file" or "D path/to/file"
     */
    private fun parseFileChanges(output: String): List<FileChange> {
        val trimmed = output.trim()
        if (trimmed.isBlank()) return emptyList()

        return trimmed.lines().mapNotNull { line ->
            val cleanLine = line.trim()
            if (cleanLine.isEmpty() || cleanLine.length < 3) {
                return@mapNotNull null
            }

            val statusChar = cleanLine[0]
            val filePath = cleanLine.substring(2).trim()

            if (filePath.isEmpty()) {
                return@mapNotNull null
            }

            val status = when (statusChar) {
                'M' -> FileChangeStatus.MODIFIED
                'A' -> FileChangeStatus.ADDED
                'D' -> FileChangeStatus.DELETED
                else -> {
                    log.debug("Unknown file status '$statusChar' in line: $cleanLine")
                    FileChangeStatus.UNKNOWN
                }
            }

            FileChange(filePath, status)
        }
    }

    private fun <T> getLog(
        template: LogTemplate<T>,
        revset: Revset = Expression.ALL,
        filePaths: List<FilePath> = emptyList(),
        limit: Int? = null
    ): Result<List<T>> {
        log.debug("Getting log for revset: $revset, files: $filePaths, limit: $limit")

        val result = executor.log(revset, template.spec, filePaths, limit)
        return if (result.isSuccess) {
            toResult("Failed to parse") { parse(template, result.stdout) }
        } else {
            // TODO Improve logging
            Result.failure(VcsException("Error from jj log: " + result.stderr))
        }
    }

    private fun <T> parse(template: LogTemplate<T>, logOutput: String): List<T> {
        val fields = logOutput.trim().split(FIELD_SEPARATOR)
        val recordSize = template.count
        return fields
            .chunked(recordSize)
            .filter { it.size == recordSize }
            .map { chunk -> template.take(chunk.iterator()) }
    }

    open class LogFields {
        fun <T> singleField(spec: String, parser: (String) -> T) = object : SingleField<T>(spec) {
            override fun parse(input: String) = parser(input)
        }

        fun stringField(spec: String) = singleField(spec) { it }

        fun booleanField(spec: String) = singleField("""if($spec, "true", "false")""") { it.toBoolean() }

        fun timestampField(spec: String) =
            singleField("""$spec.timestamp().utc().format("%s")""") {
                Instant.fromEpochSeconds(it.toLong())
            }

        inner class SignatureFields(spec: String) : MultipleFields<Signature> {
            val name = stringField("$spec.name()")
            val email = stringField("$spec.email()")
            val timestamp = timestampField(spec)

            override val fields: Array<LogSpec<*>> = arrayOf(name, email, timestamp)

            override fun take(input: Iterator<String>) = Signature(
                name.take(input),
                email.take(input),
                timestamp.take(input)
            )
        }

        fun <T> optional(field: SingleField<T>) = singleField(field.spec) {
            if (it.isEmpty()) null else field.parse(it)
        }

        val changeId = singleField(TemplateParts.qualifiedChangeId()) {
            val (full, short, offset) = it.split("~")
            ChangeId(full, short, offset)
        }
        val optionalChangeId = optional(changeId)
        val commitId = singleField(TemplateParts.commitId()) {
            val (full, short) = it.split("~")
            CommitId(full, short)
        }
        val description = stringField("description")
        val currentWorkingCopy = booleanField("current_working_copy")
        val conflict = booleanField("conflict")
        val empty = booleanField("empty")
        private val localBookmarkTemplate =
            """bookmarks.map(|b| ${TemplateParts.nameWithRemote("b")}""" +
                """ ++ ";" ++ if(b.remote(), b.tracked(), "true")).join(",")"""
        private val remoteBookmarkTemplate =
            """remote_bookmarks.map(|b| b.name() ++ "@" ++ b.remote()""" +
                """ ++ ";" ++ b.tracked()).join(",")"""
        val bookmarks = singleField(
            """separate(",", $localBookmarkTemplate, $remoteBookmarkTemplate)"""
        ) {
            it.splitByComma { part ->
                val (nameStr, trackedStr) = part.split(";")
                Bookmark(nameStr, trackedStr == "true")
            }.distinctBy { b -> b.name }
        }
        val parents = singleField(
            """
                |parents.map(|c|
                | ${TemplateParts.qualifiedChangeId("c")} ++ "|" ++
                | ${TemplateParts.commitId("c")}
                |).join(",")
            """.trimMargin()
        ) {
            it.splitByComma { p ->
                val (changeIdParts, commitIdParts) = p.split("|")
                LogEntry.Identifiers(changeId.parse(changeIdParts), commitId.parse(commitIdParts))
            }
        }
        val author = SignatureFields("author")
        val committer = SignatureFields("committer")
        val immutable = booleanField("immutable")
    }

    inner class LogTemplates : LogFields() {
        val basicLogTemplate = logTemplate(
            changeId,
            commitId,
            description,
            bookmarks,
            parents,
            currentWorkingCopy,
            conflict,
            empty,
            immutable
        ) {
            LogEntry(
                repo,
                changeId.take(it),
                commitId.take(it),
                description.take(it),
                bookmarks.take(it),
                parents.take(it),
                currentWorkingCopy.take(it),
                conflict.take(it),
                empty.take(it),
                immutable = immutable.take(it)
            )
        }

        val fullLogTemplate = logTemplate(basicLogTemplate, author, committer) {
            val basic = basicLogTemplate.take(it)
            val jjAuthor = author.take(it)
            val jjCommitter = committer.take(it)
            basic.copy(
                authorTimestamp = jjAuthor.timestamp,
                committerTimestamp = jjCommitter.timestamp,
                author = jjAuthor.user,
                committer = jjCommitter.user
            )
        }

        /**
         * Template for bookmark list parsing
         * Uses: jj bookmark list -T 'name ++ "\0" ++ normal_target.change_id() ++ "~" ++ normal_target.change_id().shortest() ++ "\0"'
         */
        val bookmarkListTemplate = object : LogTemplate<BookmarkItem?>(
            booleanField("present"),
            stringField(TemplateParts.nameWithRemote()),
            singleField("if(present,${TemplateParts.qualifiedChangeId("normal_target")},\"\")") {
                optionalChangeId.parse(it)
            }
        ) {
            override fun take(input: Iterator<String>): BookmarkItem? {
                val present = fields[0].take(input) as Boolean
                val name = fields[1].take(input) as String
                val id = fields[2].take(input) as ChangeId?
                return if (present) BookmarkItem(Bookmark(name), id!!) else null
            }
        }
    }

    companion object {
        private const val FIELD_SEPARATOR = "\u0000" // Null byte
    }
}

fun <T> String.splitByComma(transform: (String) -> T) =
    if (this.isEmpty()) emptyList() else this.split(",").map(transform)
