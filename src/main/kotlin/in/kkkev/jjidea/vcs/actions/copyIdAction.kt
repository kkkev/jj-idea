package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import `in`.kkkev.jjidea.jj.ChangeId
import java.awt.datatransfer.StringSelection

/**
 * Copy Change ID to clipboard.
 */
fun copyIdAction(id: ChangeId?) =
    nullAndDumbAwareAction(id, "log.action.copy.changeid", AllIcons.Actions.Copy) {
        val selection = StringSelection(id.toString())
        CopyPasteManager.getInstance().setContents(selection)
        log.info("Copied change ID to clipboard: $id")
    }
