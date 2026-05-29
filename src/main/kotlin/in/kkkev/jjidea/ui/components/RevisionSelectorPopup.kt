package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.ui.popup.JBPopupFactory
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.util.runLater

object RevisionSelectorPopup {
    fun show(titleKey: String, repo: JujutsuRepository, filter: Filter, onSelected: (Revision) -> Unit) {
        runLater {
            val panel = PopupPanel(repo, filter, onSelected)
            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel.searchField)
                .setTitle(JujutsuBundle.message(titleKey))
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()
            panel.setPopup(popup)
            popup.showCenteredInCurrentWindow(repo.project)
            panel.loadData()
        }
    }

    private class PopupPanel(
        repo: JujutsuRepository,
        filter: Filter,
        private val onSelected: (Revision) -> Unit
    ) : RevisionChoicePanel(repo, filter) {
        override fun onSelect(item: RevisionChoice) = onSelected(item.revision)
    }
}
