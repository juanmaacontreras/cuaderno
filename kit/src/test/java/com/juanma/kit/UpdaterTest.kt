package com.juanma.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test fun aReleaseWithAnApkIsOffered() {
        val release = Updater.parse("""{"tag_name":"v12","assets":[
            {"name":"notes.txt","browser_download_url":"https://example.com/notes.txt"},
            {"name":"cuaderno.apk","browser_download_url":"https://example.com/cuaderno.apk"}]}""")!!
        assertEquals(12L, release.version)
        assertEquals("https://example.com/cuaderno.apk", release.apkUrl)
    }

    @Test fun anythingElseIsNot() {
        assertNull(Updater.parse("not json"))
        assertNull(Updater.parse("""{"tag_name":"nightly","assets":[]}"""))
        assertNull(Updater.parse("""{"tag_name":"v3","assets":[{"name":"a.apk","browser_download_url":"http://plain"}]}"""))
    }
}
