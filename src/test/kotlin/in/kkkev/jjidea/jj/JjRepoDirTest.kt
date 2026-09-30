package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JjRepoDirTest {
    @TempDir
    lateinit var tmp: Path

    private fun workspace(name: String): Path = tmp.resolve(name).also { Files.createDirectories(it.resolve(".jj")) }

    @Test
    fun `default repo resolves to its own repo directory`() {
        val root = workspace("main")
        Files.createDirectories(root.resolve(".jj/repo"))

        resolveJjRepoDir(root) shouldBe root.resolve(".jj/repo")
    }

    @Test
    fun `secondary workspace resolves relative pointer against dot jj`() {
        val main = workspace("main")
        Files.createDirectories(main.resolve(".jj/repo"))
        val ws = workspace("ws2")
        Files.writeString(ws.resolve(".jj/repo"), "../../main/.jj/repo\n")

        resolveJjRepoDir(ws) shouldBe main.resolve(".jj/repo").toAbsolutePath().normalize()
    }

    @Test
    fun `secondary workspace resolves absolute pointer`() {
        val main = workspace("main")
        val ws = workspace("ws2")
        Files.writeString(ws.resolve(".jj/repo"), main.resolve(".jj/repo").toString())

        resolveJjRepoDir(ws) shouldBe main.resolve(".jj/repo")
    }

    @Test
    fun `empty pointer file falls back to the file path`() {
        val ws = workspace("ws2")
        Files.writeString(ws.resolve(".jj/repo"), "  \n")

        resolveJjRepoDir(ws) shouldBe ws.resolve(".jj/repo")
    }

    @Test
    fun `missing repo entry falls back to default path`() {
        val root = workspace("main")

        resolveJjRepoDir(root) shouldBe root.resolve(".jj/repo")
    }

    @Test
    fun `isUnderAnyDir matches equal and child paths`() {
        val dirs = setOf("/a/op_heads")

        isUnderAnyDir("/a/op_heads", dirs) shouldBe true
        isUnderAnyDir("/a/op_heads/heads/abc", dirs) shouldBe true
    }

    @Test
    fun `isUnderAnyDir rejects sibling with shared prefix and unrelated paths`() {
        val dirs = setOf("/a/op_heads")

        isUnderAnyDir("/a/op_heads2/x", dirs) shouldBe false
        isUnderAnyDir("/b/op_heads/x", dirs) shouldBe false
        isUnderAnyDir("/a/op_heads", emptySet()) shouldBe false
    }
}
