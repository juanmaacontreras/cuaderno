package com.cuaderno.app

/** Every text the app shows, in one place. Rioplatense Spanish. */
object Strings {
    const val EXPORT = "Exportar a PDF"
    const val EXPORT_EMPTY = "Todavía no hay nada escrito"
    const val EXPORT_FAILED = "No se pudo armar el PDF"
    const val EXPORT_SHARE = "Mandar el PDF"

    const val PAPER_DARK = "Papel oscuro"
    const val PAPER_LIGHT = "Papel claro"

    const val FLUENCY = "Medir fluidez"
    const val FLUENCY_STOP = "Dejar de medir fluidez"
    fun fluency(frames: Int, worstMs: Int, hz: Int) = "$frames cuadros/s · peor $worstMs ms · pantalla $hz Hz"

    const val RECORD = "Grabar trazos"
    const val RECORD_STOP = "Parar la grabación y mandarla"
    const val RECORD_EMPTY = "No se grabó ningún trazo"
    const val RECORD_SHARE = "Mandar la grabación"

    const val SAVE_FAILED = "No se pudo guardar: "
    const val UNREADABLE = "El cuaderno guardado no se pudo leer. Quedó apartado sin tocar y empecé uno nuevo. "

    const val NOTEBOOKS = "Cuadernos"
    const val ALL = "Todos"
    const val NO_SUBJECT = "Sin materia"
    const val NEW_SUBJECT = "Materia nueva"
    const val NEW_NOTEBOOK = "Cuaderno nuevo"
    const val EDIT_NOTEBOOK = "Cuaderno"
    const val EMPTY_SUBJECT = "Todavía no hay cuadernos acá"
    const val UNTITLED = "Sin título"
    const val UNREADABLE_SHORT = "No se pudo leer"
    const val NAME = "Nombre"
    const val NAME_HINT = "Por ejemplo: Teoría · Unidad 1"
    const val SUBJECT = "Materia"
    const val SUBJECT_HINT = "Por ejemplo: Circuitos"
    const val SUBJECT_EMPTY = "Ponele un nombre"
    const val SUBJECT_BAD = "Ese nombre ya está o no se puede usar"
    const val PAPER = "Papel"
    const val GRID = "Cuadriculado"
    const val RULED = "Rayado"
    const val DOTTED = "Punteado"
    const val BLANK = "Liso"
    const val CREATE = "Crear"
    const val SAVE = "Guardar"
    const val CANCEL = "Cancelar"
    const val EDIT_FAILED = "No se pudo cambiar el cuaderno"
    const val TODAY = "hoy"
    const val YESTERDAY = "ayer"
    val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    fun notebooks(n: Int) = if (n == 1) "1 cuaderno" else "$n cuadernos"
    fun pages(n: Int) = if (n == 1) "1 página" else "$n páginas"

    const val PAGES = "Páginas"
    fun pagesTitle(n: Int) = "Páginas · $n"
    const val NEW_PAGE_AFTER = "Página nueva después"
    const val DUPLICATE = "Duplicar"
    fun paperIs(name: String) = "Papel: $name"

    const val UPDATE = "Actualizar"
    const val UPDATE_DOWNLOADING = "Bajando…"
    const val UPDATE_PERMISSION = "Permití instalar apps desde Cuaderno y tocá de nuevo"
    const val UPDATE_FAILED = "No se pudo bajar la actualización"
}
