package `in`.kkkev.jjidea.settings

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
class JujutsuSettingsPlatformTest {
    private val project = projectFixture()

    @Test
    fun `settings service is available and has default state`() {
        val settings = JujutsuSettings.getInstance(project.get())
        settings.state.jjExecutablePath shouldBe "jj"
        settings.state.autoRefreshEnabled shouldBe true
    }
}
