package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsType
import com.intellij.vcs.AnnotationProviderEx
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuVcsTest {
    private val vcs = JujutsuVcs(mockk<Project>())

    @Test
    fun `VCS type is distributed so Commit tool window shows in mixed-VCS projects`() {
        vcs.type shouldBe VcsType.distributed
    }

    // jj-idea-hq4d: the platform's built-in Annotate action on a file opened from history
    // (AnnotateVcsVirtualFileAction.isEnabled) requires the VCS's annotation provider to
    // implement AnnotationProviderEx, not just the plain AnnotationProvider interface.
    @Test
    fun `annotation provider implements AnnotationProviderEx so historical-file Annotate is enabled`() {
        vcs.annotationProvider.shouldBeInstanceOf<AnnotationProviderEx>()
    }
}
