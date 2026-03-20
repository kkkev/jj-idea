package `in`.kkkev.jjidea.contract

import java.nio.file.Path

interface JjBackend {
    val workDir: Path
    fun run(vararg args: String): Result
    fun init()
    fun createFile(path: String, content: String)
    fun describe(message: String)
    fun newChange(message: String = "")
    fun bookmarkCreate(name: String)

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess get() = exitCode == 0
    }
}
