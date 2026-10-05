package `in`.kkkev.jjidea.ui.editor

import com.intellij.openapi.vfs.VirtualFile
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ConflictBannerRefresherTest {
    private val a = mockk<VirtualFile>()
    private val b = mockk<VirtualFile>()
    private val c = mockk<VirtualFile>()

    @Test
    fun `unchanged conflicted set flips nothing`() {
        conflictStatusFlips(setOf(a, b), setOf(a, b)) shouldBe emptySet()
    }

    @Test
    fun `undo restoring a conflict flips that file`() {
        conflictStatusFlips(setOf(a), setOf(a, b)) shouldBe setOf(b)
    }

    @Test
    fun `resolving and newly conflicting files both flip`() {
        conflictStatusFlips(setOf(a, b), setOf(b, c)) shouldBe setOf(a, c)
    }
}
