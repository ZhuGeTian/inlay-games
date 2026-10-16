package dev.gomoku.plugin

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import dev.gomoku.core.TEXAS_PROTOCOL_VERSION
import dev.gomoku.core.TexasClientMessage
import dev.gomoku.core.wireJson
import dev.gomoku.core.TexasServerMessage
import dev.gomoku.core.TexasState
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.lang.reflect.Proxy
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import javax.swing.KeyStroke

class TexasControlsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue("gomoku.fontSize") }
        finally { super.tearDown() }
    }

    private fun session(): GomokuSession {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "val source = 1\n")
        EditorTestUtil.setEditorVisibleSize(myFixture.editor, 140, 60)
        return project.getService(GomokuSession::class.java).also {
            it.receiveTexas(TexasServerMessage("welcome", roomCode = "AABBCCDD", seat = 1, token = "x".repeat(43),
                state = TexasState(status = "playing", street = "flop", hand = listOf(48,45), board = listOf(38,43,32),
                    stacks = listOf(990,990,990), bets = listOf(0,0,0), contributions = listOf(10,10,10),
                    inHand = listOf(true,true,true), turn = 1, dealer = 3, smallBlindSeat = 1, bigBlindSeat = 2,
                    pot = 30, minRaiseTo = 10, maxRaiseTo = 990, legalActions = listOf("fold","check","raise","allin"),
                    online = listOf(true,true,true), round = 1, revision = it.texasState.revision + 1)))
            it.showBoard(myFixture.editor)
        }
    }

    private fun enter() = ActionUtil.getActions(myFixture.editor.contentComponent).single {
        it.shortcutSet.shortcuts.contains(KeyboardShortcut(KeyStroke.getKeyStroke("ENTER"), null))
    }
    private fun click(x: Int, y: Int) {
        val c = myFixture.editor.contentComponent
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, x, y, x, y, 1, false, MouseEvent.BUTTON1))
    }
    private fun enterEnabled(): Boolean {
        val e = AnActionEvent.createFromAnAction(enter(), null, "test", DataContext.EMPTY_CONTEXT)
        enter().update(e)
        return e.presentation.isEnabled
    }

    fun testFourSuitColorsAndFaceRanksAreActuallyPaintedAtEveryFontSize() {
        val session = session()
        assertEquals(listOf("10","J","Q","K","A"), listOf(32,36,40,44,48).map(::texasRank))
        for (dark in listOf(false,true)) assertEquals(4, (0..3).map { texasColor(it,dark) }.toSet().size)
        val editor = myFixture.editor
        for (size in listOf(8,14,32)) {
            session.fontSize = size
            val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
            val image = BufferedImage(inlay.widthInPixels, inlay.heightInPixels, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            try { inlay.renderer.paint(inlay, g as java.awt.Graphics, Rectangle(0,0,image.width,image.height), TextAttributes()) } finally { g.dispose() }
            val bg = editor.colorsScheme.defaultBackground
            val dark = bg.red + bg.green + bg.blue < 384
            val metrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(size).toFloat()))
            val colors = mutableSetOf<Int>()
            for (y in JBUI.scale(6) + metrics.height until JBUI.scale(6) + 3 * metrics.height) {
                for (x in 0 until image.width) colors.add(image.getRGB(x,y))
            }
            for (suit in 0..3) {
                val target = texasColor(suit,dark)
                // Small antialiased glyphs may contain only foreground/background blends.
                val direction = listOf(target.red-bg.red, target.green-bg.green, target.blue-bg.blue)
                assertTrue("Missing suit $suit at font $size", colors.any { rgb ->
                    val pixel = java.awt.Color(rgb)
                    val delta = listOf(pixel.red-bg.red, pixel.green-bg.green, pixel.blue-bg.blue)
                    val alpha = delta.zip(direction).sumOf { (a,b) -> a.toDouble()*b } / direction.sumOf { it.toDouble()*it }
                    alpha in 0.25..1.01 && delta.zip(direction).all { (a,b) -> kotlin.math.abs(a-alpha*b) < 4 }
                })
            }
        }
    }

    fun testManualVisibilityOutsideClickAndFontHitBoundsKeepSource() {
        val session = session()
        val hand = session.texasState.hand
        session.toggleBoard(myFixture.editor)
        assertFalse(session.isBoardVisible)
        session.toggleBoard(myFixture.editor)
        assertTrue(session.isBoardVisible)
        assertEquals(hand, session.texasState.hand)
        for (size in listOf(8,14,32)) {
            session.fontSize = size
            click(1,1)
            assertTrue(session.isBoardVisible)
            assertFalse(enterEnabled())
            val inlay = myFixture.editor.inlayModel.getBlockElementsInRange(0,myFixture.editor.document.textLength).single()
            val bounds = inlay.bounds ?: error("No bounds")
            click(bounds.x + JBUI.scale(7), bounds.y + JBUI.scale(7))
            assertTrue(enterEnabled())
        }
        assertEquals("val source = 1\n", myFixture.editor.document.text)
        assertFalse(session.canMove)
        assertFalse(session.canDdzCommand("play"))
    }

    fun testActionsFollowServerRightsOfflineAndRaiseRange() {
        val session = session()
        assertTrue(session.canTexasCommand("check"))
        assertFalse(session.canTexasCommand("call"))
        assertTrue(validTexasRaise(session.texasState,10))
        assertTrue(validTexasRaise(session.texasState,990))
        assertFalse(validTexasRaise(session.texasState,9))
        assertFalse(validTexasRaise(session.texasState,991))
        assertFalse(validTexasRaise(session.texasState,Long.MAX_VALUE))
        session.receiveTexas(TexasServerMessage("state", state = session.texasState.copy(online = listOf(true,false,true))))
        assertFalse(session.canTexasCommand("check"))
        session.receiveTexas(TexasServerMessage("state", state = session.texasState.copy(status = "finished", legalActions = listOf("ready"), online = listOf(true,true,true))))
        assertTrue(session.canTexasCommand("ready"))
        assertFalse(session.canTexasCommand("allin"))
    }

    fun testMalformedPayloadsAndEndpointSwitching() {
        val s = session().texasState
        assertTrue(validTexasState(s))
        assertFalse(validTexasState(s.copy(hand = listOf(52,2))))
        assertFalse(validTexasState(s.copy(board = listOf(48,1,2))))
        assertFalse(validTexasState(s.copy(stacks = listOf(-1,1000,1000))))
        assertFalse(validTexasState(s.copy(online = emptyList())))
        assertFalse(validTexasState(s.copy(bots = emptyList())))
        assertFalse(validTexasState(s.copy(bots = listOf(false, true, true, true))))
        assertFalse(validTexasState(s.copy(revealed = listOf(listOf(0,1), listOf(1,2),emptyList()))))
        assertFalse(validTexasState(s.copy(legalActions = listOf("steal"))))
        val texas = "wss://zhugetian.com/gomoku/texas/v2/ws"
        assertEquals(texas, gameEndpoint(DEFAULT_SERVER, GameKind.TEXAS))
        assertEquals(texas, gameEndpoint("wss://zhugetian.com/gomoku/ddz/v2/ws", GameKind.TEXAS))
        assertEquals(DEFAULT_SERVER, gameEndpoint(texas, GameKind.GOMOKU))
        assertEquals("wss://example.com/custom/ddz/v2/ws?q=1", gameEndpoint("wss://example.com/custom/texas/ws?q=1", GameKind.DDZ))
    }

    fun testEveryGameEndpointNormalizesOldAndNewRoutesAndPreservesQuery() {
        val query = "?invite=a%26b%3Dc&label=%E6%88%BF%E9%97%B4&plus=a+b"
        val destinations = mapOf(GameKind.GOMOKU to "/ws", GameKind.DDZ to "/ddz/v2/ws", GameKind.TEXAS to "/texas/v2/ws")
        for (source in listOf("/ws", "/ddz/ws", "/ddz/v2/ws", "/texas/ws", "/texas/v2/ws")) {
            for ((kind, target) in destinations) {
                assertEquals("wss://example.com/custom$target$query", gameEndpoint("wss://example.com/custom$source$query", kind))
            }
        }
        assertEquals("ws://localhost:8091/gomoku/texas/v2/ws", gameEndpoint("ws://localhost:8091/gomoku/ws", GameKind.TEXAS))
        assertEquals(2, TEXAS_PROTOCOL_VERSION)
    }

    fun testRobotStartIsHostOnlyAndMenuFollowsWaitingState() {
        val session = session()
        val action = ActionManager.getInstance().getAction("Texas.Start") ?: error("Start menu is missing")
        fun waiting(seat: Int, online: List<Boolean>) {
            session.receiveTexas(TexasServerMessage("welcome", roomCode = "AABBCCDD", seat = seat, token = "x".repeat(43),
                state = TexasState(online = online, revision = session.texasState.revision + 1)))
        }
        fun startEnabled(): Boolean {
            val event = AnActionEvent.createFromAnAction(action, null, "test", DataContext { key ->
                if (CommonDataKeys.PROJECT.`is`(key)) project else null
            })
            action.update(event)
            return event.presentation.isEnabled
        }
        waiting(1, listOf(true, false, false))
        assertTrue(session.canTexasCommand("start"))
        assertTrue(startEnabled())
        assertFalse(session.canTexasCommand("check"))
        waiting(1, listOf(true, true, false))
        assertTrue(session.canTexasCommand("start"))
        waiting(2, listOf(true, true, false))
        assertFalse(session.canTexasCommand("start"))
        assertFalse(startEnabled())
        waiting(1, listOf(false, true, false))
        assertFalse(session.canTexasCommand("start"))
        waiting(1, listOf(true, true, true))
        assertFalse(session.canTexasCommand("start"))
        session.receiveTexas(TexasServerMessage("state", state = session.texasState.copy(status = "playing",
            bots = listOf(false, true, true), legalActions = listOf("check"))))
        assertFalse(session.canTexasCommand("start"))
        assertFalse(startEnabled())
        assertTrue(session.canTexasCommand("check"))
        session.receiveTexas(TexasServerMessage("state", state = session.texasState.copy(online = listOf(false, true, true))))
        assertFalse(session.canTexasCommand("check"))
    }

    fun testWaitingInitClickSendsStartAtEveryFontSizeAndAutoOnlyAddsOneRow() {
        val session = session()
        val sent = mutableListOf<TexasClientMessage>()
        val socket = Proxy.newProxyInstance(WebSocket::class.java.classLoader, arrayOf(WebSocket::class.java)) { _, method, args ->
            when (method.name) {
                "sendText" -> {
                    sent.add(wireJson.decodeFromString<TexasClientMessage>(args[0].toString()))
                    CompletableFuture.completedFuture<WebSocket>(null)
                }
                "abort" -> null
                else -> error("Unexpected socket call: ${method.name}")
            }
        } as WebSocket
        GomokuSession::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(session, socket)
        val editor = myFixture.editor
        for (size in listOf(8, 14, 32)) {
            session.receiveTexas(TexasServerMessage("welcome", roomCode = "AABBCCDD", seat = 1, token = "x".repeat(43),
                state = TexasState(online = listOf(true, false, false), revision = session.texasState.revision + 1)))
            session.fontSize = size
            val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
            val bounds = inlay.bounds ?: error("No bounds")
            val metrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(size).toFloat()))
            val padding = JBUI.scale(6)
            val before = sent.size
            click(bounds.x + padding + metrics.stringWidth("init()") / 2, bounds.y + bounds.height - padding - metrics.height / 2)
            assertEquals(before + 1, sent.size)
            assertEquals("start", sent.last().type)
            assertEquals(TEXAS_PROTOCOL_VERSION, sent.last().protocol)
            assertEquals(session.texasState.revision, sent.last().revision)
            assertFalse(session.canTexasCommand("start"))
            session.receiveTexas(TexasServerMessage("state", requestId = sent.last().requestId, state = session.texasState))
            val humanHeight = inlay.heightInPixels
            session.receiveTexas(TexasServerMessage("state", state = session.texasState.copy(bots = listOf(false, true, true))))
            assertEquals(humanHeight + metrics.height, inlay.heightInPixels)
            session.receiveTexas(TexasServerMessage("state", state = session.texasState.copy(bots = listOf(false, false, false))))
            assertEquals(humanHeight, inlay.heightInPixels)
        }
        assertEquals("val source = 1\n", editor.document.text)
    }

}
