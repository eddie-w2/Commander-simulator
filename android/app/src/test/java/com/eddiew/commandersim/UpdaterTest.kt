package com.eddiew.commandersim

import com.eddiew.commandersim.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun `version comparison`() {
        assertTrue(Updater.compareVersions("0.1.03", "0.1.02") > 0)
        assertTrue(Updater.compareVersions("v0.1.10", "0.1.9") > 0)
        assertTrue(Updater.compareVersions("0.2", "0.1.99") > 0)
        assertEquals(0, Updater.compareVersions("v0.1.02", "0.1.2"))
        assertTrue(Updater.compareVersions("0.1.02", "0.1.03") < 0)
    }

    @Test
    fun `parses github release`() {
        val r = Updater.parseRelease("""
            {"tag_name":"v0.1.03","name":"Commander Sim v0.1.03","body":"notes","html_url":"https://github.com/x",
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
                       {"name":"commander-sim-v0.1.03.apk","browser_download_url":"https://x/app.apk","size":12345}]}
        """)!!
        assertEquals("0.1.03", r.version)
        assertEquals("https://x/app.apk", r.apkUrl)
        assertEquals(12345L, r.apkSize)
        assertNull(Updater.parseRelease("""{"tag_name":"v1","assets":[]}"""))
    }
}
