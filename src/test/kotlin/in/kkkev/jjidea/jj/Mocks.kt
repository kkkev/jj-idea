package `in`.kkkev.jjidea.jj

import io.mockk.every
import io.mockk.mockk

val mockRepo: JujutsuRepository
    get() {
        val mock = mockk<JujutsuRepository>()
        every { mock.commandExecutor } returns mockk<CommandExecutor>()
        return mock
    }
