package com.cuaderno.app

import com.cuaderno.app.page.PageView
import cuaderno.core.Recording

/** Feeds a pen recording to the page, the way the tablet would. */
object Replay {
    fun load(name: String): Recording =
        Recording.parse(Replay::class.java.getResourceAsStream("/recordings/$name")!!.bufferedReader().readText())

    fun names(): List<String> =
        java.io.File(Replay::class.java.getResource("/recordings")!!.toURI()).list()!!.filter { it.endsWith(".csv") }.sorted()

    /** [scale] and the offsets fit a recording made on another screen. */
    fun into(page: PageView, recording: Recording, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f) {
        for (s in recording.samples) {
            val rub = s.tool == 4 || s.buttons and 0x20 != 0
            val x = s.x * scale + dx
            val y = s.y * scale + dy
            when (s.action) {
                'D' -> page.penDown(x, y, rub, s.timeMs, s.pressure, s.tilt, s.orientation)
                'M' -> page.penMove(x, y, s.timeMs, s.pressure, s.tilt, s.orientation)
                else -> page.penUp()
            }
        }
    }
}
