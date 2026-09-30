package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.impl.projectlevelman.AllVcsesI
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * jj-idea-fchy: constructing the state model must create the platform's AllVcses service
 * synchronously, so a late pooled loader can't be the first to create it during project teardown
 * (its constructor registers an app-level EP listener that then leaks the Project).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuStateModelAllVcsesTest {
    private val project = projectFixture()

    @AfterEach
    fun drainStateModelLoads() = drainBackgroundLoads()

    @Test
    fun `state model construction eagerly creates AllVcses`() {
        project.get().stateModel

        project.get().getServiceIfCreated(AllVcsesI::class.java) shouldNotBe null
    }
}
