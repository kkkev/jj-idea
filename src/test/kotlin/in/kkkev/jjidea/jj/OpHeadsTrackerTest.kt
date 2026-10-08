package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** jj-idea-2570.12: noticing a new operation without a file-watcher event. */
class OpHeadsTrackerTest {
    private val heads = mutableMapOf<String, Set<String>?>()
    private var reads = 0
    private val tracker = OpHeadsTracker {
        reads++
        heads[it]
    }

    @Test
    fun `first sight records a baseline and reports no change`() {
        heads["a"] = setOf("op1")
        tracker.update("a") shouldBe false
    }

    @Test
    fun `unchanged heads report no change however often they are checked`() {
        heads["a"] = setOf("op1")
        tracker.update("a")
        repeat(1_000) { tracker.update("a") shouldBe false }
        reads shouldBe 1_001 // one tiny directory listing per check, no other work
    }

    @Test
    fun `a new operation reports exactly one change`() {
        heads["a"] = setOf("op1")
        tracker.update("a")
        heads["a"] = setOf("op2")
        tracker.update("a") shouldBe true
        tracker.update("a") shouldBe false // the new heads are now the baseline
    }

    @Test
    fun `repos are tracked independently`() {
        heads["a"] = setOf("op1")
        heads["b"] = setOf("op9")
        tracker.update("a")
        tracker.update("b")
        heads["a"] = setOf("op2")
        tracker.update("b") shouldBe false
        tracker.update("a") shouldBe true
    }

    @Test
    fun `unreadable heads never report a change and keep the old baseline`() {
        heads["a"] = setOf("op1")
        tracker.update("a")
        heads["a"] = null
        tracker.update("a") shouldBe false
        heads["a"] = setOf("op1")
        tracker.update("a") shouldBe false
    }

    @Test
    fun `reads the head ids from a real repo layout and gives null when it is missing`() {
        val root = Files.createTempDirectory("opheads")
        try {
            readOpHeadIds(root) shouldBe null
            val heads = Files.createDirectories(root.resolve(".jj/repo/op_heads/heads"))
            Files.createFile(heads.resolve("abc123"))
            Files.createFile(heads.resolve("def456"))
            readOpHeadIds(root) shouldBe setOf("abc123", "def456")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
