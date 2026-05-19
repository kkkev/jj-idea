package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsType
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuVcsTest {
    private val vcs = JujutsuVcs(mockk<Project>())

    @Test
    fun `VCS type is distributed so Commit tool window shows in mixed-VCS projects`() {
        vcs.type shouldBe VcsType.distributed
    }
}
