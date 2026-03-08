package `in`.kkkev.jjidea.contract

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Helper to run jj commands in temp repos via ProcessBuilder.
 * Used by contract tests to verify real jj output matches the plugin's parsers.
 */
class JjCli(val workDir: Path) {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess get() = exitCode == 0
    }

    fun run(vararg args: String): Result {
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

        return Result(exitCode, stdout, stderr)
    }

    fun init() {
        val result = run("git", "init", "--colocate")
        check(result.isSuccess) { "jj git init failed: ${result.stderr}" }
    }

    fun createFile(path: String, content: String) {
        val file = workDir.resolve(path)
        file.parent.createDirectories()
        file.writeText(content)
    }

    fun describe(message: String) {
        val result = run("describe", "-m", message)
        check(result.isSuccess) { "jj describe failed: ${result.stderr}" }
    }

    fun newChange(message: String = "") {
        val result = if (message.isNotEmpty()) {
            run("new", "-m", message)
        } else {
            run("new")
        }
        check(result.isSuccess) { "jj new failed: ${result.stderr}" }
    }

    fun bookmarkCreate(name: String) {
        val result = run("bookmark", "create", name)
        check(result.isSuccess) { "jj bookmark create failed: ${result.stderr}" }
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
