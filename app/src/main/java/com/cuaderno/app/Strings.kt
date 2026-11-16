package com.cuaderno.app

/** Every text the app shows, in one place. Rioplatense Spanish. */
object Strings {
    const val ENGINE_CLASSIC = "Clásico"
    const val ENGINE_FRONT = "Baja latencia"
    const val ENGINE_INK = "Jetpack Ink"

    const val ERASER = "Goma"
    const val UNDO = "Deshacer"
    const val CLEAR = "Borrar todo"
    const val RECORD = "Grabar trazos"
    const val RECORD_STOP = "Parar y mandar"
    const val RECORD_EMPTY = "No se grabó ningún trazo"
    const val RECORD_SHARE = "Mandar la grabación"

    const val ABOUT_CLASSIC = "El trazo de la pizarra de Enlace, tal cual"
    const val ABOUT_FRONT = "El mismo trazo, dibujado directo en pantalla sin esperar al cuadro"
    const val ABOUT_INK = "La librería de Google: directo en pantalla y adivinando el movimiento"
    const val LATENCY_NONE = "escribí para medir"
    const val LATENCY_ABOUT = "del lápiz a terminar de dibujar, sin contar la pantalla"
    const val RECORDING = "grabando"

    const val ENGINE_FAILED = "Este motor no arrancó en este equipo. Volví a Clásico."

    const val UPDATE = "Actualizar"
    const val UPDATE_DOWNLOADING = "Bajando…"
    const val UPDATE_PERMISSION = "Permití instalar apps desde Cuaderno y tocá de nuevo"
    const val UPDATE_FAILED = "No se pudo bajar la actualización"

    fun latency(median: Float, p95: Float, count: Int) =
        "retraso " + ms(median) + " ms (el 95 % bajo " + ms(p95) + " ms) · " + count + " muestras"

    fun samples(count: Int) = "$RECORDING · $count muestras"

    private fun ms(value: Float) = String.format(java.util.Locale("es", "AR"), "%.1f", value)
}
