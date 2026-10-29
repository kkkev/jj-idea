package `in`.kkkev.jjidea.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/** What a copied name refers to; picks the menu label ("Copy Bookmark Name", "Copy Tag Name", "Copy Folder Path"). */
enum class RefNameKind(val messageKey: String) {
    BOOKMARK("action.bookmark.copy.name"),
    TAG("action.tag.copy.name"),
    FOLDER("action.bookmark.folder.copy.path")
}

/** Copies a bookmark, tag or bookmark-folder name to the clipboard. Disabled when [name] is null. */
fun copyRefNameAction(name: String?, kind: RefNameKind) =
    nullAndDumbAwareAction(name, kind.messageKey, AllIcons.Actions.Copy) {
        CopyPasteManager.getInstance().setContents(StringSelection(target))
        log.info("Copied ref name to clipboard: $target")
    }
