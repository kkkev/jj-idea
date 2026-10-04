package `in`.kkkev.jjidea.settings

import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** jj-idea-44db: project settings must not land in the VCS-visible `.idea/jujutsu.xml`. */
class JujutsuSettingsStorageTest {
    private val storages = JujutsuSettings::class.java.getAnnotation(State::class.java).storages

    @Test
    fun `primary storage is the workspace file`() {
        storages.first { !it.deprecated }.value shouldBe StoragePathMacros.WORKSPACE_FILE
    }

    @Test
    fun `legacy jujutsu xml is only a deprecated fallback`() {
        storages.filter { it.value == "jujutsu.xml" }.map { it.deprecated } shouldBe listOf(true)
    }
}
