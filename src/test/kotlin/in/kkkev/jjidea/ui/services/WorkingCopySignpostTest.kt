package `in`.kkkev.jjidea.ui.services

import `in`.kkkev.jjidea.settings.JujutsuApplicationSettingsState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WorkingCopySignpostTest {
    @Test
    fun `no jj roots means neither action`() {
        signpostActionsFor(hasJjRoots = false, autoOpened = false, balloonShown = false) shouldBe
            SignpostActions(openToolWindow = false, showBalloon = false)
    }

    @Test
    fun `fresh project and fresh IDE means both actions`() {
        signpostActionsFor(hasJjRoots = true, autoOpened = false, balloonShown = false) shouldBe
            SignpostActions(openToolWindow = true, showBalloon = true)
    }

    @Test
    fun `already auto-opened means balloon only`() {
        signpostActionsFor(hasJjRoots = true, autoOpened = true, balloonShown = false) shouldBe
            SignpostActions(openToolWindow = false, showBalloon = true)
    }

    @Test
    fun `balloon already shown means open only`() {
        signpostActionsFor(hasJjRoots = true, autoOpened = false, balloonShown = true) shouldBe
            SignpostActions(openToolWindow = true, showBalloon = false)
    }

    @Test
    fun `both already done means neither action`() {
        signpostActionsFor(hasJjRoots = true, autoOpened = true, balloonShown = true) shouldBe
            SignpostActions(openToolWindow = false, showBalloon = false)
    }

    @Test
    fun `workingCopySignpostShown defaults to false`() {
        JujutsuApplicationSettingsState().workingCopySignpostShown shouldBe false
    }
}
