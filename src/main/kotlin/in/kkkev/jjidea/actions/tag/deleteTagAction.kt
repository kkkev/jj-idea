package `in`.kkkev.jjidea.actions.tag

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.invalidate

fun deleteTagAction(repo: JujutsuRepository, tag: Tag) = nullAndDumbAwareAction(
    tag,
    "action.tag.delete",
    AllIcons.General.Delete
) {
    if (Messages.showYesNoDialog(
            repo.project,
            JujutsuBundle.message("action.tag.delete.confirm.message", tag.name),
            JujutsuBundle.message("action.tag.delete.confirm.title", tag.name),
            Messages.getWarningIcon()
        ) != Messages.YES
    ) {
        log.info("User cancelled deletion of tag ${tag.name}")
        return@nullAndDumbAwareAction
    }

    repo.commandExecutor.createCommand { tagDelete(tag) }
        .onSuccess {
            repo.invalidate()
            log.info("Deleted tag ${tag.name}")
        }
        .onFailure { tellUser(repo.project, "action.tag.delete.error") }
        .executeAsync()
}
