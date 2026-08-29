package `in`.kkkev.jjidea.ui.services

import `in`.kkkev.jjidea.settings.JujutsuApplicationSettingsState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

class SponsorAskTest {
    @Test
    fun `unrecorded first run only records, never shows`() {
        sponsorAskActionsFor(nowMillis = 1_000_000L, firstRunEpochMillis = 0L, askShown = false) shouldBe
            SponsorAskActions(recordFirstRun = true, showAsk = false)
    }

    @Test
    fun `29 days elapsed does not show the ask`() {
        val firstRun = 1_000_000L
        val now = firstRun + 29 * DAY_MILLIS
        sponsorAskActionsFor(nowMillis = now, firstRunEpochMillis = firstRun, askShown = false) shouldBe
            SponsorAskActions(recordFirstRun = false, showAsk = false)
    }

    @Test
    fun `exactly 30 days elapsed shows the ask`() {
        val firstRun = 1_000_000L
        val now = firstRun + 30 * DAY_MILLIS
        sponsorAskActionsFor(nowMillis = now, firstRunEpochMillis = firstRun, askShown = false) shouldBe
            SponsorAskActions(recordFirstRun = false, showAsk = true)
    }

    @Test
    fun `already shown means never shown again, even much later`() {
        val firstRun = 1_000_000L
        val now = firstRun + 60 * DAY_MILLIS
        sponsorAskActionsFor(nowMillis = now, firstRunEpochMillis = firstRun, askShown = true) shouldBe
            SponsorAskActions(recordFirstRun = false, showAsk = false)
    }

    @Test
    fun `clock skewed backwards does not show the ask`() {
        val firstRun = 1_000_000_000L
        val now = firstRun - DAY_MILLIS
        sponsorAskActionsFor(nowMillis = now, firstRunEpochMillis = firstRun, askShown = false) shouldBe
            SponsorAskActions(recordFirstRun = false, showAsk = false)
    }

    @Test
    fun `firstRunEpochMillis and sponsorAskShown default appropriately`() {
        val state = JujutsuApplicationSettingsState()
        state.firstRunEpochMillis shouldBe 0L
        state.sponsorAskShown shouldBe false
    }
}
