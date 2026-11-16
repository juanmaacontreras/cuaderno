package com.cuaderno.app

import com.cuaderno.app.ink.OwnSurface
import cuaderno.core.Recording

/** Feeds a pen recording to our own ink, the way the tablet would. */
object Replay {
    fun load(name: String): Recording =
        Recording.parse(Replay::class.java.getResourceAsStream("/recordings/$name")!!.bufferedReader().readText())

    fun names(): List<String> =
        java.io.File(Replay::class.java.getResource("/recordings")!!.toURI()).list()!!.filter { it.endsWith(".csv") }.sorted()

    /** [scale] fits a recording made on another screen size. */
    fun into(surface: OwnSurface, recording: Recording, scale: Float = 1f) {
        for (s in recording.samples) {
            val rub = s.tool == 4 || s.buttons and 0x20 != 0
            when (s.action) {
                'D' -> surface.down(s.x * scale, s.y * scale, s.pressure, rub)
                'M' -> surface.move(s.x * scale, s.y * scale, s.pressure)
                else -> surface.up()
            }
        }
    }
}
