package `in`.kkkev.jjidea.contract

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Helper to run jj commands in temp repos via ProcessBuilder.
 * Used by contract tests to verify real jj output matches the plugin's parsers.
 */
class JjCli(override val workDir: Path) : JjBackend {
    override fun run(vararg args: String): JjBackend.Result {
        val process = ProcessBuilder(listOf("jj") + args)
            .directory(workDir.toFile())
            .apply {
                environment()["NO_COLOR"] = "1"
                environment()["JJ_USER"] = "Test User"
                environment()["JJ_EMAIL"] = "test@example.com"
            }
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return JjBackend.Result(exitCode, stdout, stderr)
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

    override fun makeBookmarkConflicted(name: String, revisionA: String, revisionB: String) {
        // Mirrors what actually produces a conflicted/divergent bookmark: two operations racing
        // to move the same bookmark to different targets (e.g. concurrent `jj bookmark set` from
        // two workspaces). Verified by hand in scratch repos on jj 0.37/0.39/0.44: two
        // `bookmark set --at-op` calls pinned to the SAME operation only diverge into a real
        // conflict if BOTH are genuine state changes away from that operation's bookmark target —
        // a `set` that doesn't change the target is silently a no-op (jj writes no new operation
        // for it on 0.37), so the pre-op baseline must differ from both revisionA and revisionB.
        // A fresh empty commit guarantees that. revisionA/revisionB are resolved to concrete
        // change ids *before* that commit is created, since they may be relative revsets (e.g.
        // `@`) that would otherwise silently start pointing at the new baseline commit itself.
        val resolvedA = resolveChangeId(revisionA)
        val resolvedB = resolveChangeId(revisionB)

        val baseline = run("new", "-m", "jj-idea test baseline for $name")
        check(baseline.isSuccess) { "jj new (conflict baseline) failed: ${baseline.stderr}" }
        val create = run("bookmark", "create", name)
        check(create.isSuccess) { "jj bookmark create failed: ${create.stderr}" }

        val op = run("op", "log", "--no-graph", "--limit", "1", "-T", "id.short()")
        check(op.isSuccess) { "jj op log failed: ${op.stderr}" }
        val opId = op.stdout.trim()

        val first = run("bookmark", "set", name, "-r", resolvedA, "--allow-backwards", "--at-op", opId)
        check(first.isSuccess) { "jj bookmark set (first target) failed: ${first.stderr}" }
        val second = run("bookmark", "set", name, "-r", resolvedB, "--allow-backwards", "--at-op", opId)
        check(second.isSuccess) { "jj bookmark set (second target) failed: ${second.stderr}" }
    }

    private fun resolveChangeId(revision: String): String {
        val result = run("log", "--no-graph", "-r", revision, "-T", "change_id.short()")
        check(result.isSuccess) { "Failed to resolve revision '$revision': ${result.stderr}" }
        return result.stdout.trim()
    }

    override fun renameFile(from: String, to: String) {
        val src = workDir.resolve(from)
        val dst = workDir.resolve(to)
        dst.parent.createDirectories()
        Files.move(src, dst)
    }

    override fun addGitRemote(name: String, url: String) {
        val result = run("git", "remote", "add", name, url)
        check(result.isSuccess) { "jj git remote add failed: ${result.stderr}" }
    }

    override fun split(message: String, filePaths: List<String>, revision: String) {
        val args = mutableListOf("split", "-r", revision, "-m", message)
        args.addAll(filePaths)
        val result = run(*args.toTypedArray())
        check(result.isSuccess) { "jj split failed: ${result.stderr}" }
    }

    companion object {
        fun isAvailable(): Boolean = try {
            val process = ProcessBuilder("jj", "--version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
