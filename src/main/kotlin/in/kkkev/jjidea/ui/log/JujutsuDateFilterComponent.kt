package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.*
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.vcs.actions.BackgroundActionGroup
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

/**
 * Filter component for dates.
 */
class JujutsuDateFilterComponent(private val tableModel: JujutsuLogTableModel) :
    JujutsuFilterComponent(JujutsuBundle.message("log.filter.date")) {
    private var selectedPeriod: DatePeriod? = null

    override fun getCurrentText(): String = selectedPeriod?.displayName ?: ""

    override fun isValueSelected(): Boolean = selectedPeriod != null

    fun initialize() {
        addChangeListener {
            applyFilter()
        }
    }

    override fun createActionGroup(): ActionGroup {
        val group = BackgroundActionGroup()

        // Add predefined date ranges
        DatePeriod.values().forEach { period ->
            group.add(SelectPeriodAction(period))
        }

        // Add clear option if period is selected
        if (selectedPeriod != null) {
            group.addSeparator()
            group.add(ClearFilterAction())
        }

        return group
    }

    override fun doResetFilter() {
        selectedPeriod = null
        notifyFilterChanged()
    }

    private fun applyFilter() {
        val cutoff = selectedPeriod?.getCutoffInstant()
        tableModel.setDateFilter(cutoff)
    }

    private inner class SelectPeriodAction(private val period: DatePeriod) : AnAction(period.displayName) {
        override fun actionPerformed(e: AnActionEvent) {
            selectedPeriod = period
            notifyFilterChanged()
        }
    }

    private inner class ClearFilterAction : AnAction(JujutsuBundle.message("log.filter.clear")) {
        override fun actionPerformed(e: AnActionEvent) {
            doResetFilter()
        }
    }

    enum class DatePeriod(
        val displayName: String,
        val days: Int
    ) {
        LAST_24_HOURS("Last 24 Hours", 1),
        LAST_7_DAYS("Last 7 Days", 7),
        LAST_30_DAYS("Last 30 Days", 30),
        LAST_3_MONTHS("Last 3 Months", 90),
        LAST_6_MONTHS("Last 6 Months", 180),
        LAST_YEAR("Last Year", 365);

        fun getCutoffInstant(): Instant {
            val now = Clock.System.now()
            return now.minus(days.days)
        }
    }
}
