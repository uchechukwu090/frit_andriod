package com.frit.app.core

sealed class MacroAction {
    data class Tap(val x: Float, val y: Float) : MacroAction()
    data class Swipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val duration: Int = 300
    ) : MacroAction()
    data class LongPress(val x: Float, val y: Float) : MacroAction()
    data class Wait(val ms: Long) : MacroAction()
}