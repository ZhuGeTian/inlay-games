package dev.gomoku.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

internal fun texasAction(project: Project, session: GomokuSession, type: String) {
    if (!session.canTexasCommand(type)) return
    if (type != "raise") { session.texasCommand(type); return }
    val s = session.texasState
    val value = Messages.showInputDialog(project,
        "本轮累计投入到多少（${s.minRaiseTo}–${s.maxRaiseTo}）？\n已投入 ${s.bets[session.seat - 1]}，不是额外增加的数量。",
        "加注", null, s.minRaiseTo.toString(), object : InputValidator {
            override fun checkInput(input: String) = input.trim().toLongOrNull()?.let { validTexasRaise(s, it) } == true
            override fun canClose(input: String) = checkInput(input)
        })
    value?.trim()?.toLongOrNull()?.let { session.texasCommand(type, it, s.revision) }
}

open class TexasCommandAction(private val command: String) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.getServiceIfCreated(GomokuSession::class.java)?.canTexasCommand(command) == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        texasAction(project, project.getService(GomokuSession::class.java), command)
    }
}
class TexasFoldAction : TexasCommandAction("fold")
class TexasCheckAction : TexasCommandAction("check")
class TexasCallAction : TexasCommandAction("call")
class TexasRaiseAction : TexasCommandAction("raise")
class TexasAllInAction : TexasCommandAction("allin")
class TexasReadyAction : TexasCommandAction("ready")
