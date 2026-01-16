package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.jj.ChangeId

interface LayoutCalculator {
    fun calculate(entries: List<GraphEntry>): GraphLayout
}

class LayoutCalculatorImpl : LayoutCalculator {
    override fun calculate(entries: List<GraphEntry>): GraphLayout {
        data class State(
            val children: Map<ChangeId, Set<ChangeId>>,
            val lanes:Map<ChangeId, Int>,
            val rows:List<RowLayout>
        )

        val state = State(emptyMap(), emptyMap(), emptyList())
        val rows = entries.fold(state) { state, e ->
            val childRow = RowLayout(e.current, 0, listOf(0), listOf(0), emptySet())
            State(state.children, state.lanes, state.rows + childRow)
        }

        return GraphLayout(state.rows)
    }
}