package `in`.kkkev.jjidea.jj.cli

import com.intellij.openapi.diagnostic.Logger
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.FileChangeStatus
import `in`.kkkev.jjidea.jj.LogEntry

/**
 * CLI-based implementation of JujutsuLogService.
 * Centralizes all template generation and parsing logic.
 */
class CliLogService(private val executor: CommandExecutor) : LogService {

    private val log = Logger.getInstance(CliLogService::class.java)

    companion object {
        /**
         * Full template with all metadata fields (15 fields).
         * Used when author/committer information is needed.
         */
        private val FULL_TEMPLATE = """
            change_id ++ "\0" ++
            change_id.shortest() ++ "\0" ++
            commit_id ++ "\0" ++
            description ++ "\0" ++
            bookmarks ++ "\0" ++
            parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(", ") ++ "\0" ++
            if(current_working_copy, "true", "false") ++ "\0" ++
            if(conflict, "true", "false") ++ "\0" ++
            if(empty, "true", "false") ++ "\0" ++
            author.timestamp().utc().format("%s") ++ "\0" ++
            committer.timestamp().utc().format("%s") ++ "\0" ++
            author.name() ++ "\0" ++
            author.email() ++ "\0" ++
            committer.name() ++ "\0" ++
            committer.email() ++ "\0"
        """.trimIndent().replace("\n", " ")

        /**
         * Basic template without author/committer fields (9 fields).
         * More efficient when only basic commit info is needed.
         */
        private val BASIC_TEMPLATE = """
            change_id ++ "\0" ++
            change_id.shortest() ++ "\0" ++
            commit_id ++ "\0" ++
            description ++ "\0" ++
            bookmarks ++ "\0" ++
            parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(", ") ++ "\0" ++
            if(current_working_copy, "true", "false") ++ "\0" ++
            if(conflict, "true", "false") ++ "\0" ++
            if(empty, "true", "false") ++ "\0"
        """.trimIndent().replace("\n", " ")

        /**
         * Minimal template for refs (4 fields).
         * Includes short IDs for efficient display.
         */
        private val REFS_TEMPLATE = """
            change_id ++ "\0" ++
            change_id.shortest() ++ "\0" ++
            bookmarks ++ "\0" ++
            if(current_working_copy, "true", "false") ++ "\0"
        """.trimIndent().replace("\n", " ")

        /**
         * Minimal template for commit graph (4 fields).
         * Includes short IDs for parents to match format used elsewhere.
         */
        private val GRAPH_TEMPLATE = """
            change_id ++ "\0" ++
            change_id.shortest() ++ "\0" ++
            parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(", ") ++ "\0" ++
            committer.timestamp().utc().format("%s") ++ "\0"
        """.trimIndent().replace("\n", " ")
    }

    override fun getLog(revisions: String, filePaths: List<String>): Result<List<LogEntry>> {
        log.debug("Getting log for revisions: $revisions, files: $filePaths")

        val result = executor.log(revisions, FULL_TEMPLATE, filePaths)

        return if (result.isSuccess) {
            try {
                val entries = JujutsuLogParser.parseLog(result.stdout)
                log.debug("Parsed ${entries.size} log entries")
                Result.success(entries)
            } catch (e: Exception) {
                log.error("Failed to parse log output", e)
                Result.failure(e)
            }
        } else {
            log.error("Log command failed: ${result.stderr}")
            Result.failure(Exception("Log command failed: ${result.stderr}"))
        }
    }

    override fun getLogBasic(revisions: String, filePaths: List<String>): Result<List<LogEntry>> {
        log.debug("Getting basic log for revisions: $revisions, files: $filePaths")

        val result = executor.log(revisions, BASIC_TEMPLATE, filePaths)

        return if (result.isSuccess) {
            try {
                val entries = JujutsuLogParser.parseLog(result.stdout)
                log.debug("Parsed ${entries.size} basic log entries")
                Result.success(entries)
            } catch (e: Exception) {
                log.error("Failed to parse basic log output", e)
                Result.failure(e)
            }
        } else {
            log.error("Basic log command failed: ${result.stderr}")
            Result.failure(Exception("Log command failed: ${result.stderr}"))
        }
    }

    override fun getRefs(): Result<List<LogService.JujutsuRef>> {
        log.debug("Getting refs")

        val result = executor.log("all()", REFS_TEMPLATE)

        return if (result.isSuccess) {
            try {
                val refs = parseRefs(result.stdout)
                log.debug("Parsed ${refs.size} refs")
                Result.success(refs)
            } catch (e: Exception) {
                log.error("Failed to parse refs", e)
                Result.failure(e)
            }
        } else {
            log.error("Refs command failed: ${result.stderr}")
            Result.failure(Exception("Refs command failed: ${result.stderr}"))
        }
    }

    override fun getCommitGraph(revisions: String): Result<List<LogService.CommitGraphNode>> {
        log.debug("Getting commit graph for revisions: $revisions")

        val result = executor.log(revisions, GRAPH_TEMPLATE)

        return if (result.isSuccess) {
            try {
                val nodes = parseCommitGraph(result.stdout)
                log.debug("Parsed ${nodes.size} commit graph nodes")
                Result.success(nodes)
            } catch (e: Exception) {
                log.error("Failed to parse commit graph", e)
                Result.failure(e)
            }
        } else {
            log.error("Commit graph command failed: ${result.stderr}")
            Result.failure(Exception("Commit graph command failed: ${result.stderr}"))
        }
    }

    /**
     * Parse refs output (4 fields per entry).
     */
    private fun parseRefs(output: String): List<LogService.JujutsuRef> {
        val trimmed = output.trim()
        if (trimmed.isBlank()) return emptyList()

        val fields = trimmed.split("\u0000")
        val refs = mutableListOf<LogService.JujutsuRef>()

        fields.chunked(4).forEach { chunk ->
            if (chunk.size == 4) {
                val changeId = ChangeId(chunk[0], chunk[1])
                val bookmarks = chunk[2]
                val isWorkingCopy = chunk[3].toBoolean()

                // Add working copy ref
                if (isWorkingCopy) {
                    refs.add(
                        LogService.JujutsuRef(
                            changeId = changeId,
                            name = "@",
                            type = LogService.RefType.WORKING_COPY
                        )
                    )
                }

                // Add bookmark refs
                if (bookmarks.isNotEmpty()) {
                    bookmarks.split(",").forEach { bookmark ->
                        refs.add(
                            LogService.JujutsuRef(
                                changeId = changeId,
                                name = bookmark.trim(),
                                type = LogService.RefType.BOOKMARK
                            )
                        )
                    }
                }
            }
        }

        return refs
    }

    /**
     * Parse commit graph output (4 fields per entry).
     */
    private fun parseCommitGraph(output: String): List<LogService.CommitGraphNode> {
        val trimmed = output.trim()
        if (trimmed.isBlank()) return emptyList()

        val fields = trimmed.split("\u0000")

        return fields.chunked(4).mapNotNull { chunk ->
            if (chunk.size == 4) {
                val changeId = ChangeId(chunk[0], chunk[1])
                val parentIds = if (chunk[2].isNotEmpty()) {
                    // Format is "fullId~shortId, fullId~shortId"
                    // Extract both full and short IDs
                    chunk[2].split(",").map { parent ->
                        val parts = parent.trim().split("~")
                        if (parts.size == 2) {
                            ChangeId(parts[0], parts[1])
                        } else {
                            // Fallback if format is unexpected
                            ChangeId(parts[0])
                        }
                    }
                } else {
                    emptyList()
                }
                val timestamp = chunk[3].toLongOrNull()?.times(1000) ?: 0L

                LogService.CommitGraphNode(
                    changeId = changeId,
                    parentIds = parentIds,
                    timestamp = timestamp
                )
            } else {
                null
            }
        }
    }

    override fun getFileChanges(revision: String): Result<List<FileChange>> {
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
}