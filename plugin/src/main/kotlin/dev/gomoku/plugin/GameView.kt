package dev.gomoku.plugin

import com.intellij.openapi.Disposable

interface GameView : Disposable {
    fun attach()
    fun refresh()
}
