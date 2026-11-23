package com.cuaderno.app.page

import cuaderno.core.Clip

/**
 * What "Copiar" or "Cortar" took, until "Pegar" uses it: on any page of any notebook, so it
 * lives with the app, not with a screen. Gone when the app is (nothing here is a note yet).
 */
object Clipboard {
    var clip: Clip? = null
    /**
     * When Android's clipboard was last set as [clip] was copied (its own timestamp, 0 when
     * empty): if it changed since, something was copied in another app after, and that is pasted.
     */
    var systemStamp = 0L
}
