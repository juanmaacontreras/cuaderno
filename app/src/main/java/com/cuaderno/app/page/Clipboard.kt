package com.cuaderno.app.page

import cuaderno.core.Clip

/**
 * What "Copiar" or "Cortar" took, until "Pegar" uses it: on any page of any notebook, so it
 * lives with the app, not with a screen. Gone when the app is (nothing here is a note yet).
 */
object Clipboard {
    var clip: Clip? = null
}
