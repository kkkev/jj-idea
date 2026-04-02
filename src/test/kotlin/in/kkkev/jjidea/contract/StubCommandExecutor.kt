package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.*

/**
 * Adapts [JjStub] to the [CommandExecutor] interface for integration tests.
 * Only implements methods needed by [CliLogService][in.kkkev.jjidea.jj.cli.CliLogService]
 * and [AnnotationParser][in.kkkev.jjidea.jj.cli.AnnotationParser].
 */
class StubCommandExecutor(private val stub: JjStub) : CommandExecutor {
    private fun toResult(r: JjBackend.Result) =
        CommandExecutor.CommandResult(r.exitCode, r.stdout, r.stderr)

    override fun log(
        revset: Revset,
        template: String?,
        filePaths: List<FilePath>,
        limit: Int?
    ) = toResult(
        stub.run(
            *buildList {
                add("log")
                add("-r")
                add(revset.toString())
                add("--no-graph")
                if (template != null) {
                    add("-T")
                    add(template)
                }
                if (limit != null) {
                    add("--limit")
                    add(limit.toString())
                }
            }.toTypedArray()
        )
    )

    override fun diffSummary(revision: Revision) =
        toResult(stub.run("diff", "--summary", "-r", revision.toString()))

    override fun bookmarkList(template: String?, remote: Remote?, tracked: Boolean) = toResult(
        stub.run(
            *buildList {
                add("bookmark")
                add("list")
                if (tracked) add("--tracked")
                if (remote != null) {
                    add("--remote")
                    add(remote.name)
                }
                if (template != null) {
                    add("-T")
                    add(template)
                }
            }.toTypedArray()
        )
    )

    override fun annotate(
        file: VirtualFile,
        revision: Revision,
        template: String?
    ) = toResult(
        stub.run(
            *buildList {
                add("file")
                add("annotate")
                add("-r")
                add(revision.toString())
                if (template != null) {
                    add("-T")
                    add(template)
                }
                add(file.path)
            }.toTypedArray()
        )
    )

    override fun status() = toResult(stub.run("status"))

    // -- Methods not needed for integration tests --

    override fun diff(filePath: String): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun show(
        filePath: FilePath,
        revision: Revision
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun isAvailable() = true
    override fun version() = "stub-1.0"

    override fun gitInit(colocate: Boolean): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun describe(
        description: Description,
        revision: Revision
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun new(
        description: Description,
        parentRevisions: List<Revision>
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun abandon(revision: Revision): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun edit(revision: Revision): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun bookmarkCreate(
        name: Bookmark,
        revision: Revision
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun bookmarkDelete(name: Bookmark): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun bookmarkRename(
        oldName: Bookmark,
        newName: Bookmark
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun bookmarkSet(
        name: Bookmark,
        revision: Revision,
        allowBackwards: Boolean
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun bookmarkTrack(name: Bookmark): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun bookmarkUntrack(name: Bookmark): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun diffGit(revision: Revision): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun restore(
        filePaths: List<FilePath>,
        revision: Revision
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun rebase(
        revisions: List<Revision>,
        destinations: List<Revision>,
        sourceMode: RebaseSourceMode,
        destinationMode: RebaseDestinationMode
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun gitFetch(
        remote: Remote?,
        allRemotes: Boolean
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun gitPush(
        remote: Remote?,
        bookmark: Bookmark?,
        allBookmarks: Boolean
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun squash(
        revision: Revision,
        filePaths: List<FilePath>,
        description: Description?,
        keepEmptied: Boolean
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun split(
        revision: Revision,
        filePaths: List<FilePath>,
        description: Description?,
        parallel: Boolean
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun gitRemoteList(): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun gitClone(
        source: String,
        destination: String,
        colocate: Boolean
    ): CommandExecutor.CommandResult = TODO("Not needed for integration tests")

    override fun configGet(key: String): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")

    override fun configSetUser(key: String, value: String): CommandExecutor.CommandResult =
        TODO("Not needed for integration tests")
}
