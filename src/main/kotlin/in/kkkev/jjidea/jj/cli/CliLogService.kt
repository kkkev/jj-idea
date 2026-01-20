package `in`.kkkev.jjidea.jj.cli

import com.intellij.openapi.diagnostic.Logger
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

object LogTemplates {
    fun <T> singleField(
        spec: String,
        parser: (String) -> T
    ) = object : SingleField<T>(spec) {
        override fun parse(input: String) = parser(input)
    }

    fun stringField(spec: String) = singleField(spec) { it }

    fun booleanField(spec: String) = singleField("""if($spec, "true", "false")""") { it.toBoolean() }

    fun timestampField(spec: String) =
        singleField("""$spec.timestamp().utc().format("%s")""") {
            Instant.fromEpochSeconds(it.toLong())
        }

    class SignatureFields(
        spec: String
    ) : MultipleFields<Signature> {
        val name = stringField("$spec.name()")
        val email = stringField("$spec.email()")
        val timestamp = timestampField(spec)

        override val fields: Array<LogSpec<*>> = arrayOf(name, email, timestamp)

        override fun take(input: Iterator<String>) =
            Signature(
                name.take(input),
                email.take(input),
                timestamp.take(input)
            )
    }

    val changeId =
        singleField("change_id ++ \"~\" ++ change_id.shortest()") {
            val (full, short) = it.split("~")
            ChangeId(full, short)
        }
    val commitId = stringField("commit_id")
    val description = stringField("description")
    val currentWorkingCopy = booleanField("current_working_copy")
    val conflict = booleanField("conflict")
    val empty = booleanField("empty")
    val bookmarks = singleField("""bookmarks.map(|b| b.name()).join(",")""") { it.splitByComma(::Bookmark) }
    val parents =
        singleField("""parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(",")""") {
            it.splitByComma(changeId::parse)
        }
    val author = SignatureFields("author")
    val committer = SignatureFields("committer")
    val immutable = booleanField("immutable")

    val basicLogTemplate =
        logTemplate(
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

    val fullLogTemplate =
        logTemplate(basicLogTemplate, author, committer) {
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

    val refsLogTemplate =
        logTemplate(changeId, bookmarks, currentWorkingCopy) {
            val changeId = changeId.take(it)
            val bookmarks = bookmarks.take(it)
            val currentWorkingCopy = currentWorkingCopy.take(it)
            listOfNotNull(
                listOf(WorkingCopy).takeIf { currentWorkingCopy },
                bookmarks
            ).flatten().map { ref -> RefAtRevision(changeId, ref) }
        }

    val commitGraphLogTemplate =
        logTemplate(changeId, parents, committer.timestamp) {
            CommitGraphNode(
                changeId.take(it),
                parents.take(it),
                committer.timestamp.take(it)
            )
        }

    /**
     * Template for bookmark list parsing
     * Uses: jj bookmark list -T 'name ++ "\0" ++ normal_target.change_id() ++ "~" ++ normal_target.change_id().shortest() ++ "\0"'
     */
    val bookmarkListTemplate =
        object : LogTemplate<BookmarkItem>(
            stringField("name"),
            singleField("normal_target.change_id() ++ \"~\" ++ normal_target.change_id().shortest()") {
                val (full, short) = it.split("~")
                ChangeId(full, short)
            }
        ) {
            override fun take(input: Iterator<String>): BookmarkItem {
                val name = fields[0].take(input) as String
                val changeId = fields[1].take(input) as ChangeId
                return BookmarkItem(Bookmark(name), changeId)
            }
        }
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
class CliLogService(
    private val executor: CommandExecutor
) : LogService {
    private val log = Logger.getInstance(javaClass)

    override fun getLog(
        revset: Revset,
        filePaths: List<String>
    ) = getLog(LogTemplates.fullLogTemplate, revset, filePaths)

    override fun getLogBasic(
        revset: Revset,
        filePaths: List<String>
    ) = getLog(LogTemplates.basicLogTemplate, revset, filePaths)

    override fun getRefs(): Result<List<RefAtRevision>> = getLog(LogTemplates.refsLogTemplate).map { it.flatten() }

    override fun getCommitGraph(revset: Revset) = getLog(LogTemplates.commitGraphLogTemplate, revset)

    override fun getBookmarks(): Result<List<BookmarkItem>> {
        log.debug("Getting bookmarks")

        val result = executor.bookmarkList(LogTemplates.bookmarkListTemplate.spec)
        return if (result.isSuccess) {
            try {
                Result.success(parse(LogTemplates.bookmarkListTemplate, result.stdout))
            } catch (e: Exception) {
                log.error("Failed to parse bookmarks", e)
                Result.failure(e)
            }
        } else {
            log.error("Bookmark list command failed: ${result.stderr}")
            Result.failure(VcsException("Error from jj bookmark list: " + result.stderr))
        }
    }

    override fun getFileChanges(revision: Revision): Result<List<FileChange>> {
        log.debug("Getting file changes for revision: $revision")

        val result = executor.diffSummary(revision)

        return if (result.isSuccess) {
            try {
                val changes = parseFileChanges(result.stdout)
                log.debug("Parsed ${changes.size} file changes")
                Result.success(changes)
            } catch (e: Exception) {
                log.error("Failed to parse file changes", e)
                Result.failure(e)
            }
        } else {
            log.error("Diff summary command failed: ${result.stderr}")
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

            val status =
                when (statusChar) {
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
        filePaths: List<String> = emptyList()
    ): Result<List<T>> {
        log.debug("Getting log for revset: $revset, files: $filePaths")

        val result = executor.log(revset, template.spec, filePaths)
        return if (result.isSuccess) {
            try {
                Result.success(parse(template, result.stdout))
            } catch (e: Exception) {
                // TODO Improve logging
                log.error("Failed to parse", e)
                Result.failure(e)
            }
        } else {
            // TODO Improve logging
            Result.failure(VcsException("Error from jj log: " + result.stderr))
        }
    }

    private fun <T> parse(
        template: LogTemplate<T>,
        logOutput: String
    ): List<T> {
        val fields = logOutput.trim().split(FIELD_SEPARATOR)
        val recordSize = template.count
        return fields
            .chunked(recordSize)
            .filter { it.size == recordSize }
            .map { chunk -> template.take(chunk.iterator()) }
    }

    companion object {
        private const val FIELD_SEPARATOR = "\u0000" // Null byte
    }
}

fun <T> String.splitByComma(transform: (String) -> T) =
    if (this.isEmpty()) emptyList() else this.split(",").map(transform)
