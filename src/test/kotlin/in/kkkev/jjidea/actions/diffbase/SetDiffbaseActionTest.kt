package `in`.kkkev.jjidea.actions.diffbase

import `in`.kkkev.jjidea.settings.DiffbaseStrategy
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [diffbaseMenuItems], the pure data-driven core of the "Set Diff Base" quick
 * action's popup (jj-idea-g1io, GitHub #43) — same "extract the availability logic" convention as
 * `editableEntry` in `actions/change/EditChangeAction.kt`. Only the fixed-strategy rows and the
 * "Custom: <revset>" row are covered here; the non-data-driven "Choose revision..." and
 * "Configure in Settings..." entries live in [SetDiffbaseAction] itself.
 */
class SetDiffbaseActionTest {
    @Test
    fun `three fixed strategies, none selected, when a custom revset is active without a match`() {
        // Unreachable via the settings UI (CUSTOM_REVSET always carries a revset once applied),
        // but guards diffbaseMenuItems against crashing on a blank customRevset either way.
        val items = diffbaseMenuItems(DiffbaseStrategy.CUSTOM_REVSET, "")

        items shouldHaveSize 3
        items.none { it.selected } shouldBe true
    }

    @Test
    fun `WORKING_COPY_PARENT is selected and alone`() {
        val items = diffbaseMenuItems(DiffbaseStrategy.WORKING_COPY_PARENT, "")

        items shouldHaveSize 3
        items.single { it.selected }.strategy shouldBe DiffbaseStrategy.WORKING_COPY_PARENT
    }

    @Test
    fun `IMMUTABLE_ANCESTOR is selected and alone`() {
        val items = diffbaseMenuItems(DiffbaseStrategy.IMMUTABLE_ANCESTOR, "")

        items shouldHaveSize 3
        items.single { it.selected }.strategy shouldBe DiffbaseStrategy.IMMUTABLE_ANCESTOR
    }

    @Test
    fun `PREVIOUS_COMMIT is selected and alone`() {
        val items = diffbaseMenuItems(DiffbaseStrategy.PREVIOUS_COMMIT, "")

        items shouldHaveSize 3
        items.single { it.selected }.strategy shouldBe DiffbaseStrategy.PREVIOUS_COMMIT
    }

    @Test
    fun `CUSTOM_REVSET with a non-blank revset adds a fourth, selected, Custom row`() {
        val items = diffbaseMenuItems(DiffbaseStrategy.CUSTOM_REVSET, "trunk()")

        items shouldHaveSize 4
        val custom = items.last()
        custom.strategy shouldBe DiffbaseStrategy.CUSTOM_REVSET
        custom.selected shouldBe true
        custom.customRevset shouldBe "trunk()"
        custom.label shouldBe "Custom: trunk()"
        items.dropLast(1).none { it.selected } shouldBe true
    }

    @Test
    fun `CUSTOM_REVSET with a blank revset adds no Custom row`() {
        val items = diffbaseMenuItems(DiffbaseStrategy.CUSTOM_REVSET, "   ")

        items shouldHaveSize 3
    }

    @Test
    fun `the Custom row trims surrounding whitespace from the revset`() {
        val items = diffbaseMenuItems(DiffbaseStrategy.CUSTOM_REVSET, "  trunk()  ")

        items.last().customRevset shouldBe "trunk()"
    }
}
