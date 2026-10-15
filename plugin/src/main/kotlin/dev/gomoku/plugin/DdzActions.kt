package dev.gomoku.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

open class DdzCommandAction(private val command: String, private val value: Int = 0) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val session = e.project?.getServiceIfCreated(GomokuSession::class.java)
        e.presentation.isEnabled = session?.canDdzCommand(command, value) == true
    }
    override fun actionPerformed(e: AnActionEvent) { e.project?.getService(GomokuSession::class.java)?.ddzCommand(command, value) }
}
class DdzBid0Action : DdzCommandAction("bid", 0)
class DdzBid1Action : DdzCommandAction("bid", 1)
class DdzBid2Action : DdzCommandAction("bid", 2)
class DdzBid3Action : DdzCommandAction("bid", 3)
class DdzPlayAction : DdzCommandAction("play")
class DdzPassAction : DdzCommandAction("pass")
class DdzReadyAction : DdzCommandAction("ready")

class DdzStartAction : DdzCommandAction("start")
