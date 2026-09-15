package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk

/**
 * [project] must come from the caller's own `projectFixture()` field (declared on the test
 * class, where the JUnit5 fixture extension can find and initialize it) — creating a fresh
 * `projectFixture()` here would be unregistered with that lifecycle and throw "Fixture framework
 * seems not be initialized" the moment [in.kkkev.jjidea.jj.JujutsuRepository.project] is read.
 */
fun mockRepo(project: Project = mockk(relaxed = true)): JujutsuRepository =
    mockk<JujutsuRepository>(relaxed = true).also {
        every { it.commandExecutor } returns mockk<CommandExecutor>()
        every { it.project } returns project
    }
