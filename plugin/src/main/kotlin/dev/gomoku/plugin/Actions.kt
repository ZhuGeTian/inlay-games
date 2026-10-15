package dev.gomoku.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.util.Locale
import javax.swing.JComponent

const val DEFAULT_SERVER = "wss://zhugetian.com/gomoku/ws"
private const val SERVER_PROPERTY = "gomoku.server"

private fun editor(e: AnActionEvent): Editor? = e.getData(CommonDataKeys.EDITOR)
    ?: e.project?.let { FileEditorManager.getInstance(it).selectedTextEditor }

private fun start(e: AnActionEvent, join: Boolean, kind: GameKind = GameKind.GOMOKU) {
    val project = e.project ?: return
    val session = project.getService(GomokuSession::class.java)
    if (session.hasSession) { session.notice("请先退出当前房间"); return }
    val editor = editor(e)
    if (editor == null) { session.notice("请先打开一个文本文件，再创建或加入房间"); return }
    val dialog = RoomDialog(project, join, kind)
    if (dialog.showAndGet()) {
        PropertiesComponent.getInstance().setValue(SERVER_PROPERTY, dialog.server)
        session.start(editor, dialog.server, if (join) dialog.code else null, kind)
    }
}

private class RoomDialog(project: Project, private val join: Boolean, kind: GameKind) : DialogWrapper(project) {
    private val serverField = JBTextField(PropertiesComponent.getInstance().getValue(SERVER_PROPERTY, DEFAULT_SERVER))
    private val codeField = JBTextField()
    val server: String get() = serverField.text.trim()
    val code: String get() = codeField.text.trim().uppercase(Locale.ROOT)
    init { title = (if (join) "加入" else "创建") + kind.label + "房间"; setOKButtonText(if (join) "加入" else "创建"); init() }
    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("服务器地址", serverField)
        .apply { if (join) addLabeledComponent("8 位房间码", codeField) }
        .panel.apply { preferredSize = Dimension(520, if (join) 90 else 55) }
    override fun getPreferredFocusedComponent(): JComponent = if (join) codeField else serverField
    override fun doValidate(): ValidationInfo? {
        val uri = runCatching { URI(server) }.getOrNull()
        if (uri?.host.isNullOrEmpty() || uri?.rawUserInfo != null || uri?.fragment != null ||
            !(uri?.scheme == "wss" || (uri?.scheme == "ws" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")))) {
            return ValidationInfo("请填写 wss:// 地址；本机调试可用 ws://localhost", serverField)
        }
        if (join && !code.matches(Regex("[A-HJ-NP-Z2-9]{8}"))) return ValidationInfo("请输入 8 位房间码", codeField)
        return null
    }
}

class CreateRoomAction : DumbAwareAction() { override fun actionPerformed(e: AnActionEvent) = start(e, false) }
class CreateDdzRoomAction : DumbAwareAction() { override fun actionPerformed(e: AnActionEvent) = start(e, false, GameKind.DDZ) }
class JoinDdzRoomAction : DumbAwareAction() { override fun actionPerformed(e: AnActionEvent) = start(e, true, GameKind.DDZ) }
class CreateTexasRoomAction : DumbAwareAction() { override fun actionPerformed(e: AnActionEvent) = start(e, false, GameKind.TEXAS) }
class JoinTexasRoomAction : DumbAwareAction() { override fun actionPerformed(e: AnActionEvent) = start(e, true, GameKind.TEXAS) }
class JoinRoomAction : DumbAwareAction() { override fun actionPerformed(e: AnActionEvent) = start(e, true) }
class ShowBoardAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val session = e.project?.getService(GomokuSession::class.java) ?: return
        editor(e)?.let(session::showBoard) ?: session.notice("请先打开一个文本文件")
    }
}
class ToggleBoardAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val session = e.project?.getServiceIfCreated(GomokuSession::class.java)
        e.presentation.isEnabled = editor(e) != null && session != null && (session.hasSession || session.isBoardVisible)
    }
    override fun actionPerformed(e: AnActionEvent) {
        val session = e.project?.getService(GomokuSession::class.java) ?: return
        editor(e)?.let(session::toggleBoard)
    }
}
class FontSizeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = project.getService(GomokuSession::class.java)
        val value = Messages.showInputDialog(project, "输入字号（8–32）", "调节字号", null,
            session.fontSize.toString(), object : InputValidator {
                override fun checkInput(input: String) = input.trim().toIntOrNull()?.let { it in 8..32 } == true
                override fun canClose(input: String) = checkInput(input)
            })
        value?.trim()?.toIntOrNull()?.let { session.fontSize = it }
    }
}
class CopyRoomCodeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val session = e.project?.getService(GomokuSession::class.java) ?: return
        if (session.roomCode.isEmpty()) session.notice("请先创建或加入房间")
        else CopyPasteManager.getInstance().setContents(StringSelection(session.roomCode))
    }
}
class ReconnectAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.getService(GomokuSession::class.java)?.reconnect() }
}
class ResignAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project?.getServiceIfCreated(GomokuSession::class.java)?.isTexas != true }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = project.getService(GomokuSession::class.java)
        val message = if (session.isDdz) "认输会判你所在阵营失败并结算本局，确定吗？" else "确定认输并结束这局棋吗？"
        if (Messages.showYesNoDialog(project, message, "联机小游戏", Messages.getQuestionIcon()) == Messages.YES) session.resign()
    }
}
class LeaveRoomAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = project.getService(GomokuSession::class.java)
        val message = if (session.isDdz || session.isTexas) "退出将关闭整个房间，三人的对局和房内积分都会结束，确定吗？" else "退出后将失去本次重连身份；进行中的棋局会判你认输。"
        if (Messages.showYesNoDialog(project, message, "退出房间", Messages.getQuestionIcon()) == Messages.YES) session.leave()
    }
}
